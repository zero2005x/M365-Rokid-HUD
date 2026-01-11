package com.m365bleapp.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Utility class for exporting and sharing app logs.
 * 
 * Features:
 * - Compresses all log files (telemetry, BLE, etc.) into a ZIP archive
 * - Collects logcat output for debugging
 * - Shares the ZIP file via Android's share intent (Google Drive, OneDrive, etc.)
 * - Collects device and app info for debugging
 */
class LogExporter(private val context: Context) {
    
    companion object {
        private const val TAG = "LogExporter"
        private const val BUFFER_SIZE = 2048
        private const val LOGCAT_LINES = 2000 // Number of logcat lines to capture
    }
    
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    
    /**
     * Export result containing the ZIP file and any error message
     */
    data class ExportResult(
        val success: Boolean,
        val zipFile: File? = null,
        val errorMessage: String? = null,
        val fileCount: Int = 0,
        val totalSize: Long = 0
    )
    
    /**
     * Creates a ZIP archive containing all log files.
     * 
     * @param includeLogcat Whether to include logcat output in the archive
     * @param includeDeviceInfo Whether to include device information
     * @return ExportResult with the created ZIP file or error message
     */
    fun createLogArchive(
        includeLogcat: Boolean = true,
        includeDeviceInfo: Boolean = true
    ): ExportResult {
        try {
            val logsDir = File(context.filesDir, "logs")
            val exportDir = File(context.cacheDir, "exports")
            
            // Create export directory if it doesn't exist
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            
            // Clean up old exports (keep only the latest 5)
            cleanupOldExports(exportDir, keepCount = 5)
            
            val timestamp = dateFormat.format(Date())
            val zipFileName = "m365_logs_$timestamp.zip"
            val zipFile = File(exportDir, zipFileName)
            
            var fileCount = 0
            var totalSize = 0L
            
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                // Add all log files from the logs directory
                if (logsDir.exists() && logsDir.isDirectory) {
                    logsDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zos, file, "logs/${file.name}")
                            fileCount++
                            totalSize += file.length()
                        }
                    }
                }
                
                // Add logcat output if requested
                if (includeLogcat) {
                    val logcatContent = captureLogcat()
                    if (logcatContent.isNotEmpty()) {
                        addStringToZip(zos, logcatContent, "logcat.txt")
                        fileCount++
                        totalSize += logcatContent.length
                    }
                }
                
                // Add device info if requested
                if (includeDeviceInfo) {
                    val deviceInfo = collectDeviceInfo()
                    addStringToZip(zos, deviceInfo, "device_info.txt")
                    fileCount++
                    totalSize += deviceInfo.length
                }
            }
            
            Log.i(TAG, "Log archive created: ${zipFile.absolutePath}, files: $fileCount, size: ${zipFile.length()} bytes")
            
            return ExportResult(
                success = true,
                zipFile = zipFile,
                fileCount = fileCount,
                totalSize = totalSize
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log archive", e)
            return ExportResult(
                success = false,
                errorMessage = e.message ?: "Unknown error creating archive"
            )
        }
    }
    
    /**
     * Share the log archive using Android's share intent.
     * This allows the user to choose where to send the file (Google Drive, OneDrive, Email, etc.)
     * 
     * @param zipFile The ZIP file to share
     * @return Intent for sharing, or null if sharing is not possible
     */
    fun createShareIntent(zipFile: File): Intent? {
        return try {
            // Get the content URI for the file using FileProvider
            // Note: Authority must match the one defined in AndroidManifest.xml
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "com.m365bleapp.provider",
                zipFile
            )
            
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "M365 HUD Logs - ${dateFormat.format(Date())}")
                putExtra(Intent.EXTRA_TEXT, buildShareMessage())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create share intent", e)
            null
        }
    }
    
    /**
     * Export logs and immediately create a share intent.
     * Combines createLogArchive and createShareIntent for convenience.
     * 
     * @return Pair of ExportResult and Intent (Intent may be null if export failed)
     */
    fun exportAndShare(
        includeLogcat: Boolean = true,
        includeDeviceInfo: Boolean = true
    ): Pair<ExportResult, Intent?> {
        val result = createLogArchive(includeLogcat, includeDeviceInfo)
        val intent = result.zipFile?.let { createShareIntent(it) }
        return Pair(result, intent)
    }
    
    /**
     * Get total size of all log files
     */
    fun getLogsTotalSize(): Long {
        val logsDir = File(context.filesDir, "logs")
        return logsDir.listFiles()?.sumOf { it.length() } ?: 0
    }
    
    /**
     * Get number of log files
     */
    fun getLogsCount(): Int {
        val logsDir = File(context.filesDir, "logs")
        return logsDir.listFiles()?.size ?: 0
    }
    
    /**
     * Format file size to human-readable string
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    // ========== Private Helper Methods ==========
    
    /**
     * Add a file to the ZIP archive
     */
    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName)
        entry.time = file.lastModified()
        zos.putNextEntry(entry)
        
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { bis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var count: Int
            while (bis.read(buffer, 0, BUFFER_SIZE).also { count = it } != -1) {
                zos.write(buffer, 0, count)
            }
        }
        
        zos.closeEntry()
    }
    
    /**
     * Add a string content to the ZIP archive as a file
     */
    private fun addStringToZip(zos: ZipOutputStream, content: String, entryName: String) {
        val entry = ZipEntry(entryName)
        entry.time = System.currentTimeMillis()
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    
    /**
     * Capture logcat output for debugging
     */
    private fun captureLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", LOGCAT_LINES.toString(), "-v", "time")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture logcat", e)
            "Failed to capture logcat: ${e.message}"
        }
    }
    
    /**
     * Collect device and app information for debugging
     */
    private fun collectDeviceInfo(): String {
        val sb = StringBuilder()
        
        sb.appendLine("===== M365 HUD - Device Information =====")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine()
        
        // App Info
        sb.appendLine("=== App Info ===")
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            sb.appendLine("Package: ${context.packageName}")
            sb.appendLine("Version Name: ${packageInfo.versionName}")
            sb.appendLine("Version Code: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode}")
        } catch (e: Exception) {
            sb.appendLine("Error getting app info: ${e.message}")
        }
        sb.appendLine()
        
        // Device Info
        sb.appendLine("=== Device Info ===")
        sb.appendLine("Brand: ${Build.BRAND}")
        sb.appendLine("Model: ${Build.MODEL}")
        sb.appendLine("Device: ${Build.DEVICE}")
        sb.appendLine("Product: ${Build.PRODUCT}")
        sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("Hardware: ${Build.HARDWARE}")
        sb.appendLine()
        
        // Android Info
        sb.appendLine("=== Android Info ===")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE}")
        sb.appendLine("SDK Level: ${Build.VERSION.SDK_INT}")
        sb.appendLine("Build ID: ${Build.ID}")
        sb.appendLine("Build Type: ${Build.TYPE}")
        sb.appendLine()
        
        // Memory Info
        sb.appendLine("=== Memory Info ===")
        val runtime = Runtime.getRuntime()
        sb.appendLine("Max Memory: ${formatFileSize(runtime.maxMemory())}")
        sb.appendLine("Total Memory: ${formatFileSize(runtime.totalMemory())}")
        sb.appendLine("Free Memory: ${formatFileSize(runtime.freeMemory())}")
        sb.appendLine("Used Memory: ${formatFileSize(runtime.totalMemory() - runtime.freeMemory())}")
        sb.appendLine()
        
        // Storage Info
        sb.appendLine("=== Storage Info ===")
        val logsDir = File(context.filesDir, "logs")
        sb.appendLine("Logs Directory: ${logsDir.absolutePath}")
        sb.appendLine("Logs Count: ${getLogsCount()}")
        sb.appendLine("Logs Total Size: ${formatFileSize(getLogsTotalSize())}")
        sb.appendLine()
        
        // Log Files List
        sb.appendLine("=== Log Files ===")
        logsDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            val modDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
            sb.appendLine("${file.name} | ${formatFileSize(file.length())} | $modDate")
        }
        
        return sb.toString()
    }
    
    /**
     * Build the share message body
     */
    private fun buildShareMessage(): String {
        return """
            M365 HUD App Logs
            
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            
            This archive contains:
            - Telemetry logs (speed, battery, temperature data)
            - BLE communication logs
            - Logcat output
            - Device information
            
            Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}
        """.trimIndent()
    }
    
    /**
     * Clean up old export files to save space
     */
    private fun cleanupOldExports(exportDir: File, keepCount: Int) {
        try {
            val files = exportDir.listFiles { file -> file.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?: return
            
            if (files.size > keepCount) {
                files.drop(keepCount).forEach { file ->
                    Log.d(TAG, "Deleting old export: ${file.name}")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old exports", e)
        }
    }
}
