package com.yunluo.wxocr.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.yunluo.wxocr.MainActivity
import com.yunluo.wxocr.config.AppConfig
import com.yunluo.wxocr.knowledge.QuestionBank
import com.yunluo.wxocr.ocr.PaddleOcrEngine
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

class OcrServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: OcrHttpServer? = null
    private var questionBank: QuestionBank? = null
    private var ocrEngine: PaddleOcrEngine? = null

    var onLog: ((String) -> Unit)? = null
    var onStatusChange: ((Boolean, String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
            ACTION_UPDATE_PORT -> {
                val port = intent.getIntExtra("port", AppConfig.serverPort)
                AppConfig.serverPort = port
                httpServer?.updatePort(port)
                httpServer?.let {
                    val ip = getLocalIpAddress()
                    onStatusChange?.invoke(true, "http://$ip:$port")
                    postLog("端口已更新为 $port")
                }
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (httpServer != null) {
            postLog("服务已在运行中")
            return
        }

        val port = AppConfig.serverPort
        postLog("正在启动 OCR 服务...")
        startForeground(NOTIFICATION_ID, createNotification("服务启动中..."))

        scope.launch {
            try {
                postProgress(10, "加载题库...")
                val qb = QuestionBank()
                qb.load()
                questionBank = qb
                postLog("题库加载完成，共 ${qb.size} 道题")

                postProgress(40, "加载识别模型...")
                val engine = PaddleOcrEngine()
                ocrEngine = engine
                postProgress(70, "模型加载完成")

                postProgress(80, "启动 HTTP 服务 (0.0.0.0:$port)...")
                val server = OcrHttpServer("0.0.0.0", port, qb, engine)
                server.logListener = { msg ->
                    postLog(msg)
                }
                server.start()

                httpServer = server
                postProgress(100, "服务已启动")

                val ip = getLocalIpAddress()
                val msg = "OCR 服务已启动: http://$ip:$port"
                postLog(msg)
                withContext(Dispatchers.Main) {
                    onStatusChange?.invoke(true, "http://$ip:$port")
                    ServiceState.postState(true, "http://$ip:$port")
                    startForeground(NOTIFICATION_ID, createNotification("服务运行中: $ip:$port"))
                }
            } catch (e: Exception) {
                postLog("服务启动失败: ${e.message}")
                Log.e(TAG, "服务启动失败", e)
                postProgress(0, "启动失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    onStatusChange?.invoke(false, "启动失败")
                    ServiceState.postState(false, "启动失败")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    fun setLogger(logger: (String) -> Unit) {
        onLog = logger
    }

    private fun stopServer() {
        postLog("正在停止 OCR 服务...")
        httpServer?.stop()
        httpServer = null
        questionBank = null
        postLog("OCR 服务已停止")
        postProgress(0, "")
        ServiceState.postState(false, "服务已停止")
        runOnMain {
            onStatusChange?.invoke(false, "服务已停止")
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun postLog(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
        ServiceState.postLog(msg)
    }

    private fun postProgress(pct: Int, msg: String) {
        ServiceState.postProgress(pct, msg)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
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
        } catch (e: Exception) {
            Log.w(TAG, "获取 IP 失败", e)
        }
        return "127.0.0.1"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OCR 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WxOCR 后台服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)

        return builder
            .setContentTitle("WxOCR")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        httpServer?.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.yunluo.wxocr.action.START"
        const val ACTION_STOP = "com.yunluo.wxocr.action.STOP"
        const val ACTION_UPDATE_PORT = "com.yunluo.wxocr.action.UPDATE_PORT"
        private const val TAG = "OcrServerService"
        private const val CHANNEL_ID = "wxocr_service"
        private const val NOTIFICATION_ID = 1001
    }
}

object ServiceState {
    private val mainHandler = Handler(Looper.getMainLooper())

    var progress: Int = 0
    var progressMessage: String = ""
    var isRunning: Boolean = false
    var serverUrl: String = ""
    var onProgressChanged: ((Int, String) -> Unit)? = null
    var onStateChanged: ((Boolean, String) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null
    val pendingLogs = mutableListOf<String>()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun postProgress(pct: Int, msg: String) {
        runOnMain {
            progress = pct
            progressMessage = msg
            onProgressChanged?.invoke(pct, msg)
        }
    }

    fun postState(running: Boolean, url: String) {
        runOnMain {
            isRunning = running
            serverUrl = if (running) url else ""
            onStateChanged?.invoke(running, url)
        }
    }

    fun postLog(msg: String) {
        android.util.Log.i("ServiceState", msg)
        runOnMain {
            if (onLogMessage != null) {
                onLogMessage?.invoke(msg)
            } else {
                pendingLogs.add(msg)
            }
        }
    }
}
