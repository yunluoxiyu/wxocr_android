package com.yunluo.wxocr.ui

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.*

class LogManager {
    private val _logs = mutableStateListOf<String>()
    val logs: List<String> get() = _logs

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun append(msg: String) {
        val timeStr = dateFormat.format(Date())
        _logs.add("[$timeStr] $msg")
        if (_logs.size > MAX_LOG_LINES) {
            _logs.removeAt(0)
        }
    }

    fun clear() {
        _logs.clear()
    }

    companion object {
        private const val MAX_LOG_LINES = 500
    }
}
