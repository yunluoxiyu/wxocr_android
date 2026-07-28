package com.yunluo.wxocr.ocr

import android.graphics.Bitmap
import android.util.Log
import com.yunluo.wxocr.config.AppConfig
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

object ImagePreprocessor {

    private const val TAG = "ImagePreprocessor"

    fun decodeBase64ToMat(base64Str: String): Mat? {
        val raw = if (base64Str.startsWith("data:image")) {
            base64Str.substringAfter("base64,")
        } else base64Str
        Log.d(TAG, "decodeBase64ToMat: base64 长度=${raw.length}")
        return try {
            val start = System.currentTimeMillis()
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            Log.d(TAG, "Base64 解码: ${bytes.size} bytes (${System.currentTimeMillis() - start}ms)")
            val mat = Mat()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Utils.bitmapToMat(bmp, mat)
            bmp.recycle()
            Log.d(TAG, "Bitmap -> Mat: ${mat.cols()}x${mat.rows()}")
            mat
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Base64 解码失败: 非法 base64 字符串", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "decodeBase64ToMat 失败: ${e::class.simpleName} - ${e.message}", e)
            null
        }
    }

    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        val raw = if (base64Str.startsWith("data:image")) {
            base64Str.substringAfter("base64,")
        } else base64Str
        return try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "decodeBase64ToBitmap 失败: ${e.message}")
            null
        }
    }

    fun matToBitmap(mat: Mat): Bitmap {
        Log.d(TAG, "matToBitmap: ${mat.cols()}x${mat.rows()}, type=${mat.type()}")
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    fun crop(mat: Mat, x1: Int, y1: Int, x2: Int, y2: Int): Mat {
        val h = mat.rows()
        val w = mat.cols()
        val x1c = maxOf(0, x1)
        val y1c = maxOf(0, y1)
        val x2c = minOf(w, x2)
        val y2c = minOf(h, y2)
        Log.d(TAG, "crop: 原始=${w}x${h}, 请求=($x1,$y1)->($x2,$y2), 裁剪后=($x1c,$y1c)->($x2c,$y2c)")
        if (x1c >= x2c || y1c >= y2c) {
            Log.w(TAG, "crop: 裁剪区域无效, 返回 32x32 空图")
            return Mat.zeros(32, 32, CvType.CV_8UC3)
        }
        val roi = Mat(mat, Rect(x1c, y1c, x2c - x1c, y2c - y1c))
        Log.d(TAG, "crop 结果: ${roi.cols()}x${roi.rows()}")
        return roi
    }



    fun preprocessYellow(roi: Mat): Mat {
        Log.d(TAG, "preprocessYellow: ${roi.cols()}x${roi.rows()}")
        val start = System.currentTimeMillis()

        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val yellowLower = Scalar(
            AppConfig.YELLOW_HSV_LOWER[0].toDouble(),
            AppConfig.YELLOW_HSV_LOWER[1].toDouble(),
            AppConfig.YELLOW_HSV_LOWER[2].toDouble()
        )
        val yellowUpper = Scalar(
            AppConfig.YELLOW_HSV_UPPER[0].toDouble(),
            AppConfig.YELLOW_HSV_UPPER[1].toDouble(),
            AppConfig.YELLOW_HSV_UPPER[2].toDouble()
        )
        val mask = Mat()
        Core.inRange(hsv, yellowLower, yellowUpper, mask)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val yellowOnly = Mat()
        Core.bitwise_and(roi, roi, yellowOnly, mask)

        val gray = Mat()
        Imgproc.cvtColor(yellowOnly, gray, Imgproc.COLOR_BGR2GRAY)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val result = Mat()
        Imgproc.cvtColor(enhanced, result, Imgproc.COLOR_GRAY2BGR)

        hsv.release(); mask.release(); yellowOnly.release()
        gray.release(); enhanced.release()

        Log.d(TAG, "preprocessYellow 完成 (${System.currentTimeMillis() - start}ms)")
        return result
    }

    fun preprocessWhite(roi: Mat): Mat {
        Log.d(TAG, "preprocessWhite: ${roi.cols()}x${roi.rows()}")
        val start = System.currentTimeMillis()

        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val whiteLower = Scalar(
            AppConfig.WHITE_OPTION_HSV_LOWER[0].toDouble(),
            AppConfig.WHITE_OPTION_HSV_LOWER[1].toDouble(),
            AppConfig.WHITE_OPTION_HSV_LOWER[2].toDouble()
        )
        val whiteUpper = Scalar(
            AppConfig.WHITE_OPTION_HSV_UPPER[0].toDouble(),
            AppConfig.WHITE_OPTION_HSV_UPPER[1].toDouble(),
            AppConfig.WHITE_OPTION_HSV_UPPER[2].toDouble()
        )
        val mask = Mat()
        Core.inRange(hsv, whiteLower, whiteUpper, mask)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val whiteOnly = Mat()
        Core.bitwise_and(roi, roi, whiteOnly, mask)

        val gray = Mat()
        Imgproc.cvtColor(whiteOnly, gray, Imgproc.COLOR_BGR2GRAY)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val result = Mat()
        Imgproc.cvtColor(enhanced, result, Imgproc.COLOR_GRAY2BGR)

        hsv.release(); mask.release(); whiteOnly.release()
        gray.release(); enhanced.release()

        Log.d(TAG, "preprocessWhite 完成 (${System.currentTimeMillis() - start}ms)")
        return result
    }

    fun preprocessWhiteForRapidOcr(roi: Mat, targetWidth: Int = AppConfig.OCR_RESIZE_TARGET_WIDTH): Mat {
        Log.d(TAG, "preprocessWhiteForRapidOcr: ${roi.cols()}x${roi.rows()}")
        val start = System.currentTimeMillis()

        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY)

        val enhanced = Mat()
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(gray, enhanced)

        val denoised = Mat()
        Imgproc.medianBlur(enhanced, denoised, 3)

        val binary = Mat()
        Imgproc.threshold(denoised, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
        kernel.release()

        val result = Mat()
        Imgproc.cvtColor(binary, result, Imgproc.COLOR_GRAY2BGR)

        val w = result.cols()
        if (w > targetWidth) {
            val scale = targetWidth.toDouble() / w
            val dst = Mat()
            Imgproc.resize(result, dst, Size(), scale, scale)
            result.release()
            gray.release(); enhanced.release(); denoised.release(); binary.release()
            Log.d(TAG, "缩放: ${w} -> $targetWidth (${System.currentTimeMillis() - start}ms)")
            return dst
        }

        gray.release(); enhanced.release(); denoised.release(); binary.release()
        Log.d(TAG, "preprocessWhiteForRapidOcr 完成 (${System.currentTimeMillis() - start}ms, 无需缩放)")
        return result
    }

    fun preprocessAntiCheatWhite(roi: Mat, targetWidth: Int = AppConfig.OCR_RESIZE_TARGET_WIDTH): Mat {
        Log.d(TAG, "preprocessAntiCheatWhite: ${roi.cols()}x${roi.rows()}")
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(
            hsv,
            Scalar(
                AppConfig.WHITE_HSV_LOWER[0].toDouble(),
                AppConfig.WHITE_HSV_LOWER[1].toDouble(),
                AppConfig.WHITE_HSV_LOWER[2].toDouble()
            ),
            Scalar(
                AppConfig.WHITE_HSV_UPPER[0].toDouble(),
                AppConfig.WHITE_HSV_UPPER[1].toDouble(),
                AppConfig.WHITE_HSV_UPPER[2].toDouble()
            ),
            mask
        )

        val whiteOnly = Mat()
        Core.bitwise_and(roi, roi, whiteOnly, mask)
        val gray = Mat()
        Imgproc.cvtColor(whiteOnly, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.threshold(gray, gray, AppConfig.WHITE_BINARY_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)

        val result = Mat()
        Imgproc.cvtColor(gray, result, Imgproc.COLOR_GRAY2BGR)
        hsv.release(); mask.release(); whiteOnly.release(); gray.release()

        val w = result.cols()
        if (w > targetWidth) {
            val scale = targetWidth.toDouble() / w
            val dst = Mat()
            Imgproc.resize(result, dst, Size(targetWidth.toDouble(), (result.rows() * scale).coerceAtLeast(1.0)))
            result.release()
            return dst
        }
        return result
    }

    fun templateMatch(source: Mat, template: Mat, threshold: Double = AppConfig.TEMPLATE_MATCH_THRESHOLD): TemplateMatchResult? {
        Log.d(TAG, "templateMatch: source=${source.cols()}x${source.rows()} type=${source.type()}, template=${template.cols()}x${template.rows()} type=${template.type()}, threshold=$threshold")
        if (source.empty() || template.empty()) return null
        if (source.cols() < template.cols() || source.rows() < template.rows()) {
            Log.w(TAG, "templateMatch: 模板大于原图，跳过")
            return null
        }
        val start = System.currentTimeMillis()
        val src = Mat()
        val tpl = Mat()
        normalizeForTemplateMatch(source, src)
        normalizeForTemplateMatch(template, tpl)
        val result = Mat()
        Imgproc.matchTemplate(src, tpl, result, AppConfig.TEMPLATE_MATCH_METHOD)
        val mmr = Core.minMaxLoc(result)
        val maxVal = mmr.maxVal
        result.release()
        src.release(); tpl.release()
        if (maxVal < threshold) {
            Log.d(TAG, "templateMatch: 未匹配到, 最高相似度=${"%.4f".format(maxVal)} (${System.currentTimeMillis() - start}ms)")
            return null
        }
        val maxLoc = mmr.maxLoc
        Log.d(TAG, "templateMatch: 匹配成功, 位置=(${maxLoc.x},${maxLoc.y}), 相似度=${"%.4f".format(maxVal)} (${System.currentTimeMillis() - start}ms)")
        return TemplateMatchResult(
            x = maxLoc.x.toInt(),
            y = maxLoc.y.toInt(),
            w = template.cols(),
            h = template.rows(),
            confidence = maxVal
        )
    }

    private fun normalizeForTemplateMatch(input: Mat, output: Mat) {
        val gray = Mat()
        when (input.channels()) {
            1 -> input.copyTo(gray)
            3 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
            4 -> Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGRA2GRAY)
            else -> input.copyTo(gray)
        }
        if (gray.depth() == CvType.CV_8U) {
            gray.copyTo(output)
        } else {
            gray.convertTo(output, CvType.CV_8U)
        }
        gray.release()
    }

    fun saveBase64Image(base64Str: String, prefix: String, dir: File = AppConfig.debugDir) {
        try {
            dir.mkdirs()
            val raw = if (base64Str.startsWith("data:image")) {
                base64Str.substringAfter("base64,")
            } else base64Str
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val dims = if (bmp != null) "${bmp.width}x${bmp.height}" else "unknown"
            bmp?.recycle()
            val filename = "${prefix}_${dims}_${System.currentTimeMillis()}.png"
            val file = File(dir, filename)
            file.writeBytes(bytes)
            Log.i(TAG, "请求原图已保存: $file (${bytes.size} bytes, $dims)")
        } catch (e: Exception) {
            Log.w(TAG, "保存请求原图失败: ${e.message}", e)
        }
    }

    fun saveDebugCrop(mat: Mat, prefix: String, dir: File = AppConfig.debugDir) {
        try {
            dir.mkdirs()
            val dims = "${mat.cols()}x${mat.rows()}"
            val filename = "${prefix}_${dims}_${System.currentTimeMillis()}.png"
            val path = File(dir, filename).absolutePath
            Imgcodecs.imwrite(path, mat)
            Log.i(TAG, "调试截图已保存: $path ($dims)")
        } catch (e: Exception) {
            Log.w(TAG, "保存调试截图失败: ${e.message}", e)
        }
    }

    /** 用水平投影法将多行文本分割为单个文字行，返回行小图列表 */
    fun splitTextLines(mat: Mat, minHeight: Int = 15): List<Mat> {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val h = binary.rows()
        val w = binary.cols()
        val rowSums = IntArray(h) { y ->
            var cnt = 0
            for (x in 0 until w) {
                if (binary.get(y, x)[0] > 0) cnt++
            }
            cnt
        }
        val smoothed = IntArray(h) { y ->
            var sum = 0
            var count = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy in 0 until h) {
                    sum += rowSums[yy]
                    count++
                }
            }
            if (count > 0) sum / count else rowSums[y]
        }
        val maxRow = smoothed.maxOrNull() ?: 0
        val denseThreshold = maxOf((maxRow * 0.10).toInt(), (w * 0.006).toInt(), 2)
        val inText = BooleanArray(h) { smoothed[it] >= denseThreshold }
        val expanded = inText.copyOf()
        for (y in 1 until h) {
            if (inText[y]) {
                for (gap in 1..2) {
                    if (y - gap >= 0 && !expanded[y - gap]) expanded[y - gap] = true
                }
                for (gap in 1..2) {
                    if (y + gap < h && !expanded[y + gap]) expanded[y + gap] = true
                }
            }
        }

        fun addLine(lines: MutableList<Mat>, y1: Int, y2: Int) {
            val top = (y1 - 3).coerceAtLeast(0)
            val bottom = (y2 + 3).coerceAtMost(h)
            if (bottom - top >= minHeight) {
                val sub = Mat(mat, Rect(0, top, w, bottom - top))
                lines.add(sub.clone())
                sub.release()
            }
        }

        val lines = mutableListOf<Mat>()
        var start = -1
        for (y in 0 until h) {
            if (expanded[y] && start < 0) {
                start = y
            } else if (!expanded[y] && start >= 0) {
                addLine(lines, start, y)
                start = -1
            }
        }
        if (start >= 0) {
            addLine(lines, start, h)
        }

        if (lines.size == 1 && h >= minHeight * 2 + 8) {
            val from = (h * 0.35).toInt()
            val to = (h * 0.65).toInt().coerceAtLeast(from + 1)
            var splitY = h / 2
            var minVal = Int.MAX_VALUE
            for (y in from until to) {
                if (smoothed[y] < minVal) {
                    minVal = smoothed[y]
                    splitY = y
                }
            }
            if (minVal <= maxOf(denseThreshold, (maxRow * 0.25).toInt())) {
                lines.forEach { it.release() }
                lines.clear()
                addLine(lines, 0, splitY)
                addLine(lines, splitY, h)
            }
        }
        gray.release(); binary.release()
        Log.d(TAG, "splitTextLines: ${mat.rows()}x${mat.cols()} maxRow=$maxRow threshold=$denseThreshold -> ${lines.size} 行 [${lines.joinToString { "${it.rows()}x${it.cols()}" }}]")
        return lines
    }

    /** 在全图上画出所有 5 区域裁剪框 + OCR 识别文本，方便调试 */
    fun saveAnnotatedDebug(full: Mat, ocrTexts: Map<String, String>, index: Int = 0, dir: File = AppConfig.debugDir) {
        if (!AppConfig.saveDebug) return
        try {
            dir.mkdirs()
            val annotated = full.clone()
            val dims = "${full.cols()}x${full.rows()}"
            // 各区域不同颜色 (BGR)
            val colors = mapOf(
                "question" to Scalar(0.0, 0.0, 255.0),
                "option_a" to Scalar(255.0, 0.0, 0.0),
                "option_b" to Scalar(255.0, 255.0, 0.0),
                "option_c" to Scalar(255.0, 0.0, 255.0),
                "option_d" to Scalar(0.0, 255.0, 255.0),
            )
            for ((key, area) in AppConfig.CROP_AREA) {
                val (x1, y1, x2, y2) = area
                val color = colors[key] ?: Scalar(0.0, 255.0, 0.0)
                Imgproc.rectangle(annotated, Point(x1.toDouble(), y1.toDouble()),
                    Point(x2.toDouble(), y2.toDouble()), color, 2)
                val letter = key.split("_").getOrElse(1) { "" }.uppercase()
                val label = if (key == "question") "题目" else letter
                val ocrText = if (key == "question") {
                    ocrTexts["question"] ?: ""
                } else {
                    ocrTexts[letter] ?: ""
                }
                val display = if (ocrText.length > 20) "${ocrText.take(18)}.." else ocrText
                val annotation = "$label: $display"
                Imgproc.putText(annotated, annotation, Point((x1 + 4).toDouble(), (y1 + 20).toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, color, 1)
            }
            // 题号
            if (index > 0) {
                val (ix, iy, iw, ih) = AppConfig.INDEX_CROP_AREA
                Imgproc.rectangle(annotated, Point(ix.toDouble(), iy.toDouble()),
                    Point((ix + iw).toDouble(), (iy + ih).toDouble()), Scalar(0.0, 255.0, 0.0), 2)
                Imgproc.putText(annotated, "题号: $index", Point(ix.toDouble(), (iy - 6).toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, Scalar(0.0, 255.0, 0.0), 1)
            }
            val filename = "annotated_${index}__${dims}_${System.currentTimeMillis()}.png"
            val path = File(dir, filename).absolutePath
            Imgcodecs.imwrite(path, annotated)
            Log.i(TAG, "标注全图已保存: $path")
            annotated.release()
        } catch (e: Exception) {
            Log.w(TAG, "保存标注全图失败: ${e.message}", e)
        }
    }

    /** 在全图上画出裁剪框并保存，用于确认裁剪区域是否对准 */
    fun saveAnnotatedFull(full: Mat, x: Int, y: Int, w: Int, h: Int, label: String, dir: File = AppConfig.debugDir) {
        try {
            dir.mkdirs()
            val annotated = full.clone()
            // 画红色矩形框
            Imgproc.rectangle(annotated, Point(x.toDouble(), y.toDouble()),
                Point((x + w).toDouble(), (y + h).toDouble()), Scalar(0.0, 0.0, 255.0), 2)
            // 标注文字
            val text = "$label ${w}x${h} @($x,$y)"
            Imgproc.putText(annotated, text, Point((x + 2).toDouble(), (y - 4).toDouble()),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, Scalar(0.0, 0.0, 255.0), 1)
            val dims = "${full.cols()}x${full.rows()}"
            val filename = "annotated_${label}_${dims}_${System.currentTimeMillis()}.png"
            val path = File(dir, filename).absolutePath
            Imgcodecs.imwrite(path, annotated)
            Log.i(TAG, "标注全图已保存: $path")
            annotated.release()
        } catch (e: Exception) {
            Log.w(TAG, "保存标注全图失败: ${e.message}", e)
        }
    }

    data class TemplateMatchResult(
        val x: Int, val y: Int, val w: Int, val h: Int, val confidence: Double
    )
}
