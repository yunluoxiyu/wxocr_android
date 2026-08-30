package com.yunluo.wxocr.server

import android.util.Log
import com.google.gson.Gson
import com.yunluo.wxocr.config.AppConfig
import com.yunluo.wxocr.knowledge.FuzzyMatcher
import com.yunluo.wxocr.knowledge.QuestionBank
import com.yunluo.wxocr.ocr.ImagePreprocessor
import com.yunluo.wxocr.ocr.PaddleOcrEngine
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

class OcrHttpServer(
    hostname: String,
    port: Int,
    private val questionBank: QuestionBank,
    private val engine: PaddleOcrEngine
) : NanoHTTPD(hostname, port) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestPermits = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val requestSeq = AtomicInteger(0)

    var logListener: ((String) -> Unit)? = null

    fun updatePort(newPort: Int) {
        log("端口已更新为 $newPort，请重启服务生效")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // CORS preflight — no semaphore needed
        if (method == Method.OPTIONS) {
            val resp = newFixedLengthResponse(Response.Status.OK, "application/json", "")
            addCorsHeaders(resp)
            return resp
        }

        val reqId = nextReqId()
        return try {
            val response = when {
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/wx_ocr" && method == Method.POST -> handleWxOcr(session, reqId)
                uri == "/wx_ocr_text" && method == Method.POST -> handleOcrText(session, reqId)
                uri == "/wx_anti_cheat_popup" && method == Method.POST -> handleAntiCheatPopup(session, reqId)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    """{"error":"not_found"}""")
            }
            addCorsHeaders(response)
            response
        } catch (e: Throwable) {
            Log.e(TAG, "请求处理异常", e)
            log("[$reqId] ERROR: ${e::class.simpleName}: ${e.message}")
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                """{"success":false,"message":"服务器内部错误"}""")
            addCorsHeaders(resp)
            resp
        }
    }

    private fun nextReqId(): String {
        val seq = requestSeq.incrementAndGet()
        return "${System.currentTimeMillis() % 100000}_$seq"
    }

    private inline fun <T> withOcrPermit(block: () -> T): T {
        requestPermits.acquireUninterruptibly()
        try {
            return block()
        } finally {
            requestPermits.release()
        }
    }

    private fun addCorsHeaders(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return resp
    }

    private fun handleHealth(): Response {
        return jsonResponse("""{"status":"ok"}""")
    }

    private fun handleWxOcr(session: IHTTPSession, reqId: String): Response {
        val body = readBody(session) ?: return jsonResponse(
            """{"success":false,"message":"请求体不能为空"}""")

        val img = extractImg(body)
        if (img.isBlank()) {
            log(reqId, "wx_ocr: img 字段为空")
            return jsonResponse("""{"success":false,"message":"img 字段不能为空"}""")
        }

        log(reqId, "wx_ocr: 收到请求, img长度=${img.length}")

        if (AppConfig.saveDebug) {
            ImagePreprocessor.saveBase64Image(img, "wx_ocr_req_$reqId")
        }

        // Step 1: 题号 OCR（信号量内）
        val questionIndex = withOcrPermit { engine.ocrIndexRegion(img) }
        if (questionIndex <= 0) {
            log(reqId, "wx_ocr: 题号识别失败")
            return jsonResponse("""{"success":false,"message":"题号识别失败，请重新截图请求","index":0}""")
        }
        log(reqId, "wx_ocr: 题号=$questionIndex")

        // Step 2: 按题号查缓存（同一题号短时间内复用完整 OCR 结果）
        val now = System.currentTimeMillis()
        val cached = Companion.responseCache[questionIndex]
        if (cached != null && now - cached.timestamp < (AppConfig.CACHE_TTL_SECONDS * 1000).toLong()) {
            log(reqId, "wx_ocr: 命中题号缓存 index=$questionIndex")
            return jsonResponse(gson.toJson(cached.result))
        }

        // Step 3: 完整 5 区域 OCR（信号量内）
        val ocrResult = withOcrPermit { engine.ocrAllRegions(img) }
        val questionText = ocrResult.question
        val options = ocrResult.options

        log(reqId, "wx_ocr: 题目=$questionText")

        var matchedAnswer: String? = null
        var bestLetter = ""
        var bestX = 0
        var bestY = 0

        if (questionText.isNotBlank()) {
            val match = questionBank.search(questionText)
            if (match != null) {
                matchedAnswer = match.answer
                log(reqId, "wx_ocr: 题库答案=${match.answer}")

                var bestScore = 0.0
                for ((key, _) in AppConfig.CROP_AREA) {
                    if (key == "question") continue
                    val letter = key.split("_")[1].uppercase()
                    val optText = ocrResult.rawResults[letter] ?: ""
                    val score = FuzzyMatcher.ratio(match.answer, optText)
                    Log.d(TAG, "  $letter: $optText vs ${match.answer} -> score=${"%.2f".format(score)}")
                    if (score > bestScore) {
                        bestScore = score
                        bestLetter = letter
                    }
                }
                if (bestLetter.isNotBlank() && bestScore > AppConfig.FUZZ_MATCH_THRESHOLD) {
                    val cropKey = "option_${bestLetter.lowercase()}"
                    val cropArea = AppConfig.CROP_AREA[cropKey]
                    if (cropArea != null) {
                        val (x1, y1, _, y2) = cropArea
                        bestX = x1 + 25
                        bestY = y1 + (y2 - y1) / 2
                    }
                    log(reqId, "wx_ocr: 最佳匹配=$bestLetter 坐标=($bestX,$bestY) 相似度=${"%.2f".format(bestScore)}")
                }
            } else {
                log(reqId, "wx_ocr: 未在题库中找到匹配")
            }
        }

        val responseMap = linkedMapOf(
            "letter" to bestLetter,
            "x" to bestX,
            "y" to bestY,
            "question" to questionText,
            "options" to options,
            "answer" to (matchedAnswer ?: ""),
            "index" to questionIndex
        )

        // Step 4: 写入题号缓存
        Companion.responseCache[questionIndex] = CacheEntry(responseMap, now)

        log(reqId, "wx_ocr: 完成 -> letter=$bestLetter index=$questionIndex")
        return jsonResponse(gson.toJson(responseMap))
    }

    private fun handleOcrText(session: IHTTPSession, reqId: String): Response {
        val body = readBody(session) ?: return jsonResponse(
            """{"success":false,"message":"请求体不能为空"}""")

        val img = extractImg(body)
        if (img.isBlank()) {
            return jsonResponse("""{"success":false,"message":"img 字段不能为空"}""")
        }

        log(reqId, "wx_ocr_text: 收到请求, img长度=${img.length}")

        if (AppConfig.saveDebug) {
            ImagePreprocessor.saveBase64Image(img, "ocr_text_req_$reqId")
        }

        val text = withOcrPermit { engine.ocrTextRegion(img) }

        log(reqId, "wx_ocr_text: 识别结果=$text")

        val prefix = text.substringBefore(',')
            .trim()
            .take(2)

        return jsonResponse(gson.toJson(linkedMapOf(
            "success" to true,
            "text" to text,
            "prefix" to prefix
        )))
    }

    private fun handleAntiCheatPopup(session: IHTTPSession, reqId: String): Response {
        val body = readBody(session) ?: return jsonResponse(
            """{"success":false,"message":"请求体不能为空"}""")

        val img = extractImg(body)
        if (img.isBlank()) {
            return jsonResponse("""{"success":false,"message":"img 字段不能为空"}""")
        }

        log(reqId, "anti_cheat_popup: 收到请求, img长度=${img.length}")

        if (AppConfig.saveDebug) {
            ImagePreprocessor.saveBase64Image(img, "anti_cheat_req_$reqId")
        }

        // OCR 阶段（信号量内，防止并发 OOM；DeepSeek 网络调用放到信号量外）
        val ocr = withOcrPermit {
            val matchResult = engine.findAntiCheatButton(img)
            if (matchResult == null) {
                log(reqId, "anti_cheat_popup: 未匹配到反作弊按钮")
                return@withOcrPermit null
            }
            log(reqId, "anti_cheat_popup: 匹配成功 相似度=${"%.3f".format(matchResult.confidence)} 小图左上角=(${matchResult.x},${matchResult.y})")

            val btnX = matchResult.x
            val btnY = matchResult.y

            val qs = AppConfig.QUESTION_SEARCH
            val qx = btnX - qs.offsetX
            val qy = btnY - qs.offsetY

            val os = AppConfig.OPTION_SEARCH
            val ox = btnX - os.offsetX
            val oy = btnY - os.offsetY

            val questionDetections = engine.ocrRoiDetections(img, qx, qy, qs.w, qs.h, "question")
            val optionDetections = engine.ocrRoiDetections(img, ox, oy, os.w, os.h, "option")

            var questionText = extractQuestionText(questionDetections)

            // 题目汉字过少视为识别不全，用整窗 OCR 兜底（宽松阈值）
            val questionCn = questionText.count { it in '\u4e00'..'\u9fff' }
            if (questionCn < 4) {
                val full = engine.ocrQuestionFallback(img, qx, qy, qs.w, qs.h)
                if (full.count { it in '\u4e00'..'\u9fff' } > questionCn) {
                    log(reqId, "anti_cheat_popup: 题目整窗兜底 -> $full")
                    questionText = full
                }
            }

            // 选项：整窗检测 + 阅读顺序赋予 ABCD（从左到右，从上到下），不固定 2x2 裁切
            val optBlocks = optionDetections
                .filter { !isDigitNoise(it.text) }
                .map { it.copy(text = cleanOptionText(it.text)) }
            var classified: Map<String, PaddleOcrEngine.OcrDetection> = classifyByReadingOrder(optBlocks)
            var strictScore = optionClassifyScore(classified)

            // 选项不全时用宽松阈值+放大补全一次，取更完整结果
            if (strictScore < 5) {
                log(reqId, "anti_cheat_popup: 选项不全(score=$strictScore)，尝试补全识别")
                val faintDetections = engine.ocrRoiDetections(img, ox, oy, os.w, os.h, "option_faint")
                val faintBlocks = faintDetections
                    .filter { !isDigitNoise(it.text) }
                    .map { it.copy(text = cleanOptionText(it.text)) }
                val faintCls = classifyByReadingOrder(faintBlocks)
                val faintScore = optionClassifyScore(faintCls)
                if (faintScore > strictScore) {
                    log(reqId, "anti_cheat_popup: 采用补全结果")
                    classified = faintCls
                    strictScore = faintScore
                }
            }

            // 检测仍不足时用 2x2 网格兜底
            if (classified.isEmpty() || classified.values.count { it.text.isNotBlank() } < 2) {
                log(reqId, "anti_cheat_popup: 检测框选项不足，启用 2x2 网格兜底 OCR")
                val grid = engine.ocrAntiCheatOptionGrid(img, ox, oy, os.w, os.h)
                if (optionClassifyScore(grid) > strictScore) {
                    classified = grid
                }
            }

            val optionsMap = linkedMapOf(
                "A" to (classified["A"]?.text ?: ""),
                "B" to (classified["B"]?.text ?: ""),
                "C" to (classified["C"]?.text ?: ""),
                "D" to (classified["D"]?.text ?: "")
            )

            // 各选项点击坐标 = 识别框文字最左侧 + 偏移，y 取文字垂直中心（网格兜底时为单元格左侧）
            fun clickPoint(d: PaddleOcrEngine.OcrDetection?): Pair<Int, Int> {
                if (d == null) return (0 to 0)
                return (d.leftX + 10) to d.centerY
            }
            val clickCoords = mapOf(
                "A" to clickPoint(classified["A"]),
                "B" to clickPoint(classified["B"]),
                "C" to clickPoint(classified["C"]),
                "D" to clickPoint(classified["D"])
            )

            AntiCheatOcrResult(btnX, btnY, ox, oy, qs, os, questionText, optionsMap, clickCoords)
        }

        if (ocr == null) {
            return jsonResponse("""{"success":false,"message":"未匹配到反作弊按钮"}""")
        }

        log(reqId, "anti_cheat_popup: 题目=${ocr.questionText}, 选项=${ocr.optionsMap}")

        val answer = if (ocr.questionText.isNotBlank() && ocr.optionsMap.values.any { it.isNotBlank() }) {
            askDeepSeek(reqId, ocr.questionText, ocr.optionsMap)
        } else null

        // 点击坐标 = 对应选项识别框中心（正确答案所在位置）
        val clickCoord = if (answer != null) ocr.clickCoords[answer] ?: (0 to 0) else (0 to 0)
        val clickX = clickCoord.first
        val clickY = clickCoord.second

        val responseMap = linkedMapOf(
            "question" to ocr.questionText,
            "options" to ocr.optionsMap,
            "answer" to (answer ?: ""),
            "click_x" to clickX,
            "click_y" to clickY,
            "btn_x" to (ocr.btnX + AppConfig.BTN_CLICK_OFFSET_X),
            "btn_y" to ocr.btnY
        )

        log(reqId, "anti_cheat_popup: 完成 answer=${answer ?: ""}")

        return jsonResponse(gson.toJson(responseMap))
    }

    private data class AntiCheatOcrResult(
        val btnX: Int,
        val btnY: Int,
        val ox: Int,
        val oy: Int,
        val qs: AppConfig.SearchRect,
        val os: AppConfig.SearchRect,
        val questionText: String,
        val optionsMap: LinkedHashMap<String, String>,
        val clickCoords: Map<String, Pair<Int, Int>>
    )

    private fun isDigitNoise(text: String): Boolean {
        if (text.isBlank()) return false
        val digits = text.count { it.isDigit() }
        return digits >= 3 && digits >= text.length * 0.6
    }

    private fun cleanOptionText(text: String): String {
        return text.trimStart('…', '—', '·', ':', '：', ';', '；', ',', '，', '.', '。', '-', '、', ' ')
            .trimEnd('…', '—', '·', ':', '：', ';', '；', ',', '，', '.', '。', '-', '、', ' ')
    }

    /** 按阅读顺序拼接：先按 center_y 分行（行内 y 差 <= 20px 视为同一行），行内按 center_x 从左到右 */
    private fun joinInReadingOrder(dets: List<PaddleOcrEngine.OcrDetection>): String {
        val sorted = dets.sortedBy { it.centerY }
        val lines = mutableListOf<MutableList<PaddleOcrEngine.OcrDetection>>()
        for (d in sorted) {
            if (lines.isNotEmpty() && d.centerY - lines.last().last().centerY <= 20) {
                lines.last().add(d)
            } else {
                lines.add(mutableListOf(d))
            }
        }
        return lines.joinToString("") { line ->
            line.sortedBy { it.centerX }.joinToString("") { it.text }
        }
    }

    /** 从题目窗文本块提取完整题目：过滤低置信/数字噪点，保留长块，按阅读顺序拼接 */
    private fun extractQuestionText(detections: List<PaddleOcrEngine.OcrDetection>): String {
        val good = detections
            .filter { it.confidence >= AppConfig.OCR_MIN_CONFIDENCE && it.text.isNotBlank() }
            .filter { !isDigitNoise(it.text) }
        if (good.isEmpty()) return ""
        val maxLen = good.maxOf { it.text.length }
        if (maxLen < 4) {
            return joinInReadingOrder(good)
        }
        val keep = good.filter { it.text.length >= maxLen * 0.5 }
        return joinInReadingOrder(keep)
    }

    /** 评估选项分类质量：非空字母越多越好，且互不相同（避免退化分类把一块填到多个字母），满分 5 */
    private fun optionClassifyScore(cls: Map<String, PaddleOcrEngine.OcrDetection>): Int {
        val texts = listOf("A", "B", "C", "D").map { cls[it]?.text ?: "" }
        val nonempty = texts.filter { it.isNotBlank() }
        var score = nonempty.size
        if (nonempty.size == nonempty.toSet().size) {
            score += 1
        }
        return score
    }

    /** 按阅读顺序赋予 ABCD：先按 center_y 分行（行内差 <= 20px），每行从左到右，依次 A/B/C/D */
    private fun classifyByReadingOrder(dets: List<PaddleOcrEngine.OcrDetection>): LinkedHashMap<String, PaddleOcrEngine.OcrDetection> {
        val result = linkedMapOf<String, PaddleOcrEngine.OcrDetection>()
        val sorted = dets.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<PaddleOcrEngine.OcrDetection>>()
        for (d in sorted) {
            if (rows.isNotEmpty() && d.centerY - rows.last().last().centerY <= 20) {
                rows.last().add(d)
            } else {
                rows.add(mutableListOf(d))
            }
        }
        val letters = arrayOf("A", "B", "C", "D")
        var idx = 0
        for (row in rows) {
            for (d in row.sortedBy { it.centerX }) {
                if (idx < letters.size) {
                    result[letters[idx]] = d
                    idx++
                }
            }
        }
        return result
    }

    private fun classifyOptionsGrid(results: List<PaddleOcrEngine.OcrDetection>): Map<String, PaddleOcrEngine.OcrDetection> {
        if (results.size < 2) return emptyMap()
        val sortedByY = results.sortedBy { it.centerY }
        var splitIdx = sortedByY.size / 2
        if (sortedByY.size >= 4) {
            val bestGap = sortedByY.zipWithNext().mapIndexed { i, pair ->
                i to (pair.second.centerY - pair.first.centerY)
            }.maxByOrNull { it.second }
            if (bestGap != null && bestGap.second > AppConfig.GRID_Y_GAP_MIN) {
                splitIdx = bestGap.first + 1
            }
        }

        val topRow = sortedByY.take(splitIdx)
            .sortedByDescending { it.confidence }
            .take(AppConfig.GRID_TOP_N_BY_CONF)
        val bottomRow = sortedByY.drop(splitIdx)
            .sortedByDescending { it.confidence }
            .take(AppConfig.GRID_TOP_N_BY_CONF)

        if (topRow.isEmpty() || bottomRow.isEmpty()) {
            val byX = results.sortedBy { it.centerX }
            return buildMap {
                if (byX.isNotEmpty()) put("A", byX.first())
                if (byX.size >= 2) put("B", byX.last())
            }
        }

        return buildMap {
            put("A", topRow.minBy { it.centerX })
            put("B", topRow.maxBy { it.centerX })
            put("C", bottomRow.minBy { it.centerX })
            put("D", bottomRow.maxBy { it.centerX })
        }
    }

    private fun askDeepSeek(reqId: String, question: String, options: Map<String, String>): String? {
        val apiKey = AppConfig.deepSeekApiKey
            ?.replace(Regex("[\\r\\n\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]"), "")
            ?.takeIf { it.isNotBlank() } ?: run {
            log(reqId, "anti_cheat_popup: DeepSeek API Key 未配置，跳过答题")
            return null
        }
        var conn: HttpURLConnection? = null
        return try {
            val prompt = buildString {
                append("题目：").append(question).append('\n')
                append("选项：\n")
                for (letter in listOf("A", "B", "C", "D")) {
                    append(letter).append(". ").append(options[letter].orEmpty()).append('\n')
                }
                append("请只返回正确答案的字母(A/B/C/D)，不要返回其他内容。")
            }
            val payload = JSONObject()
                .put("model", AppConfig.DEEPSEEK_MODEL)
                .put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", "你是一个答题助手，请根据题目和选项选择正确答案，只返回选项字母，不要解释。"))
                    put(JSONObject().put("role", "user").put("content", prompt))
                })
                .put("max_tokens", 100)

            conn = URL(AppConfig.DEEPSEEK_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = AppConfig.DEEPSEEK_TIMEOUT_MS
            conn.readTimeout = AppConfig.DEEPSEEK_TIMEOUT_MS
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.use { it.reader().readText() }.orEmpty()
            if (conn.responseCode !in 200..299) {
                log(reqId, "anti_cheat_popup: DeepSeek HTTP ${conn.responseCode}: ${resp.take(160)}")
                return null
            }
            val choice = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val raw = (choice.optString("content") + choice.optString("reasoning_content")).trim()
            Regex("[ABCD]").find(raw)?.value
        } catch (e: Exception) {
            log(reqId, "anti_cheat_popup: DeepSeek 调用失败 ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBody(session: IHTTPSession): String? {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val stream = session.getInputStream()
            val bytes = ByteArrayOutputStream(contentLength.coerceAtLeast(1024))
            val tmp = ByteArray(8192)
            if (contentLength > 0) {
                var remaining = contentLength
                while (remaining > 0) {
                    val n = stream.read(tmp, 0, minOf(tmp.size, remaining))
                    if (n < 0) break
                    bytes.write(tmp, 0, n)
                    remaining -= n
                }
            } else {
                val buf = ByteArray(4096)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    bytes.write(buf, 0, n)
                }
            }
            val body = bytes.toString("UTF-8")
            bytes.close()
            Log.d(TAG, "readBody: ${body.length} chars (contentLength=$contentLength)")
            body
        } catch (e: Exception) {
            Log.w(TAG, "读取请求体失败", e)
            null
        }
    }

    private fun jsonResponse(json: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    /** 兼容 JSON 对象、表单格式与裸字符串请求体：`{"img":"..."}`、`img=<base64>` 或直接传 base64 */
    private fun extractImg(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            return try {
                gson.fromJson(trimmed, WxOcrRequest::class.java)?.img ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        if (trimmed.startsWith("img=", ignoreCase = true)) {
            return trimmed.removePrefix("img=")
                .trim()
                .removeSurrounding("\"")
                .trim()
        }
        return trimmed.removeSurrounding("\"").trim()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logListener?.invoke(msg)
    }

    private fun log(reqId: String, msg: String) {
        Log.i(TAG, "[$reqId] $msg")
        logListener?.invoke("[$reqId] $msg")
    }

    override fun stop() {
        scope.cancel()
        super.stop()
    }

    data class WxOcrRequest(val img: String = "", val region: String = "")

    data class CacheEntry(val result: Map<String, Any>, val timestamp: Long)

    companion object {
        private const val TAG = "OcrHttpServer"
        private const val MAX_CONCURRENT_REQUESTS = 3
        val responseCache = ConcurrentHashMap<Int, CacheEntry>()
    }
}
