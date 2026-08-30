package com.yunluo.wxocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.yunluo.wxocr.config.AppConfig
import java.io.File
import java.io.FileOutputStream

class PaddleLiteRecModel(private val device: String = "cpu") {

    private val modelDir: String
    private val labelList: List<String>

    init {
        val ctx = AppConfig.appContext
        log("=== ppocrv4_mobile_rec init start, device=$device")

        val t1 = System.currentTimeMillis()
        modelDir = copyModelIfNeeded(ctx)
        log("模型目录确认: $modelDir (${System.currentTimeMillis() - t1}ms)")

        val t2 = System.currentTimeMillis()
        labelList = loadLabelList(ctx)
        log("label 表: ${labelList.size} 字符 (${System.currentTimeMillis() - t2}ms)")

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

    fun release() {
        try {
            cachedPredictor?.let { predictorClose(it) }
        } catch (_: Exception) {}
        cachedPredictor = null
    }

    @Synchronized
    fun predict(bmp: Bitmap): String {
        val startTime = System.currentTimeMillis()
        log("=== predict 开始, 图像=${bmp.width}x${bmp.height}")
        log("rec 模型文件: $modelDir/ppocrv4_mobile_rec.nb 存在=${File(modelDir, "ppocrv4_mobile_rec.nb").exists()} 大小=${File(modelDir, "ppocrv4_mobile_rec.nb").length()}")
        try {
            log("=== Step 1: getPredictor 开始...")
            val predictor: Any
            try {
                predictor = getPredictor()
                log("=== Step 1 OK: predictor=$predictor")
            } catch (e: Throwable) {
                log("=== Step 1 createPredictor 失败: ${e::class.simpleName}: ${e.message}")
                log("=== 堆栈: ${e.stackTraceToString()}")
                return ""
            }

            val preprocessStart = System.currentTimeMillis()
            val (inputData, width) = preprocessBitmap(bmp)
            log("=== Step 2: 预处理 ${width}x48 -> ${inputData.size} floats (${System.currentTimeMillis() - preprocessStart}ms)")

            log("=== Step 3: getInput(0)")
            val inputTensor = predictorGetInput(predictor, 0)
            log("=== Step 4: resize [1,3,48,$width]")
            predictorResize(inputTensor, longArrayOf(1, 3, INPUT_SIZE.toLong(), width.toLong()))
            log("=== Step 5: setData")
            predictorSetData(inputTensor, inputData)

            val runStart = System.currentTimeMillis()
            log("=== Step 6: predictorRun")
            predictorRun(predictor)
            val runTime = System.currentTimeMillis() - runStart
            log("=== Step 6 OK: 推理完成 ($runTime ms)")

            log("=== Step 7: getOutput(0)")
            val outputTensor = predictorGetOutput(predictor, 0)
            log("=== Step 8: getFloatData + shape")
            val outputData = predictorGetFloatData(outputTensor)
            val outputShape = predictorGetShape(outputTensor)
            log("=== 输出: outputShape=[${outputShape.joinToString(", ")}], 数据长度=${outputData.size}")

            val (seqLen, numClasses) = when (outputShape.size) {
                4 -> outputShape[2].toInt() to outputShape[3].toInt()
                3 -> outputShape[1].toInt() to outputShape[2].toInt()
                else -> 1 to 1
            }
            log("=== CTC seqLen=$seqLen numClasses=$numClasses labelList.size=${labelList.size}")

            val text = ctcDecode(outputData, seqLen, numClasses)

            val totalTime = System.currentTimeMillis() - startTime
            log("=== predict 结果: \"$text\" (${totalTime}ms)")
            return text
        } catch (e: Throwable) {
            log("=== predict 异常: ${e::class.simpleName}: ${e.message}")
            log("=== 堆栈: ${e.stackTraceToString()}")
            return ""
        }
    }

    private var cachedPredictor: Any? = null

    private fun getPredictor(): Any {
        if (cachedPredictor == null) {
            cachedPredictor = createPredictor()
        }
        return cachedPredictor!!
    }

    fun predictBatch(bmps: List<Bitmap>): List<String> {
        Log.d(TAG, "predictBatch 开始, 批次大小=${bmps.size}")
        val results = bmps.mapIndexed { i, bmp ->
            Log.d(TAG, "predictBatch 第${i + 1}/ ${bmps.size} 个")
            val result = predict(bmp)
            bmp.recycle()
            result
        }
        val nonEmpty = results.count { it.isNotBlank() }
        Log.d(TAG, "predictBatch 完成: $nonEmpty/${bmps.size} 非空")
        return results
    }

    // ---- PaddleLite 反射调用 ----

    private val configBaseClass by lazy {
        try {
            Class.forName("com.baidu.paddle.lite.ConfigBase")
        } catch (e: ClassNotFoundException) {
            log("ConfigBase 类反射加载失败: ${e.message}")
            throw e
        }
    }
    private val mobileConfigClass by lazy {
        try {
            Class.forName("com.baidu.paddle.lite.MobileConfig")
        } catch (e: ClassNotFoundException) {
            log("MobileConfig 类反射加载失败: ${e.message}")
            throw e
        }
    }
    private val paddlePredictorClass by lazy {
        try {
            Class.forName("com.baidu.paddle.lite.PaddlePredictor")
        } catch (e: ClassNotFoundException) {
            log("PaddlePredictor 类反射加载失败: ${e.message}")
            throw e
        }
    }
    private val tensorClass by lazy {
        try {
            Class.forName("com.baidu.paddle.lite.Tensor")
        } catch (e: ClassNotFoundException) {
            log("Tensor 类反射加载失败: ${e.message}")
            throw e
        }
    }

    private fun createPredictor(): Any {
        log("createPredictor: 创建 PaddlePredictor 实例")
        val config = mobileConfigClass.getDeclaredConstructor().newInstance()
        val modelPath = "$modelDir/ppocrv4_mobile_rec.nb"
        val modelFile = File(modelPath)
        log("模型文件: $modelPath, 存在=${modelFile.exists()}, 大小=${modelFile.length()}")
        log("调用 setModelFromFile($modelPath)")
        mobileConfigClass.getMethod("setModelFromFile", String::class.java)
            .invoke(config, modelPath)
        if (device == "cpu") {
            try {
                mobileConfigClass.getMethod("setThreads", Int::class.java)
                    .invoke(config, 4)
                log("设置 CPU 线程数: 4")
            } catch (e: NoSuchMethodException) {
                log("MobileConfig.setThreads 方法不存在")
            } catch (e: Exception) {
                log("设置 CPU 线程数失败: ${e.message}")
            }
        }
        log("调用 createPaddlePredictor(ConfigBase)")
        val predictor = paddlePredictorClass.getMethod("createPaddlePredictor", configBaseClass)
            .invoke(null, config) as Any
        log("PaddlePredictor 创建成功: $predictor")
        return predictor
    }

    private fun predictorGetInput(predictor: Any, idx: Int): Any {
        log("getInput(idx=$idx)")
        return try {
            paddlePredictorClass.getMethod("getInput", Int::class.java).invoke(predictor, idx)
        } catch (e: Exception) {
            log("getInput 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorResize(tensor: Any, shape: LongArray) {
        log("resize [${shape.joinToString(", ")}]")
        try {
            tensorClass.getMethod("resize", LongArray::class.java).invoke(tensor, shape)
        } catch (e: Exception) {
            log("Tensor.resize 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorSetData(tensor: Any, data: FloatArray) {
        log("setData: ${data.size} floats")
        try {
            tensorClass.getMethod("setData", FloatArray::class.java).invoke(tensor, data)
        } catch (e: Exception) {
            log("Tensor.setData 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorRun(predictor: Any) {
        log("predictorRun")
        try {
            paddlePredictorClass.getMethod("run").invoke(predictor)
        } catch (e: Exception) {
            log("PaddlePredictor.run 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorGetOutput(predictor: Any, idx: Int): Any {
        log("getOutput(idx=$idx)")
        return try {
            paddlePredictorClass.getMethod("getOutput", Int::class.java).invoke(predictor, idx)
        } catch (e: Exception) {
            log("getOutput 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorGetFloatData(tensor: Any): FloatArray {
        return try {
            tensorClass.getMethod("getFloatData").invoke(tensor) as FloatArray
        } catch (e: Exception) {
            log("Tensor.getFloatData 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorGetShape(tensor: Any): LongArray {
        return try {
            tensorClass.getMethod("shape").invoke(tensor) as LongArray
        } catch (e: Exception) {
            log("Tensor.shape 反射调用失败: ${e.message}")
            throw e
        }
    }

    private fun predictorClose(predictor: Any) {
        try {
            paddlePredictorClass.getMethod("close").invoke(predictor)
            log("PaddlePredictor 已关闭")
        } catch (e: NoSuchMethodException) {
            log("PaddlePredictor.close 方法不存在")
        } catch (e: Exception) {
            log("PaddlePredictor.close 失败: ${e.message}")
        }
    }

    // ---- 预处理 ----

    private fun preprocessBitmap(bmp: Bitmap): Pair<FloatArray, Int> {
        val srcW = bmp.width.toFloat()
        val srcH = bmp.height.toFloat()
        val ratio = INPUT_SIZE.toFloat() / srcH
        val dstW = (srcW * ratio).toInt().coerceIn(1, INPUT_WIDTH)
        log("预处理: ${bmp.width}x${bmp.height} -> ${dstW}x$INPUT_SIZE (ratio=$ratio)")

        val resized = Bitmap.createScaledBitmap(bmp, dstW, INPUT_SIZE, true)
        val pixels = IntArray(dstW * INPUT_SIZE)
        resized.getPixels(pixels, 0, dstW, 0, 0, dstW, INPUT_SIZE)
        resized.recycle()

        val chStride = INPUT_SIZE * dstW
        val inputData = FloatArray(3 * chStride)
        var nonZero = 0
        var minVal = 1.0f
        var maxVal = -1.0f
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            val bNorm = (b - 0.5f) / 0.5f
            val gNorm = (g - 0.5f) / 0.5f
            val rNorm = (r - 0.5f) / 0.5f
            inputData[i] = bNorm
            inputData[chStride + i] = gNorm
            inputData[2 * chStride + i] = rNorm
            if (r > 0 || g > 0 || b > 0) nonZero++
            if (bNorm < minVal) minVal = bNorm
            if (bNorm > maxVal) maxVal = bNorm
        }
        log("预处理统计: 总像素=${pixels.size}, 非黑像素=$nonZero, B通道范围=[$minVal, $maxVal]")
        return Pair(inputData, dstW)
    }

    private fun ctcDecode(outputData: FloatArray, width: Int, height: Int): String {
        log("CTC 解码: width=$width, height=$height, 数据长度=${outputData.size}")
        val sb = StringBuilder()
        var lastCharIndex = -1
        var maxScoreSum = 0f
        var decodedCount = 0
        var blankCount = 0

        val sampleSize = minOf(10, outputData.size)
        val samples = (0 until sampleSize).map { String.format("%.4f", outputData[it]) }
        log("输出数据前$sampleSize 个值: [${samples.joinToString(", ")}]")

        for (w in 0 until width) {
            var maxScore = -1f
            var maxIdx = -1
            for (h in 0 until height) {
                val score = outputData[w * height + h]
                if (score > maxScore) {
                    maxScore = score
                    maxIdx = h
                }
            }
            if (maxIdx == 0) blankCount++
            if (maxIdx > 0 && maxIdx != lastCharIndex) {
                val charIndex = maxIdx - 1
                if (charIndex < labelList.size) {
                    sb.append(labelList[charIndex])
                    maxScoreSum += maxScore
                    decodedCount++
                } else {
                    log("字符索引越界 idx=$charIndex, labelSize=${labelList.size}")
                }
            }
            lastCharIndex = maxIdx
        }

        val avgConf = if (decodedCount > 0) maxScoreSum / decodedCount else 0f
        val blankRatio = if (width > 0) "%.1f%%".format(blankCount * 100.0f / width) else "N/A"
        log("CTC 解码结果: \"$sb\" (${decodedCount}字, blank=$blankCount/$width=$blankRatio, avgConf=${"%.3f".format(avgConf)})")
        return sb.toString()
    }

    private fun copyModelIfNeeded(ctx: Context): String {
        val modelDir = AppConfig.paddleRecModelDir
        val targetFile = File(modelDir, "ppocrv4_mobile_rec.nb")
        log("检查模型文件: ${targetFile.absolutePath}")
        if (!targetFile.exists()) {
            log("模型文件不存在，从 assets 复制...")
            try {
                val start = System.currentTimeMillis()
                ctx.assets.open("models/ppocrv4_mobile_rec.nb").use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val bytes = input.copyTo(output)
                        log("模型文件复制成功: $bytes bytes (${System.currentTimeMillis() - start}ms)")
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                log("模型文件未找到: assets/models/ppocrv4_mobile_rec.nb")
            } catch (e: Exception) {
                log("模型文件复制失败: ${e.message}")
            }
        } else {
            log("模型文件已存在: ${targetFile.length()} bytes")
        }
        return modelDir.absolutePath
    }

    private fun loadLabelList(ctx: Context): List<String> {
        log("加载 label 表: assets/models/ppocr_keys_v1.txt")
        return try {
            val start = System.currentTimeMillis()
            val lines = ctx.assets.open("models/ppocr_keys_v1.txt")
                .bufferedReader().readLines()
            log("label 表加载完成: ${lines.size} 行 (${System.currentTimeMillis() - start}ms)")
            lines
        } catch (e: java.io.FileNotFoundException) {
            log("label 文件未找到: assets/models/ppocr_keys_v1.txt")
            emptyList()
        } catch (e: Exception) {
            log("label 表加载失败: ${e.message}")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "PaddleLiteRecModel"
        private const val INPUT_SIZE = 48
        private const val INPUT_WIDTH = 960

        private var logFile: File? = null

        private fun log(msg: String) {
            Log.i(TAG, msg)
            try {
                if (logFile == null || logFile!!.length() > 1024 * 1024) {
                    val dir = File(AppConfig.saveDirPath)
                    dir.mkdirs()
                    logFile = File(dir, "model_log.txt")
                    Log.i(TAG, "日志文件: ${logFile!!.absolutePath}")
                }
                logFile!!.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $msg\n")
            } catch (e: Exception) {
                Log.e(TAG, "写日志失败: ${e.message}, fallback...")
                try {
                    val ff = File(AppConfig.saveDirPath, "model_log.txt")
                    ff.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $msg\n")
                    logFile = ff
                } catch (_: Exception) {}
            }
        }
    }
}
