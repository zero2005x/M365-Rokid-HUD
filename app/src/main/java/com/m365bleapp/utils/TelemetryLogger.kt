package com.m365bleapp.utils

import android.content.Context
import com.m365bleapp.repository.MotorInfo
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TelemetryLogger(private val context: Context) {
    private var currentSessionFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val filenameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun startSession() {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        
        val filename = "m365_log_${filenameFormat.format(Date())}.csv"
        currentSessionFile = File(dir, filename)
        
        FileWriter(currentSessionFile, true).use { writer ->
            writer.append("Timestamp,Speed,Battery,Temp\n")
        }
    }

    fun log(info: MotorInfo) {
        val file = currentSessionFile ?: return
        try {
            FileWriter(file, true).use { writer ->
                val line = "${dateFormat.format(Date())},${info.speed},${info.battery},${info.temp}\n"
                writer.append(line)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSession() {
        currentSessionFile = null
    }
    
    fun getLogFiles(): List<File> {
        val dir = File(context.filesDir, "logs")
        return dir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
