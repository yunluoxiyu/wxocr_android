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
        val normalized = normalizeBase64(base64Str)
        Log.d(TAG, "decodeBase64ToMat: base64 长度=${normalized.length}")
        return try {
            val start = System.currentTimeMillis()
            val bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
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
        val normalized = normalizeBase64(base64Str)
        return try {
            val bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "decodeBase64ToBitmap 失败: ${e.message}")
            null
        }
    }

    /** 归一化 base64：去掉 data 前缀、清空白/换行、空格转 +、还原 URL 编码（%2F→/、%2B→+ 等） */
    internal fun normalizeBase64(base64Str: String): String {
        var raw = if (base64Str.startsWith("data:image")) {
            base64Str.substringAfter("base64,")
        } else base64Str
        raw = raw.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "+")
            .replace(Regex("%([0-9A-Fa-f]{2})")) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
        return raw
    }

    /** 解码为白字黑底软掩码：alpha 灰度保留抗锯齿细笔画，低饱和度排除绿色等彩色标签 */
    fun decodeToWhiteTextMask(
        base64Str: String,
        alphaThreshold: Int = 15,
        satThreshold: Int = 30,
        brightThreshold: Int = 120
    ): Mat? {
        val normalized = normalizeBase64(base64Str)
        return try {
            val bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            val rawMat = MatOfByte(*bytes)
            var src = Imgcodecs.imdecode(rawMat, Imgcodecs.IMREAD_UNCHANGED)
            rawMat.release()
            if (src == null || src.empty()) {
                Log.w(TAG, "decodeToWhiteTextMask: 解码为空")
                return null
            }
            if (src.depth() != CvType.CV_8U) {
                val tmp = Mat()
                src.convertTo(tmp, CvType.CV_8U)
                src.release(); src = tmp
            }

            val chCount = src.channels()
            val ch = mutableListOf<Mat>()
            Core.split(src, ch)
            val b = ch[0]; val g = ch[1]; val r = ch[2]
            val a = if (chCount == 4) ch[3] else null

            // 灰度（保留抗锯齿梯度）
            val bgrTmp = Mat()
            Core.merge(listOf(b, g, r), bgrTmp)
            val gray = Mat()
            Imgproc.cvtColor(bgrTmp, gray, Imgproc.COLOR_BGR2GRAY)
            bgrTmp.release()

            // 饱和度 = max(R,G,B) - min(R,G,B)，白字/灰字 ≈ 0，绿字高
            val maxCh = Mat()
            Core.max(b, g, maxCh); Core.max(maxCh, r, maxCh)
            val minCh = Mat()
            Core.min(b, g, minCh); Core.min(minCh, r, minCh)
            val diff = Mat()
            Core.subtract(maxCh, minCh, diff)
            val lowSat = Mat()
            Imgproc.threshold(diff, lowSat, satThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)

            // alpha 是否退化（全 0 或全 255）：退化则按不透明处理，避免灰度被 alpha 清零导致全黑
            val alphaUniform = if (a != null) {
                val mm = Core.minMaxLoc(a)
                mm.minVal == mm.maxVal
            } else true

            var soft: Mat
            if (a == null || alphaUniform) {
                val bright = Mat()
                Imgproc.threshold(gray, bright, brightThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                val textMask = Mat()
                Core.bitwise_and(bright, lowSat, textMask)
                soft = Mat()
                Core.bitwise_and(gray, textMask, soft)
                bright.release(); textMask.release()
            } else {
                val comp = Mat()
                Core.multiply(gray, a, comp, 1.0 / 255.0)
                val opq = Mat()
                Imgproc.threshold(comp, opq, alphaThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                val textMask = Mat()
                Core.bitwise_and(opq, lowSat, textMask)
                soft = Mat()
                Core.bitwise_and(comp, textMask, soft)
                opq.release(); textMask.release()
                if (Core.countNonZero(soft) == 0) {
                    soft.release()
                    soft = Mat()
                    Core.multiply(gray, a, soft, 1.0 / 255.0)
                }
            }

            val out = Mat()
            Imgproc.cvtColor(soft, out, Imgproc.COLOR_GRAY2BGR)
            val nz = Core.countNonZero(soft)
            ch.forEach { it.release() }
            maxCh.release(); minCh.release(); diff.release(); lowSat.release(); gray.release()
            soft.release(); src.release()
            Log.d(TAG, "decodeToWhiteTextMask: ${out.cols()}x${out.rows()} ch=$chCount alphaUniform=$alphaUniform soft_nz=$nz")
            out
        } catch (e: Exception) {
            Log.w(TAG, "decodeToWhiteTextMask 失败: ${e.message}")
            null
        }
    }

    fun matToBitmap(mat: Mat): Bitmap {
        Log.d(TAG, "matToBitmap: ${mat.cols()}x${mat.rows()}, type=${mat.type()}")
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    /** 按目标高度等比放大，用于小字放大后再识别，减少笔画丢失 */
    fun upscaleToHeight(src: Mat, targetHeight: Int): Mat {
        val h = src.rows()
        val w = src.cols()
        val scale = targetHeight.toDouble() / h.coerceAtLeast(1)
        val dst = Mat()
        Imgproc.resize(
            src, dst,
            Size((w * scale).toDouble().coerceAtLeast(1.0), targetHeight.toDouble()),
            0.0, 0.0, Imgproc.INTER_CUBIC
        )
        return dst
    }

    /** 裁剪到非背景像素的紧致包围盒（模拟检测模型的裁剪，供纯 rec 识别使用） */
    fun trimToContent(src: Mat, bgThreshold: Int = 10): Mat {
        if (src.empty()) return Mat()
        val bbox = findContentBbox(src, bgThreshold)
        if (bbox == null) return src.clone()
        val x1 = (bbox.x - 2).coerceAtLeast(0)
        val y1 = (bbox.y - 2).coerceAtLeast(0)
        val x2 = (bbox.x + bbox.width + 2).coerceAtMost(src.cols())
        val y2 = (bbox.y + bbox.height + 2).coerceAtMost(src.rows())
        if (x2 <= x1 || y2 <= y1) return src.clone()
        val roi = Mat(src, Rect(x1, y1, x2 - x1, y2 - y1))
        val out = roi.clone()
        roi.release()
        return out
    }

    /** 返回非背景像素的紧致包围盒，无内容返回 null */
    fun findContentBbox(src: Mat, bgThreshold: Int = 10): Rect? {
        if (src.empty()) return null
        val gray = Mat()
        if (src.channels() == 1) src.copyTo(gray) else Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.threshold(gray, binary, bgThreshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val pts = Mat()
        Core.findNonZero(binary, pts)
        binary.release(); gray.release()
        if (pts.empty()) {
            pts.release()
            return null
        }
        val rect = Imgproc.boundingRect(pts)
        pts.release()
        return rect
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

    fun preprocessAntiCheatWhite(
        roi: Mat,
        targetWidth: Int = AppConfig.OCR_RESIZE_TARGET_WIDTH,
        hsvLower: IntArray = AppConfig.WHITE_HSV_LOWER,
        hsvUpper: IntArray = AppConfig.WHITE_HSV_UPPER
    ): Mat {
        Log.d(TAG, "preprocessAntiCheatWhite: ${roi.cols()}x${roi.rows()}")
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(
            hsv,
            Scalar(
                hsvLower[0].toDouble(),
                hsvLower[1].toDouble(),
                hsvLower[2].toDouble()
            ),
            Scalar(
                hsvUpper[0].toDouble(),
                hsvUpper[1].toDouble(),
                hsvUpper[2].toDouble()
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

    /** 白字软掩码：保留灰度渐变（不硬二值化），用于小字识别，避免抗锯齿笔画断裂 */
    fun preprocessAntiCheatWhiteSoft(
        roi: Mat,
        hsvLower: IntArray = AppConfig.WHITE_HSV_LOWER,
        hsvUpper: IntArray = AppConfig.WHITE_HSV_UPPER
    ): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_BGR2HSV)
        val mask = Mat()
        Core.inRange(
            hsv,
            Scalar(hsvLower[0].toDouble(), hsvLower[1].toDouble(), hsvLower[2].toDouble()),
            Scalar(hsvUpper[0].toDouble(), hsvUpper[1].toDouble(), hsvUpper[2].toDouble()),
            mask
        )
        val whiteOnly = Mat()
        Core.bitwise_and(roi, roi, whiteOnly, mask)
        val gray = Mat()
        Imgproc.cvtColor(whiteOnly, gray, Imgproc.COLOR_BGR2GRAY)
        val result = Mat()
        Imgproc.cvtColor(gray, result, Imgproc.COLOR_GRAY2BGR)
        hsv.release(); mask.release(); whiteOnly.release(); gray.release()
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
            val raw = normalizeBase64(base64Str)
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
        val rowSums = IntArray(h)
        if (w > 0) {
            val rowBuf = ByteArray(w)
            for (y in 0 until h) {
                var cnt = 0
                binary.row(y).get(0, 0, rowBuf)
                for (x in 0 until w) {
                    if (rowBuf[x].toInt() and 0xFF > 0) cnt++
                }
                rowSums[y] = cnt
            }
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
