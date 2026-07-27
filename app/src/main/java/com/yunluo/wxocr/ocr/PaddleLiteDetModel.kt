package com.yunluo.wxocr.ocr

import android.content.Context
import android.util.Log
import com.yunluo.wxocr.config.AppConfig
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.*

class PaddleLiteDetModel(private val device: String = "cpu") {

    private val modelDir: String

    init {
        log("=== PP-OCRv5_mobile_det init start, device=$device")
        val t1 = System.currentTimeMillis()
        modelDir = copyModelIfNeeded(AppConfig.appContext)
        log("模型目录确认: $modelDir (${System.currentTimeMillis() - t1}ms)")
        try {
            Class.forName("com.baidu.paddle.lite.MobileConfig")
            Class.forName("com.baidu.paddle.lite.ConfigBase")
            Class.forName("com.baidu.paddle.lite.PaddlePredictor")
            Class.forName("com.baidu.paddle.lite.Tensor")
            log("PaddleLite 反射 API 校验通过")
        } catch (e: ClassNotFoundException) {
            log("PaddleLite 反射 API 校验失败: ${e.message}")
        } catch (e: Throwable) {
            log("PaddleLite 反射 API 校验异常: ${e.message}")
        }
    }

    fun release() {}

    fun predict(mat: Mat): List<DetBox> {
        val start = System.currentTimeMillis()
        log("=== det predict 开始, 图像=${mat.cols()}x${mat.rows()}")
        log("det 模型文件: $modelDir/PP-OCRv5_mobile_det.nb 存在=${File(modelDir, "PP-OCRv5_mobile_det.nb").exists()} 大小=${File(modelDir, "PP-OCRv5_mobile_det.nb").length()}")

        try {
            // Step 1: 创建 predictor
            log("predict: Step1 createPredictor 开始...")
            val predictor: Any
            try {
                predictor = createPredictor()
                log("predict: Step1 createPredictor 完成")
            } catch (e: Throwable) {
                log("predict: Step1 createPredictor 失败: ${e::class.simpleName}: ${e.message}")
                log("predict: 堆栈: ${e.stackTraceToString()}")
                return emptyList()
            }

            // Step 2: 预处理
            log("predict: Step2 预处理 开始...")
            val (inputData, inW, inH) = preprocess(mat)
            log("predict: Step2 预处理 -> ${inW}x${inH}, ${inputData.size} floats")

            // Step 3: 设置输入
            log("predict: Step3 设置输入 tensor 开始...")
            val inputTensor = predictorGetInput(predictor, 0)
            predictorResize(inputTensor, longArrayOf(1, 3, inH.toLong(), inW.toLong()))
            predictorSetData(inputTensor, inputData)
            log("predict: Step3 设置输入 tensor 完成")

            // Step 4: 推理（带超时）
            log("predict: Step4 predictorRun 开始...")
            val inferStart = System.currentTimeMillis()
            var runFuture: Future<*>? = null
            try {
                runFuture = predictorExecutor.submit(Callable { predictorRun(predictor) })
                runFuture.get(8, TimeUnit.SECONDS)
                log("predict: Step4 predictorRun 完成 (${System.currentTimeMillis() - inferStart}ms)")
            } catch (e: TimeoutException) {
                log("predict: Step4 predictorRun 超时 (${System.currentTimeMillis() - inferStart}ms)")
                runFuture?.cancel(true)
                return emptyList()
            } catch (e: Throwable) {
                log("predict: Step4 predictorRun 异常: ${e::class.simpleName}: ${e.message}")
                runFuture?.cancel(true)
                return emptyList()
            }

            // Step 5: 获取输出
            log("predict: Step5 获取输出 tensor 开始...")
            val outputTensor = predictorGetOutput(predictor, 0)
            val outputData = predictorGetFloatData(outputTensor)
            val outputShape = predictorGetShape(outputTensor)
            log("predict: Step5 输出 shape=[${outputShape.joinToString(", ")}], 数据长度=${outputData.size}")

            if (outputData.isEmpty()) {
                log("predict: 输出数据为空，返回空列表")
                predictorClose(predictor)
                return emptyList()
            }

            val (outH, outW) = parseOutputShape(outputShape)
            log("predict: 解析输出尺寸: ${outH}x${outW}")

            val totalPixels = outH * outW
            if (totalPixels <= 0 || totalPixels > outputData.size) {
                log("predict: 输出尺寸不合法: $outH x $outW, 数据长度=${outputData.size}")
                predictorClose(predictor)
                return emptyList()
            }

            val mapMat = Mat(outH, outW, CvType.CV_32F)
            val subData = outputData.copyOfRange(0, totalPixels)
            mapMat.put(0, 0, subData)

            val boxes = dbPostProcess(mat, mapMat, inW, inH)
            mapMat.release()
            predictorClose(predictor)

            val total = System.currentTimeMillis() - start
            log("=== det predict 完成: ${boxes.size} 框 (${total}ms)")
            return boxes
        } catch (e: Throwable) {
            log("=== det predict 异常: ${e::class.simpleName}: ${e.message}")
            log("=== 堆栈: ${e.stackTraceToString()}")
            return emptyList()
        }
    }

