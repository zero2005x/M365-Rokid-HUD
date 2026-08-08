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
    // log()/logBle() are called from BLE callback and worker threads while
    // startSession()/stopSession() run on other threads. @Volatile gives
    // visibility; writeLock serialises the actual file writes and the session
    // state transitions so lines cannot interleave or land in a replaced file.
    @Volatile private var currentSessionFile: File? = null
    @Volatile private var currentBleLogFile: File? = null
    @Volatile private var isLogging = false

    private val writeLock = Any()

    // SimpleDateFormat is not thread-safe: a shared instance produces corrupted
    // timestamps (or throws) under concurrent formatting. One instance per
    // thread avoids that without a lock on the hot path.
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val filenameFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private fun timestamp(): String = dateFormat.get()!!.format(Date())

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
    fun startSession() = synchronized(writeLock) {
        if (!isLoggingEnabled()) {
            return // Logging is disabled, don't create files
        }

        // Re-entrancy guard: calling this twice appended a second header to the
        // same file (filenames only have second resolution) and abandoned the
        // in-progress session.
        if (isLogging) {
            return
        }

        val dir = File(context.filesDir, "logs")
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.e("TelemetryLogger", "Could not create log directory: $dir")
            return
        }

        val stamp = filenameFormat.get()!!.format(Date())

        try {
            // Telemetry log file
            val telemetryFile = File(dir, "m365_telemetry_${stamp}.csv")
            FileWriter(telemetryFile, true).use { writer ->
                writer.append("Timestamp,Speed,Battery,Temperature,AvgSpeed,TripSeconds,TripMeters,RemainingKm,Mileage\n")
            }

            // BLE communication log file
            val bleFile = File(dir, "m365_ble_${stamp}.csv")
            FileWriter(bleFile, true).use { writer ->
                writer.append("Timestamp,Direction,Type,Service,Characteristic,DataHex,DataLength,Description\n")
            }

            // Only publish the session once both files exist, so a failure
            // cannot leave a half-initialised logging state behind.
            currentSessionFile = telemetryFile
            currentBleLogFile = bleFile
            isLogging = true
        } catch (e: Exception) {
            android.util.Log.e("TelemetryLogger", "Could not start logging session", e)
            currentSessionFile = null
            currentBleLogFile = null
            isLogging = false
        }
    }

    /**
     * Log telemetry data (motor info)
     */
    fun log(info: MotorInfo) {
        // Format outside the lock; only the write is serialised.
        val line = "${timestamp()},${info.speed},${info.battery},${info.temp}," +
                "${info.avgSpeed},${info.tripSeconds},${info.tripMeters},${info.remainingKm},${info.mileage}\n"

        synchronized(writeLock) {
            val file = currentSessionFile ?: return
            if (!isLogging) return
            try {
                FileWriter(file, true).use { writer -> writer.append(line) }
            } catch (e: Exception) {
                android.util.Log.w("TelemetryLogger", "Telemetry log write failed", e)
            }
        }
    }

    /**
     * Escapes a value for CSV: wraps it in quotes and doubles any embedded
     * quotes (RFC 4180). Only stripping commas and newlines left embedded `"`
     * free to break the row structure and corrupt every following row.
     */
    private fun csvEscape(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
    
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
        val dataHex = data.joinToString("") { "%02X".format(it) }
        val line = "${timestamp()},${csvEscape(direction)},${csvEscape(type)}," +
                "${csvEscape(service)},${csvEscape(characteristic)},$dataHex,${data.size}," +
                "${csvEscape(description)}\n"

        synchronized(writeLock) {
            val file = currentBleLogFile ?: return
            if (!isLogging) return
            try {
                FileWriter(file, true).use { writer -> writer.append(line) }
            } catch (e: Exception) {
                android.util.Log.w("TelemetryLogger", "BLE log write failed", e)
            }
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
    fun stopSession() = synchronized(writeLock) {
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
