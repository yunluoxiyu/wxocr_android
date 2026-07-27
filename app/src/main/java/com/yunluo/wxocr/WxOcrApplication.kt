package com.yunluo.wxocr

import android.app.Application
import android.util.Log
import com.yunluo.wxocr.config.AppConfig
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream

class WxOcrApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            val msg = "未捕获异常: thread=${thread.name}, ${ex::class.simpleName}: ${ex.message}"
            Log.e(TAG, msg, ex)
            try {
                val crashLog = File(AppConfig.saveDir, "crash_log.txt")
                crashLog.appendText("[${timestamp()}] $msg\n${ex.stackTraceToString()}\n")
            } catch (_: Exception) {}
        }
        Log.i(TAG, "AppConfig 初始化完成, filesDir=${filesDir.absolutePath}")
        initOpenCV()
        copyAssets()
    }

    private fun initOpenCV() {
        try {
            if (!OpenCVLoader.initDebug()) {
                Log.w(TAG, "OpenCV 初始化失败: initDebug() 返回 false")
            } else {
                Log.i(TAG, "OpenCV 初始化成功")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "OpenCV 初始化失败: 无法加载 OpenCV 原生库", e)
        } catch (e: Exception) {
            Log.w(TAG, "OpenCV 初始化异常: ${e::class.simpleName} - ${e.message}", e)
        }
    }

    private fun copyAssets() {
        Log.i(TAG, "开始复制资源文件到内部存储...")
        copyAssetIfNeeded("template.bmp", AppConfig.templateFile)
        copyAssetIfNeeded("questions.json", AppConfig.questionsFile)
    }

    private fun copyAssetIfNeeded(assetName: String, destFile: File) {
        if (destFile.exists()) {
            Log.i(TAG, "资源已存在，跳过: $assetName -> ${destFile.absolutePath} (${destFile.length()} bytes)")
            return
        }
        try {
            destFile.parentFile?.mkdirs()
            val start = System.currentTimeMillis()
            assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    val bytes = input.copyTo(output)
                    Log.i(TAG, "资源复制成功: $assetName -> ${destFile.absolutePath} ($bytes bytes, ${System.currentTimeMillis() - start}ms)")
                }
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "资源文件未找到: $assetName (APK 中可能未打包此文件)")
        } catch (e: Exception) {
            Log.w(TAG, "复制资源 $assetName 失败: ${e::class.simpleName} - ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "WxOcrApplication"
        private fun timestamp() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
    }
}
