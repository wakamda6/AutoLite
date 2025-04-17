package com.autolite

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogUtils {
    private const val LOG_TAG = "AuToLite"  // 设置你的应用名
    private const val LOG_FILE_NAME_PREFIX = "AuToLite_"  // 日志文件名称前缀

    private lateinit var logFile: File  // 延迟初始化文件对象

    // 初始化方法，接受上下文作为参数
    fun initialize(context: Context) {
        val logDir = File(context.getExternalFilesDir(null), "YourAppLogs")
        if (!logDir.exists()) {
            logDir.mkdirs() // 创建目录
        }

        // 使用当前时间戳创建文件名
        val currentTimestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(Date())
        val logFileName = "$LOG_FILE_NAME_PREFIX$currentTimestamp.txt"
        logFile = File(logDir, logFileName) // 设置日志文件路径
    }

    // 格式化时间戳
    private fun getCurrentTimeStamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    // 写入日志
    fun log(priority: Int, tag: String, message: String) {
        val fullMessage = "${getCurrentTimeStamp()} | $tag | $message"
        Log.println(priority, LOG_TAG, fullMessage)  // 控制台输出

        try {
            FileWriter(logFile, true).use { writer ->
                writer.append(fullMessage).append("\n")
            }
        } catch (e: IOException) {
            Log.e(LOG_TAG, "无法写入日志文件: ${e.message}")
        }
    }

    fun String.otherShow(context: Context) {
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, this, Toast.LENGTH_SHORT).show()
        }
    }

}

