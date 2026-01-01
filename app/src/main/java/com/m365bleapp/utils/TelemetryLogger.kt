package com.m365bleapp.utils

import android.content.Context
import android.content.SharedPreferences
import com.m365bleapp.repository.MotorInfo
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger for telemetry data and BLE communication logs.
 * Stores data in CSV format for later analysis.
 */
class TelemetryLogger(private val context: Context) {
    private var currentSessionFile: File? = null
    private var currentBleLogFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val filenameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    
    private var isLogging = false
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "m365_logger_prefs"
        private const val KEY_LOGGING_ENABLED = "logging_enabled"
    }
    
    /**
     * Check if logging is globally enabled
     */
    fun isLoggingEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOGGING_ENABLED, true) // Default: enabled
    }
    
    /**
     * Set the global logging enabled state
     */
    fun setLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()
    }
    
    /**
     * Start a new logging session for both telemetry and BLE communication.
     * Will only start if logging is globally enabled.
     */
    fun startSession() {
        if (!isLoggingEnabled()) {
            return // Logging is disabled, don't create files
        }
        
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        
        val timestamp = filenameFormat.format(Date())
        
        // Telemetry log file
        val telemetryFilename = "m365_telemetry_${timestamp}.csv"
        currentSessionFile = File(dir, telemetryFilename)
        FileWriter(currentSessionFile, true).use { writer ->
            writer.append("Timestamp,Speed,Battery,Temperature,AvgSpeed,TripSeconds,TripMeters,RemainingKm,Mileage\n")
        }
        
        // BLE communication log file
        val bleFilename = "m365_ble_${timestamp}.csv"
        currentBleLogFile = File(dir, bleFilename)
        FileWriter(currentBleLogFile, true).use { writer ->
            writer.append("Timestamp,Direction,Type,Service,Characteristic,DataHex,DataLength,Description\n")
        }
        
        isLogging = true
    }

    /**
     * Log telemetry data (motor info)
     */
    fun log(info: MotorInfo) {
        val file = currentSessionFile ?: return
        if (!isLogging) return
        try {
            FileWriter(file, true).use { writer ->
                val line = "${dateFormat.format(Date())},${info.speed},${info.battery},${info.temp}," +
                        "${info.avgSpeed},${info.tripSeconds},${info.tripMeters},${info.remainingKm},${info.mileage}\n"
                writer.append(line)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Log BLE communication data
     * @param direction "TX" for sending, "RX" for receiving
     * @param type Message type (e.g., "UART", "AUTH", "CONTROL")
     * @param service UUID of the BLE service (can be abbreviated)
     * @param characteristic UUID of the characteristic (can be abbreviated)
     * @param data Raw byte data
     * @param description Optional human-readable description
     */
    fun logBle(
        direction: String,
        type: String,
        service: String,
        characteristic: String,
        data: ByteArray,
        description: String = ""
    ) {
        val file = currentBleLogFile ?: return
        if (!isLogging) return
        try {
            FileWriter(file, true).use { writer ->
                val dataHex = data.joinToString("") { "%02X".format(it) }
                val escapedDesc = description.replace(",", ";").replace("\n", " ")
                val line = "${dateFormat.format(Date())},$direction,$type,$service,$characteristic,$dataHex,${data.size},\"$escapedDesc\"\n"
                writer.append(line)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Log a command sent to the scooter
     */
    fun logCommand(commandName: String, data: ByteArray) {
        logBle("TX", "COMMAND", "UART", "TX", data, commandName)
    }
    
    /**
     * Log encrypted data sent via UART
     */
    fun logUartTx(data: ByteArray, description: String = "Encrypted UART TX") {
        logBle("TX", "UART_ENC", "UART", "TX", data, description)
    }
    
    /**
     * Log data received via UART
     */
    fun logUartRx(data: ByteArray, description: String = "UART RX") {
        logBle("RX", "UART", "UART", "RX", data, description)
    }
    
    /**
     * Log authentication data
     */
    fun logAuth(direction: String, characteristic: String, data: ByteArray, description: String = "") {
        logBle(direction, "AUTH", "AUTH", characteristic, data, description)
    }

    /**
     * Stop the current logging session
     */
    fun stopSession() {
        isLogging = false
        currentSessionFile = null
        currentBleLogFile = null
    }
    
    /**
     * Check if logging is currently active
     */
    fun isActive(): Boolean = isLogging
    
    /**
     * Get all log files sorted by modification time (newest first)
     */
    fun getLogFiles(): List<File> {
        val dir = File(context.filesDir, "logs")
        return dir.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    /**
     * Get only telemetry log files
     */
    fun getTelemetryLogFiles(): List<File> {
        return getLogFiles().filter { it.name.startsWith("m365_telemetry_") }
    }
    
    /**
     * Get only BLE communication log files
     */
    fun getBleLogFiles(): List<File> {
        return getLogFiles().filter { it.name.startsWith("m365_ble_") }
    }
    
    /**
     * Read the content of a log file
     */
    fun readLogFile(file: File): String {
        return try {
            file.readText()
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }
    
    /**
     * Delete a log file
     */
    fun deleteLogFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Delete all log files
     */
    fun deleteAllLogs() {
        val dir = File(context.filesDir, "logs")
        dir.listFiles()?.forEach { it.delete() }
    }
}