    data class DetBox(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val x3: Int, val y3: Int, val x4: Int, val y4: Int,
        val score: Float,
        val centerX: Int,
        val centerY: Int
    )

    // ---- 预处理 ----

    private fun preprocess(src: Mat): Triple<FloatArray, Int, Int> {
        val h = src.rows()
        val w = src.cols()
        val maxSide = AppConfig.DET_MAX_SIDE_LEN
        val ratio = maxSide.toFloat() / maxOf(h, w).coerceAtLeast(1)
        var resizedW = (w * ratio).toInt()
        var resizedH = (h * ratio).toInt()
        // 补齐到 32 的倍数（DBNet 要求输入宽高能被 32 整除）
        resizedW = (resizedW + 31) / 32 * 32
        resizedH = (resizedH + 31) / 32 * 32

        val resized = Mat()
        Imgproc.resize(src, resized, Size(resizedW.toDouble(), resizedH.toDouble()))
        log("缩放: ${w}x$h -> ${resizedW}x${resizedH} (ratio=$ratio)")

        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        resized.release()

        val pixels = ByteArray(resizedW * resizedH * 3)
        rgb.get(0, 0, pixels)
        rgb.release()

        val ch = resizedH * resizedW
        val inputData = FloatArray(3 * ch)
        val mean = AppConfig.DET_MEAN
        val std = AppConfig.DET_STD

        for (i in 0 until ch) {
            val r = (pixels[3 * i + 0].toInt() and 0xFF) / 255.0f
            val g = (pixels[3 * i + 1].toInt() and 0xFF) / 255.0f
            val b = (pixels[3 * i + 2].toInt() and 0xFF) / 255.0f
            inputData[i] = (b - mean[2]) / std[2]
            inputData[ch + i] = (g - mean[1]) / std[1]
            inputData[2 * ch + i] = (r - mean[0]) / std[0]
        }
        return Triple(inputData, resizedW, resizedH)
    }

    private fun parseOutputShape(shape: LongArray): Pair<Int, Int> {
        return when (shape.size) {
            4 -> shape[2].toInt() to shape[3].toInt()
            3 -> shape[1].toInt() to shape[2].toInt()
            else -> {
                log("未知输出shape维度: ${shape.size}")
                1 to 1
            }
        }
    }

    // ---- DB 后处理 ----

