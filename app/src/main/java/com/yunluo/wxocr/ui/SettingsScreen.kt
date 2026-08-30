package com.yunluo.wxocr.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunluo.wxocr.config.AppConfig
import com.yunluo.wxocr.server.ServiceState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var cardKeyInput by remember { mutableStateOf(AppConfig.cardKey ?: "") }
    var cardKeyError by remember { mutableStateOf<String?>(null) }
    var deepSeekKeyInput by remember { mutableStateOf(AppConfig.deepSeekApiKey ?: "") }
    var deepSeekKeyError by remember { mutableStateOf<String?>(null) }
    var dirInput by remember { mutableStateOf(AppConfig.saveDirPath) }
    var dirError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 卡密管理
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("卡密管理", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("当前卡密: ${(AppConfig.cardKey?.take(4) ?: "无") + "****"}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cardKeyInput,
                        onValueChange = { cardKeyInput = it; cardKeyError = null },
                        label = { Text("新卡密") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = cardKeyError != null
                    )
                    if (cardKeyError != null) {
                        Text(cardKeyError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = {
                            val newKey = cardKeyInput.trim()
                            if (newKey.isBlank()) { cardKeyError = "卡密不能为空"; return@Button }
                            AppConfig.cardKey = newKey
                            ServiceState.postLog("卡密已更新")
                        }) { Text("保存") }
                        if (AppConfig.cardKey != null) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                                AppConfig.cardKey = null
                                Toast.makeText(context, "卡密已清除，启动服务需重新设置", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清除卡密", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // DeepSeek API Key
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("DeepSeek API Key", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("反作弊弹框答题会使用此 Key 调用 DeepSeek", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deepSeekKeyInput,
                        onValueChange = { deepSeekKeyInput = it; deepSeekKeyError = null },
                        label = { Text("DeepSeek API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = deepSeekKeyError != null
                    )
                    if (deepSeekKeyError != null) {
                        Text(deepSeekKeyError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = {
                            val newKey = deepSeekKeyInput.trim()
                            if (newKey.isBlank()) { deepSeekKeyError = "API Key 不能为空"; return@Button }
                            AppConfig.deepSeekApiKey = newKey
                            ServiceState.postLog("DeepSeek API Key 已更新")
                            Toast.makeText(context, "DeepSeek API Key 已保存", Toast.LENGTH_SHORT).show()
                        }) { Text("保存") }
                        if (AppConfig.deepSeekApiKey != null) {
                            OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                                AppConfig.deepSeekApiKey = null
                                deepSeekKeyInput = ""
                                Toast.makeText(context, "DeepSeek API Key 已清除", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("清除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // 保存目录
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("保存目录", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("调试图片和日志文件将保存在此目录", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = dirInput,
                        onValueChange = { dirInput = it; dirError = null },
                        label = { Text("目录路径") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = dirError != null
                    )
                    if (dirError != null) {
                        Text(dirError!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        val path = dirInput.trim()
                        if (path.isNotBlank()) {
                            val dir = File(path)
                            if (dir.mkdirs() || dir.exists()) {
                                AppConfig.saveDirPath = path
                                dirError = null
                                ServiceState.postLog("保存目录已设置为: $path")
                                Toast.makeText(context, "保存目录已更新", Toast.LENGTH_SHORT).show()
                            } else {
                                dirError = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                    !Environment.isExternalStorageManager()
                                ) {
                                    "目录创建失败：Android 11+ 需要先授予“所有文件访问”权限"
                                } else {
                                    "目录创建失败，请检查路径是否可写"
                                }
                                Toast.makeText(context, dirError, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            dirError = "路径不能为空"
                        }
                    }) { Text("保存") }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                    ) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        }) { Text("授予所有文件访问权限") }
                        Spacer(Modifier.height(4.dp))
                        Text("或使用下方 App 专属目录（无需权限）：${AppConfig.defaultSaveDir()}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
