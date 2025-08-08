package com.kopia.android.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.kopia.android.R
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class for handling errors and edge cases in the app.
 * Provides methods for logging, displaying, and reporting errors.
 */
class ErrorHandlingUtility(private val context: Context) {
    
    private val TAG = "ErrorHandlingUtility"
    private val errorLog = mutableListOf<ErrorEntry>()
    private val maxLogSize = 100
    
    /**
     * Log an error with optional user-friendly message
     * @param throwable The exception that occurred
     * @param userMessage Optional user-friendly message
     * @param severity Error severity level
     * @return Unique error ID for reference
     */
    fun logError(
        throwable: Throwable, 
        userMessage: String? = null, 
        severity: ErrorSeverity = ErrorSeverity.ERROR
    ): String {
        val errorId = UUID.randomUUID().toString().substring(0, 8)
        val timestamp = System.currentTimeMillis()
        
        // Create error entry
        val entry = ErrorEntry(
            id = errorId,
            timestamp = timestamp,
            exception = throwable,
            message = userMessage ?: throwable.message ?: "Unknown error",
            severity = severity
        )
        
        // Add to log, maintaining max size
        synchronized(errorLog) {
            errorLog.add(entry)
            if (errorLog.size > maxLogSize) {
                errorLog.removeAt(0)
            }
        }
        
        // Log to system
        when (severity) {
            ErrorSeverity.WARNING -> Log.w(TAG, "[$errorId] ${entry.message}", throwable)
            ErrorSeverity.ERROR -> Log.e(TAG, "[$errorId] ${entry.message}", throwable)
            ErrorSeverity.CRITICAL -> Log.wtf(TAG, "[$errorId] ${entry.message}", throwable)
        }
        
        // Write to error log file
        writeToErrorLog(entry)
        
        return errorId
    }
    
    /**
     * Display an error to the user
     * @param errorId Error ID from logError
     * @param view View to show Snackbar on, if null will use Toast
     */
    fun displayError(errorId: String, view: android.view.View? = null) {
        val entry = errorLog.find { it.id == errorId } ?: return
        
        if (view != null) {
            Snackbar.make(
                view,
                entry.message,
                when (entry.severity) {
                    ErrorSeverity.WARNING -> Snackbar.LENGTH_SHORT
                    else -> Snackbar.LENGTH_LONG
                }
            ).show()
        } else {
            Toast.makeText(
                context,
                entry.message,
                when (entry.severity) {
                    ErrorSeverity.WARNING -> Toast.LENGTH_SHORT
                    else -> Toast.LENGTH_LONG
                }
            ).show()
        }
    }
    