    private fun dbPostProcess(src: Mat, probMap: Mat, inW: Int, inH: Int): List<DetBox> {
        val srcH = src.rows()
        val srcW = src.cols()
        val mapH = probMap.rows()
        val mapW = probMap.cols()
        log("DB后处理: 概率图=${mapW}x${mapH}, 原图=${srcW}x${srcH}")

        val scaleX = srcW.toFloat() / inW
        val scaleY = srcH.toFloat() / inH

        val binary = Mat()
        Imgproc.threshold(probMap, binary, AppConfig.DET_DB_THRESH.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        binary.convertTo(binary, CvType.CV_8U)

        if (AppConfig.DET_USE_DILATION) {
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            Imgproc.dilate(binary, binary, kernel)
            kernel.release()
        }

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        log("轮廓数: ${contours.size}")

        val ratio = AppConfig.DET_DB_UNCLIP_RATIO
        val boxThresh = AppConfig.DET_DB_BOX_THRESH
        val boxes = mutableListOf<DetBox>()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 3.0) {
                contour.release()
                continue
            }

            val arcLen = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            if (arcLen < 1.0) {
                contour.release()
                continue
            }

            val unclipDist = area * ratio / arcLen

            val points2f = MatOfPoint2f(*contour.toArray())
            val rotRect = Imgproc.minAreaRect(points2f)
            points2f.release()

            val cx = rotRect.center.x
            val cy = rotRect.center.y
            val newW = rotRect.size.width + unclipDist * 2
            val newH = rotRect.size.height + unclipDist * 2
            val newSize = org.opencv.core.Size(newW.coerceAtLeast(1.0), newH.coerceAtLeast(1.0))
            val newRect = RotatedRect(Point(cx, cy), newSize, rotRect.angle)

            val pts = arrayOfNulls<Point>(4)
            newRect.points(pts)
            @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
            val boxPts = pts.map { Point(it!!.x * scaleX, it!!.y * scaleY) }

            val x1 = boxPts[0].x.toInt().coerceIn(0, srcW - 1)
            val y1 = boxPts[0].y.toInt().coerceIn(0, srcH - 1)
            val x2 = boxPts[1].x.toInt().coerceIn(0, srcW - 1)
            val y2 = boxPts[1].y.toInt().coerceIn(0, srcH - 1)
            val x3 = boxPts[2].x.toInt().coerceIn(0, srcW - 1)
            val y3 = boxPts[2].y.toInt().coerceIn(0, srcH - 1)
            val x4 = boxPts[3].x.toInt().coerceIn(0, srcW - 1)
            val y4 = boxPts[3].y.toInt().coerceIn(0, srcH - 1)

            val score = boxScore(probMap, x1.toFloat() / scaleX, y1.toFloat() / scaleY,
                x2.toFloat() / scaleX, y2.toFloat() / scaleY,
                x3.toFloat() / scaleX, y3.toFloat() / scaleY,
                x4.toFloat() / scaleX, y4.toFloat() / scaleY)

            if (score >= boxThresh) {
                val centerX = (x1 + x2 + x3 + x4) / 4
                val centerY = (y1 + y2 + y3 + y4) / 4
                boxes.add(DetBox(x1, y1, x2, y2, x3, y3, x4, y4, score, centerX, centerY))
            }
            contour.release()
        }
        binary.release()

