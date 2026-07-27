package com.yunluo.wxocr.knowledge

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yunluo.wxocr.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class QuestionBank {

    private val gson = Gson()
    private var questions: MutableList<Question> = mutableListOf()
    private var loaded = false

    val size: Int get() = questions.size

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = AppConfig.questionsFile
            Log.i(TAG, "加载题库: ${file.absolutePath}")
            if (!file.exists()) {
                Log.w(TAG, "题库文件不存在: ${file.absolutePath} (首次启动会从 assets 复制)")
                loaded = true
                return@withContext true
            }
            if (file.length() == 0L) {
                Log.w(TAG, "题库文件为空: ${file.absolutePath}")
                questions.clear()
                loaded = true
                return@withContext true
            }
            val start = System.currentTimeMillis()
            val text = file.readText()
            Log.d(TAG, "题库文件读取: ${text.length} chars (${System.currentTimeMillis() - start}ms)")
            if (text.isBlank()) {
                Log.w(TAG, "题库文件内容为空")
                questions.clear()
                loaded = true
                return@withContext true
            }
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val rawList: List<Map<String, Any>> = gson.fromJson(text, type) ?: emptyList()
            questions = rawList.map { Question.fromMap(it) }.toMutableList()
            loaded = true
            Log.i(TAG, "题库加载完成: ${questions.size} 道题 (${System.currentTimeMillis() - start}ms)")
            if (questions.isEmpty()) {
                Log.w(TAG, "题库文件存在但解析后为空, 检查 JSON 格式是否匹配")
            }
            true
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "题库文件未找到: ${AppConfig.questionsFile.absolutePath}")
            loaded = true
            false
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.w(TAG, "题库 JSON 解析失败: ${e.message} (文件格式可能已损坏)")
            loaded = true
            false
        } catch (e: Exception) {
            Log.w(TAG, "题库加载失败: ${e::class.simpleName} - ${e.message}", e)
            loaded = true
            false
        }
    }

    fun search(text: String, threshold: Double = AppConfig.FUZZ_MATCH_THRESHOLD): Question? {
        if (text.isBlank()) {
            Log.d(TAG, "search: 查询文本为空")
            return null
        }
        if (!loaded) {
            Log.w(TAG, "search: 题库未加载完成")
            return null
        }
        if (questions.isEmpty()) {
            Log.d(TAG, "search: 题库为空")
            return null
        }
        Log.d(TAG, "search: 开始搜索 \"$text\" (threshold=$threshold, 题库=${questions.size}道)")
        val searchStart = System.currentTimeMillis()
        var best: Question? = null
        var bestScore = 0.0
        for ((i, q) in questions.withIndex()) {
            val score = FuzzyMatcher.ratio(text, q.question)
            if (score > bestScore) {
                bestScore = score
                best = q
            }
            if (i % 500 == 0 && i > 0) {
                Log.d(TAG, "search: 已搜索 $i/${questions.size} 道 (当前最佳: ${"%.4f".format(bestScore)})")
            }
        }
        val searchTime = System.currentTimeMillis() - searchStart
        Log.d(TAG, "search: 全量搜索完成 (${searchTime}ms), 最佳匹配度=${"%.4f".format(bestScore)}")
        if (best != null && bestScore > threshold) {
            Log.i(TAG, "search: 找到匹配 [${"%.2f".format(bestScore)}] \"${best.question}\" -> \"${best.answer}\"")
            return best
        }
        if (best != null) {
            Log.d(TAG, "search: 最佳匹配 \"${best.question}\" (${"%.4f".format(bestScore)}) 未达到阈值 $threshold")
        }
        Log.d(TAG, "search: 未找到匹配")
        return null
    }

    fun getQuestion(index: Int): Question? {
        val q = questions.getOrNull(index)
        Log.d(TAG, "getQuestion($index): ${if (q != null) "找到" else "未找到"}")
        return q
    }

    fun getAll(): List<Question> = questions.toList()

    fun add(question: String, answer: String): Question {
        val id = UUID.randomUUID().toString().replace("-", "")
        Log.i(TAG, "add: id=$id, question=\"$question\", answer=\"$answer\"")
        val q = Question(
            id = id,
            question = question,
            answer = answer
        )
        questions.add(q)
        Log.d(TAG, "add: 题库现有 ${questions.size} 道题")
        return q
    }

    fun update(id: String, question: String, answer: String): Boolean {
        val idx = questions.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "update: 未找到 id=$id")
            return false
        }
        Log.i(TAG, "update: id=$id, question=\"${questions[idx].question}\" -> \"$question\"")
        questions[idx] = questions[idx].copy(question = question, answer = answer)
        return true
    }

    fun delete(id: String): Boolean {
        val idx = questions.indexOfFirst { it.id == id }
        if (idx < 0) {
            Log.w(TAG, "delete: 未找到 id=$id")
            return false
        }
        val q = questions.removeAt(idx)
        Log.i(TAG, "delete: id=$id, question=\"${q.question.take(40)}\"")
        return true
    }

    suspend fun saveToFile(): Boolean = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val list = questions.map { it.toMap() }
            val json = gson.toJson(list)
            AppConfig.questionsFile.writeText(json)
            Log.i(TAG, "题库保存完成: ${questions.size} 道题, ${json.length} chars (${System.currentTimeMillis() - start}ms)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "题库保存失败: ${e::class.simpleName} - ${e.message}", e)
            false
        }
    }

    suspend fun importFromJson(text: String): Int = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val rawList: List<Map<String, Any>> = gson.fromJson(text, type) ?: emptyList()
            val imported = rawList.map { Question.fromMap(it) }
            val before = questions.size
            questions.addAll(imported)
            val count = questions.size - before
            Log.i(TAG, "题库导入: 新增 $count 道, 现有 ${questions.size} 道 (${System.currentTimeMillis() - start}ms)")
            imported.size
        } catch (e: com.google.gson.JsonSyntaxException) {
            Log.w(TAG, "题库导入失败: JSON 格式错误 - ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "题库导入失败: ${e::class.simpleName} - ${e.message}", e)
            throw e
        }
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val list = questions.map { it.toMap() }
        val json = gson.toJson(list)
        Log.i(TAG, "题库导出: ${questions.size} 道题, ${json.length} chars")
        json
    }

    companion object {
        private const val TAG = "QuestionBank"
    }
}