    /**
     * Show detailed error dialog for critical errors
     * @param errorId Error ID from logError
     */
    fun showErrorDialog(errorId: String) {
        val entry = errorLog.find { it.id == errorId } ?: return
        
        // Use a dialog only if we have an Activity context that can display windows
        val activity = (context as? android.app.Activity)
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            AlertDialog.Builder(activity)
                .setTitle(
                    when (entry.severity) {
                        ErrorSeverity.WARNING -> "Warning"
                        ErrorSeverity.ERROR -> "Error"
                        ErrorSeverity.CRITICAL -> "Critical Error"
                    }
                )
                .setMessage("${entry.message}\n\nError ID: $errorId")
                .setPositiveButton("OK", null)
                .setNeutralButton("Report") { _, _ ->
                    reportError(errorId)
                }
                .show()
        } else {
            // Fallback to Toast when we only have application context
            Toast.makeText(context, "${entry.message} (ID: $errorId)", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Get error details for a specific error
     * @param errorId Error ID from logError
     * @return Formatted error details or null if not found
     */
    fun getErrorDetails(errorId: String): String? {
        val entry = errorLog.find { it.id == errorId } ?: return null
        
        val sb = StringBuilder()
        sb.appendLine("Error ID: ${entry.id}")
        sb.appendLine("Time: ${formatTimestamp(entry.timestamp)}")
        sb.appendLine("Severity: ${entry.severity}")
        sb.appendLine("Message: ${entry.message}")
        sb.appendLine()
        sb.appendLine("Exception: ${entry.exception.javaClass.name}")
        sb.appendLine("Cause: ${entry.exception.cause?.javaClass?.name ?: "Unknown"}")
        sb.appendLine()
        sb.appendLine("Stack trace:")
        entry.exception.stackTrace.take(15).forEach { element ->
            sb.appendLine("  at $element")
        }
        
        return sb.toString()
    }
    
    /**
     * Report an error (e.g., via email or crash reporting service)
     * @param errorId Error ID from logError
     */
    fun reportError(errorId: String) {
        val errorDetails = getErrorDetails(errorId) ?: return
        
        // In a real app, this would send to a crash reporting service
        // For now, we'll create an email intent
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("support@example.com"))
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Kopia Android Error Report: $errorId")
            putExtra(android.content.Intent.EXTRA_TEXT, errorDetails)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        
        try {
            context.startActivity(
                android.content.Intent.createChooser(intent, "Send Error Report")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create error report email", e)
            Toast.makeText(context, "Could not create error report", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Check if an error is recoverable and attempt recovery
     * @param errorId Error ID from logError
     * @return true if recovery was attempted
     */
    fun attemptRecovery(errorId: String): Boolean {
        val entry = errorLog.find { it.id == errorId } ?: return false
        
        // Attempt different recovery strategies based on exception type
        return when {
            entry.exception is IOException -> {
                // For IO exceptions, we might try clearing cache
                val cacheDir = context.cacheDir
                var success = true
                cacheDir.listFiles()?.forEach { file ->
                    if (!file.name.contains("kopia")) {
                        success = success && (file.delete() || !file.exists())
                    }
                }
                success
            }
            entry.exception is OutOfMemoryError -> {
                // For OOM, try to free memory
                System.gc()
                true
            }
            entry.exception.message?.contains("permission", ignoreCase = true) == true -> {
                // For permission issues, we might guide the user
                showPermissionErrorHelp()
                true
            }
            else -> false
        }
    }
    
    /**
     * Show help for permission-related errors
     */
    private fun showPermissionErrorHelp() {
        AlertDialog.Builder(context)
            .setTitle("Permission Required")
            .setMessage("This app needs storage permissions to function properly. " +
                    "Please grant the requested permissions in the app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null)
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Write error entry to log file
     * @param entry Error entry to write
     */
    private fun writeToErrorLog(entry: ErrorEntry) {
        try {
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            val logFile = File(logDir, "error_log.txt")
            val writer = FileWriter(logFile, true)
            
            writer.use {
                it.write("${formatTimestamp(entry.timestamp)} [${entry.severity}] [${entry.id}] ${entry.message}\n")
                it.write("Exception: ${entry.exception.javaClass.name}: ${entry.exception.message}\n")
                entry.exception.stackTrace.take(5).forEach { element ->
                    it.write("  at $element\n")
                }
                it.write("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to error log", e)
        }
    }
    
    /**
     * Format timestamp for display
     * @param timestamp Timestamp in milliseconds
     * @return Formatted date/time string
     */
    private fun formatTimestamp(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return dateFormat.format(Date(timestamp))
    }
    
    /**
     * Get all errors matching a severity level
     * @param severity Severity level to filter by, or null for all
     * @return List of matching error entries
     */
    fun getErrors(severity: ErrorSeverity? = null): List<ErrorEntry> {
        return if (severity == null) {
            errorLog.toList()
        } else {
            errorLog.filter { it.severity == severity }
        }
    }
    
    /**
     * Clear error log
     */
    fun clearErrorLog() {
        synchronized(errorLog) {
            errorLog.clear()
        }
        
        try {
            val logFile = File(context.filesDir, "logs/error_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete error log file", e)
        }
    }
    
    /**
     * Error severity levels
     */
    enum class ErrorSeverity {
        WARNING,
        ERROR,
        CRITICAL
    }
    
    /**
     * Data class for error entries
     */
    data class ErrorEntry(
        val id: String,
        val timestamp: Long,
        val exception: Throwable,
        val message: String,
        val severity: ErrorSeverity
    )
    
    companion object {
        private var instance: ErrorHandlingUtility? = null
        
        @Synchronized
        fun getInstance(context: Context): ErrorHandlingUtility {
            if (instance == null) {
                instance = ErrorHandlingUtility(context.applicationContext)
            }
            return instance!!
        }
    }
}
