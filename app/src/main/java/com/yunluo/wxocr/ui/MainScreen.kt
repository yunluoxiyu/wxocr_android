package com.yunluo.wxocr.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yunluo.wxocr.config.AppConfig
import com.yunluo.wxocr.server.OcrServerService
import com.yunluo.wxocr.server.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToQuestionBank: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToTest: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val logManager = remember { LogManager() }

    var serverRunning by remember { mutableStateOf(ServiceState.isRunning) }
    var serverUrl by remember { mutableStateOf(ServiceState.serverUrl) }
    var port by remember { mutableStateOf(AppConfig.serverPort.toString()) }
    var showMenu by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(ServiceState.progress) }
    var progressMsg by remember { mutableStateOf(ServiceState.progressMessage) }
    var currentIp by remember { mutableStateOf("") }
    val authState = remember { mutableStateOf(false) }
    val logListState = rememberLazyListState()
    var saveDebug by remember { mutableStateOf(AppConfig.saveDebug) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    // 获取当前 IP
    LaunchedEffect(Unit) {
        currentIp = withContext(Dispatchers.IO) { getLocalIpAddress() }
    }

    // 监听 ServiceState 变化
    DisposableEffect(Unit) {
        val progressListener = { pct: Int, msg: String ->
            progress = pct
            progressMsg = msg
            logManager.append(msg)
        }
        val stateListener = { running: Boolean, url: String ->
            serverRunning = running
            serverUrl = url
        }
        val logListener = { msg: String ->
            logManager.append(msg)
        }
        ServiceState.onProgressChanged = progressListener
        ServiceState.onStateChanged = stateListener
        ServiceState.onLogMessage = logListener
        // 初始化
        progress = ServiceState.progress
        progressMsg = ServiceState.progressMessage
        serverRunning = ServiceState.isRunning
        serverUrl = ServiceState.serverUrl
        // 把 ServiceState 中已有的日志拉到 UI
        ServiceState.pendingLogs.forEach { logManager.append(it) }
        ServiceState.pendingLogs.clear()
        onDispose {
            ServiceState.onProgressChanged = null
            ServiceState.onStateChanged = null
            ServiceState.onLogMessage = null
        }
    }

    // 请求忽略电池优化，防止后台被杀
    fun requestIgnoreBatteryOptimization(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    // 启动服务
    fun startServer() {
        val p = port.toIntOrNull() ?: return
        AppConfig.serverPort = p

        val cardKey = AppConfig.cardKey
        if (cardKey == null) {
            logManager.append("请先在设置中配置卡密")
            Toast.makeText(context, "请先在设置中配置卡密", Toast.LENGTH_SHORT).show()
            return
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val timeoutReset = Runnable {
            if (authState.value) {
                authState.value = false
                logManager.append("卡密验证超时，服务未启动")
                Toast.makeText(context, "卡密验证超时", Toast.LENGTH_SHORT).show()
            }
        }
        authState.value = true
        logManager.append("正在验证卡密...")
        mainHandler.postDelayed(timeoutReset, 15000L)

        Thread {
            try {
                val valid = verifyCardKey(cardKey)
                mainHandler.post {
                    mainHandler.removeCallbacks(timeoutReset)
                    authState.value = false
                    try {
                        if (valid) {
                            requestIgnoreBatteryOptimization(context)
                            val intent = Intent(context, OcrServerService::class.java).apply {
                                action = OcrServerService.ACTION_START
                            }
                            ContextCompat.startForegroundService(context, intent)
                            logManager.append("正在启动服务...")
                        } else {
                            logManager.append("卡密验证失败，服务未启动")
                            Toast.makeText(context, "卡密验证失败", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainScreen", "启动异常", e)
                        logManager.append("启动异常: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainScreen", "验证线程异常", e)
                mainHandler.post {
                    mainHandler.removeCallbacks(timeoutReset)
                    authState.value = false
                    logManager.append("卡密验证异常: ${e.message}")
                    Toast.makeText(context, "网络错误", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // 停止服务
    fun stopServer() {
        val intent = Intent(context, OcrServerService::class.java).apply {
            action = OcrServerService.ACTION_STOP
        }
        context.startService(intent)
        logManager.append("正在停止服务...")
    }

    // 申请权限
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WxOCR 服务") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("图片测试") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToTest()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("题库管理") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToQuestionBank()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("调试") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToDebug()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSettings()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清空日志") },
                                onClick = {
                                    showMenu = false
                                    logManager.clear()
                                }
                            )
                        }
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
            // IP 和端口区域
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serverRunning)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 当前 IP（默认显示）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (serverRunning) serverUrl else "http://$currentIp:$port",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 状态指示
                    if (serverRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "运行中",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 端口编辑
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("端口: ", fontSize = 14.sp)
                        OutlinedTextField(
                            value = port,
                            onValueChange = { newVal ->
                                if (newVal.all { it.isDigit() } && newVal.length <= 5) {
                                    port = newVal
                                }
                            },
                            modifier = Modifier.width(110.dp),
                            singleLine = true,
                            enabled = !serverRunning,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 调试开关
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("保存调试图片", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("请求截图自动保存到 debug_crops", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = saveDebug,
                        onCheckedChange = { saveDebug = it; AppConfig.saveDebug = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // API 接口说明
            var showApiDocs by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                onClick = { showApiDocs = !showApiDocs }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Api,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "接口文档",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showApiDocs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (showApiDocs) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ApiEndpoint(
                            method = "GET",
                            path = "/health",
                            desc = "健康检查",
                            params = emptyList()
                        )
                        ApiEndpoint(
                            method = "POST",
                            path = "/wx_ocr",
                            desc = "题目区域 OCR 识别 + 题库匹配",
                            params = listOf("img" to "Base64 截图（含题号+题目+选项）")
                        )
                        ApiEndpoint(
                            method = "POST",
                            path = "/wx_anti_cheat_popup",
                            desc = "反作弊弹窗识别",
                            params = listOf("img" to "Base64 截图（含小图按钮）")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 启动进度条
            if (progress in 1..99) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val animProgress by animateIntAsState(
                        targetValue = progress,
                        label = "progress"
                    )
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = animProgress / 100f,
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progressMsg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // 启动/停止按钮
            Button(
                onClick = {
                    if (serverRunning) stopServer() else startServer()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !authState.value,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serverRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                if (authState.value) {
                    Icon(Icons.Default.HourglassEmpty, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = if (serverRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    when {
                        authState.value -> "验证中..."
                        serverRunning -> "停止服务"
                        else -> "启动服务"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 日志显示
            Text(
                text = "运行日志",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(8.dp)
                ) {
                    items(logManager.logs) { log ->
                        Text(
                            text = log,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 自动滚动到底部
            LaunchedEffect(logManager.logs.size) {
                if (logManager.logs.isNotEmpty()) {
                    logListState.scrollToItem(logManager.logs.size - 1)
                }
            }
        }
    }
}

@Composable
private fun ApiEndpoint(method: String, path: String, desc: String, params: List<Pair<String, String>>) {
    val methodColor = when (method) {
        "GET" -> MaterialTheme.colorScheme.primary
        "POST" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = method,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = methodColor,
                modifier = Modifier.width(40.dp)
            )
            Text(
                text = path,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = desc,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 44.dp, top = 2.dp)
        )
        if (params.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 44.dp, top = 4.dp)) {
                params.forEach { (key, value) ->
                    Row {
                        Text(
                            text = key,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(30.dp)
                        )
                        Text(
                            text = value,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

private fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (_: Exception) {
    }
    return "127.0.0.1"
}

private fun verifyCardKey(key: String): Boolean {
    var conn: HttpURLConnection? = null
    try {
        val url = URL("https://yijianwan-km.yunluo.dev/card/status")
        conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val body = JSONObject().put("key", key).toString()
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code != 200) return false
        val resp = conn.inputStream.use { it.reader().readText() }
        return JSONObject(resp).optBoolean("valid", false)
    } catch (e: java.net.SocketTimeoutException) {
        Log.e("verifyCardKey", "连接超时", e)
        return false
    } catch (e: Exception) {
        Log.e("verifyCardKey", "验证异常", e)
        return false
    } finally {
        conn?.disconnect()
    }
}
