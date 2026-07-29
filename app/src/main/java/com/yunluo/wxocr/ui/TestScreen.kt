package com.yunluo.wxocr.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunluo.wxocr.config.AppConfig
import com.yunluo.wxocr.knowledge.FuzzyMatcher
import com.yunluo.wxocr.knowledge.QuestionBank
import com.yunluo.wxocr.ocr.PaddleOcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TestScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logListState = rememberLazyListState()
    val logs = remember { mutableStateListOf<String>() }
    var testRunning by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null || testRunning) return@rememberLauncherForActivityResult
        testRunning = true
        logs.clear()
        scope.launch(Dispatchers.IO) {
            val msgs = mutableListOf<String>()
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    msgs.add("读取图片失败")
                    postLogs(msgs, logs)
                    return@launch
                }
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                msgs.add("已选择图片: ${bytes.size} bytes")

                msgs.add("--- Step 1: 题号 OCR ---")
                var engine: PaddleOcrEngine? = null
                try {
                    engine = PaddleOcrEngine()
                    val questionIndex = engine.ocrIndexRegion(base64)
                    msgs.add("题号 OCR 结果: index=$questionIndex")
                    if (questionIndex <= 0) {
                        msgs.add("题号识别失败，跳过后续步骤")
                        postLogs(msgs, logs)
                        return@launch
                    }

                    msgs.add("--- Step 2: 完整 5 区域 OCR ---")
                    val ocrResult = engine.ocrAllRegions(base64)
                    val questionText = ocrResult.question
                    msgs.add("题目: $questionText")
                    for (opt in ocrResult.options) msgs.add("选项: $opt")

                    msgs.add("--- Step 3: 题库搜索 ---")
                    if (questionText.isNotBlank()) {
                        val qb = QuestionBank(); qb.load()
                        val match = qb.search(questionText)
                        if (match != null) {
                            msgs.add("题库答案: ${match.answer}")
                            msgs.add("--- Step 4: 选项匹配 ---")
                            var bestLetter = ""; var bestScore = 0.0
                            for ((key, _) in AppConfig.CROP_AREA) {
                                if (key == "question") continue
                                val letter = key.split("_")[1].uppercase()
                                val optText = ocrResult.rawResults[letter] ?: ""
                                val score = FuzzyMatcher.ratio(match.answer, optText)
                                msgs.add("  $letter: \"$optText\" vs \"${match.answer}\" 相似度=${"%.2f".format(score)}")
                                if (score > bestScore) { bestScore = score; bestLetter = letter }
                            }
                            if (bestLetter.isNotBlank() && bestScore > AppConfig.FUZZ_MATCH_THRESHOLD) {
                                val cropKey = "option_${bestLetter.lowercase()}"
                                val cropArea = AppConfig.CROP_AREA[cropKey]
                                if (cropArea != null) {
                                    val (x1, y1, _, y2) = cropArea
                                    val cx = x1 + 25
                                    val cy = y1 + (y2 - y1) / 2
                                    msgs.add("最佳匹配: $bestLetter 坐标=($cx,$cy) 相似度=${"%.2f".format(bestScore)}")
                                } else {
                                    msgs.add("最佳匹配: $bestLetter 相似度=${"%.2f".format(bestScore)}")
                                }
                            }
                        } else { msgs.add("未在题库中找到匹配") }
                    } else { msgs.add("题目为空，跳过题库搜索") }
                    msgs.add("--- 测试完成 ---")
                } finally {
                    engine?.release()
                }
            } catch (e: Exception) {
                msgs.add("异常: ${e.message}")
                Log.e(TAG, "测试异常", e)
            } finally {
                postLogs(msgs, logs)
                withContext(Dispatchers.Main) { testRunning = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片 OCR 测试") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !testRunning
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (logs.isEmpty()) "选择图片" else "重新选择", fontWeight = FontWeight.Bold)
                    }
                    if (testRunning) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("OCR 运行中...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("结果", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
            }
        }
    }
}

private suspend fun postLogs(msgs: List<String>, logs: MutableList<String>) {
    try {
        withContext(Dispatchers.Main) { msgs.forEach { logs.add(it) } }
    } catch (_: kotlinx.coroutines.CancellationException) { }
}