        log("DB 后处理: ${boxes.size} 框通过阈值")
        return boxes
    }

    private fun boxScore(prob: Mat, vararg pts: Float): Float {
        val h = prob.rows()
        val w = prob.cols()
        var sum = 0f
        var count = 0
        val xs = intArrayOf(pts[0].toInt(), pts[2].toInt(), pts[4].toInt(), pts[6].toInt())
        val ys = intArrayOf(pts[1].toInt(), pts[3].toInt(), pts[5].toInt(), pts[7].toInt())
        val minX = xs.min().coerceIn(0, w - 1)
        val maxX = xs.max().coerceIn(0, w - 1)
        val minY = ys.min().coerceIn(0, h - 1)
        val maxY = ys.max().coerceIn(0, h - 1)
        val buffer = FloatArray((maxX - minX + 1) * (maxY - minY + 1))
        prob.get(minY, minX, buffer)
        val stride = maxX - minX + 1
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val v = buffer[(y - minY) * stride + (x - minX)]
                sum += v
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    // ---- PaddleLite 反射 ----

    private val configBaseClass by lazy { Class.forName("com.baidu.paddle.lite.ConfigBase") }
    private val mobileConfigClass by lazy { Class.forName("com.baidu.paddle.lite.MobileConfig") }
    private val paddlePredictorClass by lazy { Class.forName("com.baidu.paddle.lite.PaddlePredictor") }
    private val tensorClass by lazy { Class.forName("com.baidu.paddle.lite.Tensor") }

    private fun createPredictor(): Any {
        log("createPredictor: 开始创建 MobileConfig...")
        val config = mobileConfigClass.getDeclaredConstructor().newInstance()
        val modelPath = "$modelDir/PP-OCRv5_mobile_det.nb"
        log("createPredictor: setModelFromFile=$modelPath")
        mobileConfigClass.getMethod("setModelFromFile", String::class.java).invoke(config, modelPath)
        if (device == "cpu") {
            try {
                mobileConfigClass.getMethod("setThreads", Int::class.java).invoke(config, 4)
                log("createPredictor: setThreads=4")
            } catch (_: NoSuchMethodException) {
                log("createPredictor: setThreads 方法不存在")
            }
        }
        log("createPredictor: 调用 createPaddlePredictor...")
        val predictor = paddlePredictorClass.getMethod("createPaddlePredictor", configBaseClass)
            .invoke(null, config) as Any
        log("createPredictor: PaddlePredictor 创建完成: $predictor")
        return predictor
    }

    private fun predictorGetInput(predictor: Any, idx: Int): Any =
        paddlePredictorClass.getMethod("getInput", Int::class.java).invoke(predictor, idx) as Any

    private fun predictorResize(tensor: Any, shape: LongArray) =
        tensorClass.getMethod("resize", LongArray::class.java).invoke(tensor, shape)

    private fun predictorSetData(tensor: Any, data: FloatArray) =
        tensorClass.getMethod("setData", FloatArray::class.java).invoke(tensor, data)

    private fun predictorRun(predictor: Any) =
        paddlePredictorClass.getMethod("run").invoke(predictor)

    private fun predictorGetOutput(predictor: Any, idx: Int): Any =
        paddlePredictorClass.getMethod("getOutput", Int::class.java).invoke(predictor, idx) as Any

    private fun predictorGetFloatData(tensor: Any): FloatArray =
        tensorClass.getMethod("getFloatData").invoke(tensor) as FloatArray

    private fun predictorGetShape(tensor: Any): LongArray =
        tensorClass.getMethod("shape").invoke(tensor) as LongArray

    private fun predictorClose(predictor: Any) {
        try { paddlePredictorClass.getMethod("close").invoke(predictor) } catch (_: NoSuchMethodException) {}
    }

    // ---- 模型文件复制 ----

    private fun copyModelIfNeeded(ctx: Context): String {
        val modelDir = AppConfig.paddleRecModelDir
        val targetFile = File(modelDir, "PP-OCRv5_mobile_det.nb")
        if (!targetFile.exists()) {
            log("检测模型不存在，从 assets 复制...")
            try {
                ctx.assets.open("models/PP-OCRv5_mobile_det.nb").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                log("检测模型复制成功: ${targetFile.length()} bytes")
            } catch (e: java.io.FileNotFoundException) {
                log("检测模型未找到: assets/models/PP-OCRv5_mobile_det.nb")
            } catch (e: Exception) {
                log("检测模型复制失败: ${e.message}")
            }
        } else {
            log("检测模型已存在: ${targetFile.length()} bytes")
        }
        return modelDir.absolutePath
    }

    companion object {
        private const val TAG = "PaddleLiteDetModel"
        private val predictorExecutor = Executors.newCachedThreadPool { r -> Thread(r, "det-predictor").also { it.isDaemon = true } }
        private var logFile: File? = null
        private fun log(msg: String) {
            Log.i(TAG, msg)
            try {
                if (logFile == null || logFile!!.length() > 1024 * 1024) {
                    val dir = File(AppConfig.saveDirPath)
                    dir.mkdirs()
                    logFile = File(dir, "det_model_log.txt")
                }
                logFile!!.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $msg\n")
            } catch (_: Exception) {}
        }
    }
}
