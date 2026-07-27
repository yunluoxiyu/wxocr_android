package com.yunluo.wxocr.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yunluo.wxocr.knowledge.Question
import com.yunluo.wxocr.knowledge.QuestionBank
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionBankScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val questionBank = remember { QuestionBank() }
    var questions by remember { mutableStateOf(listOf<Question>()) }
    var searchQuery by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    // 编辑对话框
    var showEditDialog by remember { mutableStateOf(false) }
    var editingQuestion by remember { mutableStateOf<Question?>(null) }
    var editQuestionText by remember { mutableStateOf("") }
    var editAnswerText by remember { mutableStateOf("") }

    // 删除确认对话框
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deletingQuestion by remember { mutableStateOf<Question?>(null) }

    // 导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = readTextFromUri(context, uri)
                    val count = questionBank.importFromJson(text)
                    questionBank.saveToFile()
                    questions = questionBank.getAll()
                    Toast.makeText(context, "导入 $count 道题 (共 ${questions.size} 道)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 导出
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = questionBank.exportToJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    }
                    Toast.makeText(context, "导出成功", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!loaded) {
            questionBank.load()
            questions = questionBank.getAll()
            loaded = true
        }
    }

    // 筛选
    val filteredQuestions = remember(questions, searchQuery) {
        if (searchQuery.isBlank()) questions
        else questions.filter {
            it.question.contains(searchQuery, ignoreCase = true) ||
            it.answer.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题库管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "导入")
                    }
                    IconButton(onClick = { exportLauncher.launch("questions.json") }) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "导出")
                    }
                    IconButton(onClick = {
                        editingQuestion = null
                        editQuestionText = ""
                        editAnswerText = ""
                        showEditDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "添加")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 统计
            Text(
                text = "共 ${filteredQuestions.size} 道题（总 ${questions.size} 道）",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // 搜索
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索题目...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 题目列表
            if (filteredQuestions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "未找到匹配的题目" else "题库为空",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filteredQuestions) { _, q ->
                        QuestionItem(
                            question = q,
                            onEdit = {
                                editingQuestion = q
                                editQuestionText = q.question
                                editAnswerText = q.answer
                                showEditDialog = true
                            },
                            onDelete = {
                                deletingQuestion = q
                                showDeleteConfirm = true
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm && deletingQuestion != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                deletingQuestion = null
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("确认删除") },
            text = {
                Text("确定删除题目「${deletingQuestion!!.question.take(40)}」吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    val q = deletingQuestion!!
                    scope.launch {
                        questionBank.delete(q.id)
                        questionBank.saveToFile()
                        questions = questionBank.getAll()
                        showDeleteConfirm = false
                        deletingQuestion = null
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deletingQuestion = null
                }) { Text("取消") }
            }
        )
    }

    // 编辑对话框
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(if (editingQuestion != null) "编辑题目" else "添加题目")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = editQuestionText,
                        onValueChange = { editQuestionText = it },
                        label = { Text("题目") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editAnswerText,
                        onValueChange = { editAnswerText = it },
                        label = { Text("答案") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val q = editingQuestion
                        if (q != null) {
                            questionBank.update(q.id, editQuestionText, editAnswerText)
                            Toast.makeText(context, "已更新", Toast.LENGTH_SHORT).show()
                        } else {
                            questionBank.add(editQuestionText, editAnswerText)
                            Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                        }
                        questionBank.saveToFile()
                        questions = questionBank.getAll()
                        showEditDialog = false
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun QuestionItem(
    question: Question,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = question.question,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Text(
                    text = "答案: ${question.answer}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "编辑",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    val sb = StringBuilder()
    context.contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
        }
    }
    return sb.toString()
}
