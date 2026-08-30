package com.yunluo.wxocr.ocr

import android.util.Log
import com.yunluo.wxocr.config.AppConfig
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object OcrCache {
    data class Entry(val value: Int, val timestamp: Long)
    val indexCache = ConcurrentHashMap<String, Entry>()
}

class PaddleOcrEngine(private val device: String = "cpu") {

    private val recModel: PaddleLiteRecModel
    private val detModel: PaddleLiteDetModel by lazy { PaddleLiteDetModel(device) }
    private val inferenceLock = Any()

    private var logFile: File? = null
    private fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            if (logFile == null || logFile!!.length() > 1024 * 1024) {
                val dir = File(AppConfig.saveDirPath)
                dir.mkdirs()
                logFile = File(dir, "engine_log.txt")
            }
            logFile!!.appendText("[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $msg\n")
        } catch (_: Exception) {}
    }

    init {
        log("=== PaddleOcrEngine init start, device=$device")
        val start = System.currentTimeMillis()
        recModel = PaddleLiteRecModel(device)
        log("=== PaddleOcrEngine init 完成 (${System.currentTimeMillis() - start}ms)")
    }

    fun release() {
        recModel.release()
    }

    private fun predictAndRecycle(mat: Mat): String {
        val bmp = ImagePreprocessor.matToBitmap(mat)
        val text = synchronized(inferenceLock) { recModel.predict(bmp) }
        bmp.recycle()
        return text
    }

    private fun decodeToMat(base64Str: String): Mat? {
        val normalized = ImagePreprocessor.normalizeBase64(base64Str)
        return try {
            val bytes = android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
            val rawMat = MatOfByte(*bytes)
            val mat = Imgcodecs.imdecode(rawMat, Imgcodecs.IMREAD_COLOR)
            rawMat.release()
            if (mat == null || mat.empty()) {
                log("imdecode 返回空")
                return null
            }
            log("imdecode: ${mat.cols()}x${mat.rows()}")
            mat
        } catch (e: Exception) {
            log("decodeToMat 异常: ${e.message}")
            null
        }
    }

    // ==================== 题号 OCR ====================

    fun ocrIndexRegion(base64Str: String): Int {
        val rawKey = base64Str.hashCode().toString()
        val cached = OcrCache.indexCache[rawKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < (AppConfig.INDEX_CACHE_TTL_SECONDS * 1000).toLong()) {
            log("ocrIndexRegion: 命中缓存 index=${cached.value}")
            return cached.value
        }

        val mat = decodeToMat(base64Str) ?: return 0
        try {
            val (x, y, w, h) = AppConfig.INDEX_CROP_AREA
            val roi = ImagePreprocessor.crop(mat, x, y, x + w, y + h)
            if (roi.empty()) return 0
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(roi, "index_raw_fb")
            val processed = ImagePreprocessor.preprocessYellow(roi)
            val bmp = ImagePreprocessor.matToBitmap(processed)
            val text = recModel.predict(bmp)
            bmp.recycle()
            val index = extractQuestionIndex(text)
            log("ocrIndexRegion (fixed): \"$text\" -> index=$index")
            com.yunluo.wxocr.server.ServiceState.postLog("题号OCR: \"$text\" → index=$index")
            if (index > 0) OcrCache.indexCache[rawKey] = OcrCache.Entry(index, System.currentTimeMillis())
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(processed, "index_proc_${if (index > 0) index else "fail"}")
            roi.release(); processed.release()
            return index
        } finally { mat.release() }
    }

    // ==================== 全区域 OCR（检测+识别） ====================

    fun ocrAllRegions(base64Str: String): OcrResult {
        val mat = decodeToMat(base64Str) ?: run {
            Log.w(TAG, "ocrAllRegions: 图片解码失败")
            return OcrResult(index = 0)
        }
        try {
            return ocrAllRegionsFixed(mat)
        } finally { mat.release() }
    }

    // ==================== 检测结果分类 ====================

    private fun classifyBoxes(boxes: List<PaddleLiteDetModel.DetBox>): Map<String, List<PaddleLiteDetModel.DetBox>> {
        val result = mutableMapOf<String, MutableList<PaddleLiteDetModel.DetBox>>()
        for (box in boxes) {
            val label = classifyByPosition(box) ?: continue
            result.getOrPut(label) { mutableListOf() }.add(box)
        }
        return result
    }

    private fun classifyByPosition(box: PaddleLiteDetModel.DetBox): String? {
        val cx = box.centerX
        val cy = box.centerY
        for ((name, area) in AppConfig.CROP_AREA) {
            val (x1, y1, x2, y2) = area
            if (cx in x1..x2 && cy in y1..y2) return name
        }
        val (ix, iy, iw, ih) = AppConfig.INDEX_CROP_AREA
        if (cx in ix..(ix + iw) && cy in iy..(iy + ih)) return "index"
        return null
    }

    // ==================== 检测框 OCR ====================

    private fun ocrIndexFromBox(mat: Mat, box: PaddleLiteDetModel.DetBox): Int {
        val roi = cropBox(mat, box)
        val processed = ImagePreprocessor.preprocessYellow(roi)
        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(processed, "index_det_ocr")
        val bmp = ImagePreprocessor.matToBitmap(processed)
        val text = recModel.predict(bmp)
        bmp.recycle()
        val index = extractQuestionIndex(text)
        log("题号(det): \"$text\" -> $index")
        roi.release(); processed.release()
        return index
    }

    private fun ocrBox(mat: Mat, box: PaddleLiteDetModel.DetBox, isYellow: Boolean): String {
        val roi = cropBox(mat, box)
        val processed = if (isYellow) ImagePreprocessor.preprocessYellow(roi)
        else ImagePreprocessor.preprocessWhite(roi)
        if (AppConfig.saveDebug) {
            val prefix = if (isYellow) "det_yellow" else "det_white"
            ImagePreprocessor.saveDebugCrop(processed, "${prefix}_${box.centerX}_${box.centerY}")
        }
        val bmp = ImagePreprocessor.matToBitmap(processed)
        val text = recModel.predict(bmp)
        bmp.recycle()
        roi.release(); processed.release()
        return text
    }

    private fun cropBox(mat: Mat, box: PaddleLiteDetModel.DetBox): Mat {
        val xs = intArrayOf(box.x1, box.x2, box.x3, box.x4)
        val ys = intArrayOf(box.y1, box.y2, box.y3, box.y4)
        val minX = xs.min().coerceIn(0, mat.cols() - 1)
        val maxX = xs.max().coerceIn(0, mat.cols() - 1)
        val minY = ys.min().coerceIn(0, mat.rows() - 1)
        val maxY = ys.max().coerceIn(0, mat.rows() - 1)
        return Mat(mat, Rect(minX, minY, maxX - minX, maxY - minY))
    }

    // ==================== 固定坐标 fallback ====================

    fun ocrAllRegionsFixed(mat: Mat): OcrResult {
        Log.d(TAG, "使用固定坐标 OCR")
        val crops = mutableListOf<Mat>()
        val keys = mutableListOf<String>()
        for (key in AppConfig.CROP_AREA_ORDER) {
            val (x1, y1, x2, y2) = AppConfig.CROP_AREA[key]!!
            val roi = ImagePreprocessor.crop(mat, x1, y1, x2, y2)
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(roi, "raw_${key}")
            val processed = if (key == "question") ImagePreprocessor.preprocessYellow(roi)
            else ImagePreprocessor.preprocessWhite(roi)
            roi.release()
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(processed, "proc_${key}")
            if (key == "question" && processed.rows() >= 60) {
                val lines = ImagePreprocessor.splitTextLines(processed, minHeight = 12)
                if (lines.size in 2..3) {
                    lines.forEachIndexed { idx, line ->
                        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(line, "proc_${key}_line_${idx + 1}")
                        crops.add(line)
                        keys.add(key)
                    }
                    processed.release()
                } else {
                    lines.forEach { it.release() }
                    crops.add(processed)
                    keys.add(key)
                }
            } else {
                crops.add(processed)
                keys.add(key)
            }
        }
        val bmps = crops.map { ImagePreprocessor.matToBitmap(it) }
        val texts = recModel.predictBatch(bmps)
        crops.forEach { it.release() }
        val questionCropCount = keys.count { it == "question" }

        val result = mutableMapOf<String, String>()
        val questionParts = mutableListOf<String>()
        var rawQuestionIndex = 0

        for (i in keys.indices) {
            val text = texts.getOrElse(i) { "" }
            when (keys[i]) {
                "question" -> {
                    rawQuestionIndex = extractQuestionIndex(text)
                    var cleaned = cleanQuestionText(text)
                    if (questionCropCount == 1 && cleaned.count { it in '\u4e00'..'\u9fff' } < 4) {
                        val (x1, y1, x2, y2) = AppConfig.CROP_AREA[keys[i]]!!
                        val fbRoi = ImagePreprocessor.crop(mat, x1, y1, x2, y2)
                        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(fbRoi, "fallback_${keys[i]}")
                        val fbText = predictAndRecycle(fbRoi)
                        if (fbText.isNotBlank()) {
                            cleaned = cleanQuestionText(fbText)
                            rawQuestionIndex = extractQuestionIndex(fbText).takeIf { it > 0 } ?: rawQuestionIndex
                        }
                        fbRoi.release()
                    }
                    if (cleaned.isNotBlank()) questionParts.add(cleaned)
                }
                else -> {
                    val letter = keys[i].split("_")[1].uppercase()
                    var cleaned = cleanOptionText(text)
                    val cn = cleaned.count { it in '\u4e00'..'\u9fff' }
                    if (cn < 1) {
                        val (x1, y1, x2, y2) = AppConfig.CROP_AREA[keys[i]]!!
                        val fbRoi = ImagePreprocessor.crop(mat, x1, y1, x2, y2)
                        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(fbRoi, "fallback_${keys[i]}")
                        val fbText = predictAndRecycle(fbRoi)
                        if (fbText.isNotBlank()) {
                            cleaned = cleanOptionText(fbText)
                        }
                        fbRoi.release()
                    }
                    result[letter] = cleaned
                }
            }
        }

        if (questionParts.isNotEmpty()) {
            result["question"] = questionParts.joinToString("")
        }

        Log.i(TAG, "固定坐标 OCR 完成: index=$rawQuestionIndex")
        if (AppConfig.saveDebug) ImagePreprocessor.saveAnnotatedDebug(mat, result.toMap(), rawQuestionIndex)
        return OcrResult(rawResults = result.toMap(), index = rawQuestionIndex)
    }

    // ==================== 反作弊 ====================

    fun findAntiCheatButton(base64Str: String): ImagePreprocessor.TemplateMatchResult? {
        val mat = decodeToMat(base64Str) ?: return null
        try {
            val tplFile = AppConfig.templateFile
            if (!tplFile.exists()) return null
            val tplMat = Imgcodecs.imread(tplFile.absolutePath, Imgcodecs.IMREAD_COLOR)
            if (tplMat.empty()) {
                tplMat.release()
                return null
            }
            Log.d(TAG, "findAntiCheatButton: source=${mat.cols()}x${mat.rows()}, template=${tplMat.cols()}x${tplMat.rows()}")
            val scales = doubleArrayOf(0.75, 0.85, 0.95, 1.0, 1.05, 1.15, 1.25)
            var best: ImagePreprocessor.TemplateMatchResult? = null
            var bestScore = 0.0
            for (scale in scales) {
                val scaledTpl = if (scale == 1.0) {
                    tplMat.clone()
                } else {
                    Mat().also {
                        Imgproc.resize(tplMat, it, Size(), scale, scale)
                    }
                }
                try {
                    val result = ImagePreprocessor.templateMatch(mat, scaledTpl, 0.70)
                    if (result != null && result.confidence > bestScore) {
                        best = result
                        bestScore = result.confidence
                    }
                } finally {
                    scaledTpl.release()
                }
            }
            tplMat.release()
            return best
        } finally { mat.release() }
    }

    fun ocrRoiRegion(base64Str: String, x: Int, y: Int, w: Int, h: Int): List<OcrDetection> {
        val mat = decodeToMat(base64Str) ?: return emptyList()
        try {
            val roi = ImagePreprocessor.crop(mat, x, y, x + w, y + h)
            if (roi.empty()) return emptyList()
            val processed = ImagePreprocessor.preprocessAntiCheatWhite(roi)
            val text = cleanAntiCheatText(predictAndRecycle(processed))
            roi.release(); processed.release()
            if (text.isBlank()) return emptyList()
            return listOf(OcrDetection(text = text, centerX = x + w / 2, centerY = y + h / 2, confidence = 0.8f))
        } finally { mat.release() }
    }

    fun ocrRoiDetections(base64Str: String, x: Int, y: Int, w: Int, h: Int, windowType: String = "option"): List<OcrDetection> {
        val mat = decodeToMat(base64Str) ?: return emptyList()
        try {
            return ocrRoiDetections(mat, x, y, w, h, windowType)
        } finally { mat.release() }
    }

    fun ocrQuestionFallback(base64Str: String, x: Int, y: Int, w: Int, h: Int): String {
        val mat = decodeToMat(base64Str) ?: return ""
        try {
            val roi = ImagePreprocessor.crop(mat, x, y, x + w, y + h)
            if (roi.empty()) return ""
            val processed = ImagePreprocessor.preprocessAntiCheatWhite(
                roi, targetWidth = AppConfig.ANTI_CHEAT_DET_WIDTH,
                hsvLower = AppConfig.ANTI_QUESTION_HSV_LOWER,
                hsvUpper = AppConfig.ANTI_QUESTION_HSV_UPPER
            )
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(processed, "anti_q_fallback")
            val text = cleanAntiCheatText(predictAndRecycle(processed))
            roi.release(); processed.release()
            return text
        } finally { mat.release() }
    }

    /** 识别指定区域截图的白字文本（支持透明背景）：投影切行 + 裁剪紧致框 + 每行识别，过高行递归再切分 */
    fun ocrTextRegion(base64Str: String): String {
        val mask = ImagePreprocessor.decodeToWhiteTextMask(base64Str) ?: return ""
        if (mask.empty()) return ""
        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(mask, "ocr_text_mask")

        val texts = mutableListOf<String>()
        recTextSegments(mask, texts)

        val joined = texts.filter { it.isNotBlank() }.joinToString(",")
        log("ocrTextRegion: ${mask.cols()}x${mask.rows()} -> \"$joined\"")
        return joined
    }

    /** 递归识别文本段：过高（可能合并多行）再次切分，逐行裁剪紧致框后识别 */
    private fun recTextSegments(seg: Mat, texts: MutableList<String>) {
        if (seg.rows() <= 52) {
            val trimmed = ImagePreprocessor.trimToContent(seg)
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(trimmed, "ocr_text_line_${texts.size + 1}")
            texts.add(cleanOcrLine(predictAndRecycle(trimmed)))
            trimmed.release()
            seg.release()
            return
        }
        val subs = ImagePreprocessor.splitTextLines(seg, minHeight = 8)
        if (subs.size <= 1) {
            subs.forEach { it.release() }
            val trimmed = ImagePreprocessor.trimToContent(seg)
            if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(trimmed, "ocr_text_line_${texts.size + 1}")
            texts.add(cleanOcrLine(predictAndRecycle(trimmed)))
            trimmed.release()
            seg.release()
            return
        }
        seg.release()
        for (sub in subs) {
            recTextSegments(sub, texts)
        }
    }

    /** 对应 wangxian_ocr _clean_ocr_line：去 Lv、去首尾杂字符、剔除括号/答题行，并只保留技能前缀行 */
    private fun cleanOcrLine(text: String): String {
        var t = text.replace(Regex("(?:LV|V)\\s*\\d+", RegexOption.IGNORE_CASE), "")
        t = t.trim().trim(',')
        t = t.trimStart('：', ':', '；', ';', '，', ',', '。', '.', '、', '-', ' ')
        if (t.isEmpty() || t.contains('(') || t.contains(')') || t.contains("答题")) return ""
        // 只保留以技能前缀开头的行，过滤乱码（如 ¥+++——+）与其他标签
        if (!SKILL_PREFIXES.any { t.startsWith(it) }) return ""
        return t
    }

    fun ocrAntiCheatOptionGrid(base64Str: String, x: Int, y: Int, w: Int, h: Int, faint: Boolean = false): Map<String, OcrDetection> {
        val mat = decodeToMat(base64Str) ?: return emptyMap()
        try {
            val halfW = w / 2
            val halfH = h / 2
            val cells = linkedMapOf(
                "A" to Rect(x, y, halfW, halfH),
                "B" to Rect(x + halfW, y, w - halfW, halfH),
                "C" to Rect(x, y + halfH, halfW, h - halfH),
                "D" to Rect(x + halfW, y + halfH, w - halfW, h - halfH)
            )
            val result = linkedMapOf<String, OcrDetection>()
            for ((letter, rect) in cells) {
                val padX = (rect.width * 0.06).toInt()
                val padY = (rect.height * 0.12).toInt()
                var cropLeft = rect.x + padX
                var cropTop = rect.y + padY
                val baseRight = rect.x + rect.width - padX
                val baseBottom = rect.y + rect.height - padY
                val upscaleFactor = if (faint) AppConfig.OPTION_OCR_UPSCALE else 1.0
                var roi = ImagePreprocessor.crop(mat, cropLeft, cropTop, baseRight, baseBottom)
                if (roi.empty()) {
                    roi.release()
                    continue
                }
                if (faint) {
                    val up = Mat()
                    Imgproc.resize(roi, up, Size(), upscaleFactor, upscaleFactor, Imgproc.INTER_CUBIC)
                    roi.release()
                    roi = up
                }
                val hsvLower = if (faint) AppConfig.ANTI_QUESTION_HSV_LOWER else AppConfig.WHITE_HSV_LOWER
                val hsvUpper = if (faint) AppConfig.ANTI_QUESTION_HSV_UPPER else AppConfig.WHITE_HSV_UPPER
                // 识别用软掩码（保留灰度渐变），小字识别更准
                var soft = ImagePreprocessor.preprocessAntiCheatWhiteSoft(roi, hsvLower, hsvUpper)
                // 文字紧贴裁剪左/下边缘时，仅向左下各扩展 50px 重裁，避免被裁掉（不向上扩展，防止带入题目文字）
                val bbox = ImagePreprocessor.findContentBbox(soft)
                if (bbox != null && (bbox.x <= 4 || bbox.y + bbox.height >= soft.rows() - 4)) {
                    val newLeft = (cropLeft - 50).coerceAtLeast(0)
                    val newRight = (baseRight + 50).coerceAtMost(mat.cols())
                    val newBottom = (baseBottom + 50).coerceAtMost(mat.rows())
                    if (newRight > newLeft && newBottom > cropTop) {
                        roi.release(); soft.release()
                        cropLeft = newLeft
                        roi = ImagePreprocessor.crop(mat, newLeft, cropTop, newRight, newBottom)
                        if (faint) {
                            val up = Mat()
                            Imgproc.resize(roi, up, Size(), upscaleFactor, upscaleFactor, Imgproc.INTER_CUBIC)
                            roi.release()
                            roi = up
                        }
                        soft = ImagePreprocessor.preprocessAntiCheatWhiteSoft(roi, hsvLower, hsvUpper)
                    }
                }
                if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(soft, "anti_option_${letter}${if (faint) "_faint" else ""}")
                // 文字左边缘（用于点击坐标）：soft 中文字 bbox 左边界映射回原图
                val finalBbox = ImagePreprocessor.findContentBbox(soft)
                val textLeftInMat = if (finalBbox != null) {
                    (cropLeft + (finalBbox.x / upscaleFactor).toInt()).coerceAtLeast(0)
                } else {
                    cropLeft
                }
                // 裁剪到文字紧致框，避免单元格内大量空白被压缩导致识别不全
                val trimmed = ImagePreprocessor.trimToContent(soft)
                val text = cleanAntiCheatText(predictAndRecycle(trimmed))
                trimmed.release(); soft.release(); roi.release()
                result[letter] = OcrDetection(
                    text = text,
                    centerX = rect.x + rect.width / 2,
                    centerY = rect.y + rect.height / 2,
                    confidence = 0.8f,
                    leftX = textLeftInMat
                )
            }
            return result
        } finally { mat.release() }
    }

    private fun ocrRoiDetections(mat: Mat, x: Int, y: Int, w: Int, h: Int, windowType: String = "option"): List<OcrDetection> {
        val roi = ImagePreprocessor.crop(mat, x, y, x + w, y + h)
        if (roi.empty()) return emptyList()
        val isRelaxed = windowType == "question" || windowType == "option_faint"
        val hsvLower = if (isRelaxed) AppConfig.ANTI_QUESTION_HSV_LOWER else AppConfig.WHITE_HSV_LOWER
        val hsvUpper = if (isRelaxed) AppConfig.ANTI_QUESTION_HSV_UPPER else AppConfig.WHITE_HSV_UPPER
        val processed = ImagePreprocessor.preprocessAntiCheatWhite(
            roi, targetWidth = AppConfig.ANTI_CHEAT_DET_WIDTH,
            hsvLower = hsvLower, hsvUpper = hsvUpper
        )
        // 识别用软掩码（保留灰度渐变），小字识别更准
        val soft = ImagePreprocessor.preprocessAntiCheatWhiteSoft(roi, hsvLower, hsvUpper)
        if (AppConfig.saveDebug) ImagePreprocessor.saveDebugCrop(processed, "anti_roi_${x}_${y}_$windowType")

        val sx = roi.cols().toDouble() / processed.cols().coerceAtLeast(1)
        val sy = roi.rows().toDouble() / processed.rows().coerceAtLeast(1)
        val boxes = synchronized(inferenceLock) { detModel.predict(processed) }
            .filter { it.score >= AppConfig.ANTI_CHEAT_MIN_CONFIDENCE }
            .sortedWith(compareBy<PaddleLiteDetModel.DetBox> { it.centerY }.thenBy { it.centerX })

        val detections = mutableListOf<OcrDetection>()
        for (box in boxes) {
            val localCrop = cropDetBox(soft, box)
            if (localCrop.empty()) {
                localCrop.release()
                continue
            }
            // 裁剪到文字紧致框，减少检测框不精确导致的裁切
            val trimmed = ImagePreprocessor.trimToContent(localCrop)
            val text = cleanAntiCheatText(predictAndRecycle(trimmed))
            trimmed.release(); localCrop.release()
            if (text.isBlank()) continue
            val boxLeft = minOf(box.x1, box.x2, box.x3, box.x4)
            val centerX = x + (box.centerX * sx).toInt()
            val centerY = y + (box.centerY * sy).toInt()
            val leftX = x + (boxLeft * sx).toInt()
            detections.add(OcrDetection(text = text, centerX = centerX, centerY = centerY, confidence = box.score, leftX = leftX))
        }

        if (detections.isEmpty()) {
            val text = cleanAntiCheatText(predictAndRecycle(soft))
            processed.release(); soft.release(); roi.release()
            return if (text.isBlank()) emptyList() else listOf(
                OcrDetection(text = text, centerX = x + w / 2, centerY = y + h / 2, confidence = 0.8f)
            )
        }

        processed.release(); soft.release(); roi.release()
        return detections
    }

    private fun cropDetBox(mat: Mat, box: PaddleLiteDetModel.DetBox): Mat {
        val minX = minOf(box.x1, box.x2, box.x3, box.x4).coerceIn(0, mat.cols() - 1)
        val maxX = maxOf(box.x1, box.x2, box.x3, box.x4).coerceIn(0, mat.cols() - 1)
        val minY = minOf(box.y1, box.y2, box.y3, box.y4).coerceIn(0, mat.rows() - 1)
        val maxY = maxOf(box.y1, box.y2, box.y3, box.y4).coerceIn(0, mat.rows() - 1)
        if (maxX <= minX || maxY <= minY) return Mat.zeros(1, 1, CvType.CV_8UC3)
        return Mat(mat, Rect(minX, minY, maxX - minX, maxY - minY))
    }

    data class OcrResult(
        val rawResults: Map<String, String> = emptyMap(),
        val index: Int = 0
    ) {
        val question: String get() = rawResults["question"] ?: ""
        val options: List<String> get() = rawResults.filterKeys { it != "question" }
            .map { (k, v) -> "$k: $v" }
    }

    data class OcrDetection(val text: String, val centerX: Int, val centerY: Int, val confidence: Float, val leftX: Int = 0)

    companion object {
        private const val TAG = "PaddleOcrEngine"

        val SKILL_PREFIXES = listOf("初级", "中级", "高级", "特级", "特技", "终极")

        fun extractQuestionIndex(text: String): Int {
            var m = Regex("""^(\d+)\s*[<、.．\s,]""").find(text)
            if (m == null) m = Regex("""^(\d+)""").find(text)
            return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        fun cleanQuestionText(text: String): String {
            var t = text.replace(Regex("""[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]"""), "").trim()
            t = t.replace(Regex("""^[\d]+[<、.．\s]+"""), "")
            t = t.replace(Regex("""^[&?#@*…<>]+"""), "")
            t = t.replace(Regex("""[—\-上原感无让诚品局时成\[\]（）()｛｝{}【】]+$"""), "")
            t = t.replace(Regex("""[　\s]+$"""), "")
            t = t.replace("川母", "川贝母")
            return t.trim()
        }

        fun cleanOptionText(text: String): String {
            var t = text.replace(Regex("""[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]"""), "").trim()
            t = t.replace(Regex("""([一-鿿])[0O]$"""), "$1")
            t = t.replace(Regex("""[0O]$"""), "")
            return t.trim()
        }

        fun cleanAntiCheatText(text: String): String {
            return text.replace(Regex("""[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]"""), "").trim()
        }
    }
}
