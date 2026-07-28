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

class OcrHttpServer(
    hostname: String,
    port: Int,
    private val questionBank: QuestionBank,
    private val engine: PaddleOcrEngine
) : NanoHTTPD(hostname, port) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var logListener: ((String) -> Unit)? = null

    fun updatePort(newPort: Int) {
        log("端口已更新为 $newPort，请重启服务生效")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            // CORS preflight
            if (method == Method.OPTIONS) {
                val resp = newFixedLengthResponse(Response.Status.OK, "application/json", "")
                addCorsHeaders(resp)
                return resp
            }
            val response = when {
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/wx_ocr" && method == Method.POST -> handleWxOcr(session)
                uri == "/wx_anti_cheat_popup" && method == Method.POST -> handleAntiCheatPopup(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json",
                    """{"error":"not_found"}""")
            }
            addCorsHeaders(response)
            response
        } catch (e: Throwable) {
            Log.e(TAG, "请求处理异常", e)
            log("ERROR: ${e::class.simpleName}: ${e.message}")
            val resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                """{"success":false,"message":"服务器内部错误"}""")
            addCorsHeaders(resp)
            resp
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

    private fun handleWxOcr(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonResponse(
            """{"success":false,"message":"请求体不能为空"}""")

        val img = gson.fromJson(body, WxOcrRequest::class.java)?.img ?: ""
        if (img.isBlank()) {
            log("wx_ocr: img 字段为空")
            return jsonResponse("""{"success":false,"message":"img 字段不能为空"}""")
        }

        log("wx_ocr: 收到请求, img长度=${img.length}")

        if (AppConfig.saveDebug) {
            ImagePreprocessor.saveBase64Image(img, "wx_ocr_req")
        }

        // Step 1: 题号 OCR
        val questionIndex = engine.ocrIndexRegion(img)
        if (questionIndex <= 0) {
            log("wx_ocr: 题号识别失败")
            return jsonResponse("""{"success":false,"message":"题号识别失败，请重新截图请求","index":0}""")
        }
        log("wx_ocr: 题号=$questionIndex")

        // Step 2: 按题号查缓存（同一题号短时间内复用完整 OCR 结果）
        val now = System.currentTimeMillis()
        val cached = Companion.responseCache[questionIndex]
        if (cached != null && now - cached.timestamp < (AppConfig.CACHE_TTL_SECONDS * 1000).toLong()) {
            log("wx_ocr: 命中题号缓存 index=$questionIndex")
            return jsonResponse(gson.toJson(cached.result))
        }

        // Step 3: 完整 5 区域 OCR
        val ocrResult = engine.ocrAllRegions(img)
        val questionText = ocrResult.question
        val options = ocrResult.options

        log("wx_ocr: 题目=$questionText")

        var matchedAnswer: String? = null
        var bestLetter = ""
        var bestX = 0
        var bestY = 0

        if (questionText.isNotBlank()) {
            runBlocking {
                val match = questionBank.search(questionText)
                if (match != null) {
                    matchedAnswer = match.answer
                    log("wx_ocr: 题库答案=${match.answer}")

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
                        val coord = AppConfig.OPTION_COORDS[bestLetter] ?: intArrayOf(0, 0)
                        bestX = coord[0]; bestY = coord[1]
                        log("wx_ocr: 最佳匹配=$bestLetter 坐标=($bestX,$bestY) 相似度=${"%.2f".format(bestScore)}")
                    }
                } else {
                    log("wx_ocr: 未在题库中找到匹配")
                }
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

        log("wx_ocr: 完成 -> letter=$bestLetter index=$questionIndex")
        return jsonResponse(gson.toJson(responseMap))
    }

    private fun handleAntiCheatPopup(session: IHTTPSession): Response {
        val body = readBody(session) ?: return jsonResponse(
            """{"success":false,"message":"请求体不能为空"}""")

        val img = gson.fromJson(body, WxOcrRequest::class.java)?.img ?: ""
        if (img.isBlank()) {
            return jsonResponse("""{"success":false,"message":"img 字段不能为空"}""")
        }

        log("anti_cheat_popup: 收到请求, img长度=${img.length}")

        if (AppConfig.saveDebug) {
            ImagePreprocessor.saveBase64Image(img, "anti_cheat_req")
        }

        val matchResult = engine.findAntiCheatButton(img)
        if (matchResult == null) {
            log("anti_cheat_popup: 未匹配到反作弊按钮")
            return jsonResponse("""{"success":false,"message":"未匹配到反作弊按钮"}""")
        }
        log("anti_cheat_popup: 匹配成功 相似度=${"%.3f".format(matchResult.confidence)} 小图左上角=(${matchResult.x},${matchResult.y})")

        val btnX = matchResult.x
        val btnY = matchResult.y

        val qs = AppConfig.QUESTION_SEARCH
        val qx = btnX - qs.offsetX
        val qy = btnY - qs.offsetY

        val os = AppConfig.OPTION_SEARCH
        val ox = btnX - os.offsetX
        val oy = btnY - os.offsetY

        val questionDetections = engine.ocrRoiDetections(img, qx, qy, qs.w, qs.h)
        val optionDetections = engine.ocrRoiDetections(img, ox, oy, os.w, os.h)

        val questionText = questionDetections.sortedBy { it.centerY }.joinToString("") { it.text }

        var classified = classifyOptionsGrid(optionDetections)
        if (classified.isEmpty() || classified.values.count { it.text.isNotBlank() } < 2) {
            log("anti_cheat_popup: 检测框选项不足，启用 2x2 网格兜底 OCR")
            classified = engine.ocrAntiCheatOptionGrid(img, ox, oy, os.w, os.h)
        }

        val optionsMap = linkedMapOf(
            "A" to (classified["A"]?.text ?: ""),
            "B" to (classified["B"]?.text ?: ""),
            "C" to (classified["C"]?.text ?: ""),
            "D" to (classified["D"]?.text ?: "")
        )

        log("anti_cheat_popup: 题目=$questionText, 选项=$optionsMap")

        val answer = if (questionText.isNotBlank() && optionsMap.values.any { it.isNotBlank() }) {
            askDeepSeek(questionText, optionsMap)
        } else null
        val click = answer?.let { classified[it] }

        val responseMap = linkedMapOf(
            "question" to questionText,
            "options" to optionsMap,
            "answer" to (answer ?: ""),
            "click_x" to (click?.centerX ?: 0),
            "click_y" to (click?.centerY ?: 0),
            "btn_x" to (btnX + AppConfig.BTN_CLICK_OFFSET_X),
            "btn_y" to btnY
        )

        log("anti_cheat_popup: 完成 answer=${answer ?: ""}")
        return jsonResponse(gson.toJson(responseMap))
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

    private fun askDeepSeek(question: String, options: Map<String, String>): String? {
        val apiKey = AppConfig.deepSeekApiKey?.takeIf { it.isNotBlank() } ?: run {
            log("anti_cheat_popup: DeepSeek API Key 未配置，跳过答题")
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
                log("anti_cheat_popup: DeepSeek HTTP ${conn.responseCode}: ${resp.take(160)}")
                return null
            }
            val choice = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val raw = (choice.optString("content") + choice.optString("reasoning_content")).trim()
            Regex("[ABCD]").find(raw)?.value
        } catch (e: Exception) {
            log("anti_cheat_popup: DeepSeek 调用失败 ${e.message}")
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

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logListener?.invoke(msg)
    }

    override fun stop() {
        scope.cancel()
        super.stop()
    }

    data class WxOcrRequest(val img: String = "", val region: String = "")

    data class CacheEntry(val result: Map<String, Any>, val timestamp: Long)

    companion object {
        private const val TAG = "OcrHttpServer"
        val responseCache = ConcurrentHashMap<Int, CacheEntry>()
    }
}
