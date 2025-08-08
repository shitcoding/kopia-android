package com.kopia.android.util

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.measureTimeMillis

/**
 * Utility class for monitoring app performance and resource usage.
 * Helps identify bottlenecks and optimize resource consumption.
 */
class PerformanceMonitor(private val context: Context) {
    
    private val TAG = "PerformanceMonitor"
    private val metrics = mutableMapOf<String, Long>()
    private val timings = mutableMapOf<String, MutableList<Long>>()
    
    /**
     * Measures execution time of a block of code
     * @param tag Identifier for the operation being measured
     * @param block Code block to execute and measure
     * @return Result of the code block execution
     */
    fun <T> measureOperation(tag: String, block: () -> T): T {
        var result: T
        val time = measureTimeMillis {
            result = block()
        }
        
        // Store timing data
        if (!timings.containsKey(tag)) {
            timings[tag] = mutableListOf()
        }
        timings[tag]?.add(time)
        
        Log.d(TAG, "Operation '$tag' took $time ms")
        return result
    }
    
    /**
     * Logs memory usage statistics
     */
    fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
        val availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB
        
        Log.d(TAG, "Memory Usage:")
        Log.d(TAG, "- Used Memory: $usedMemInMB MB")
        Log.d(TAG, "- Max Heap Size: $maxHeapSizeInMB MB")
        Log.d(TAG, "- Available Heap: $availHeapSizeInMB MB")
        
        // Native heap info (requires Debug.getNativeHeapSize API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nativeHeapSize = Debug.getNativeHeapSize() / 1048576L
            val nativeHeapFree = Debug.getNativeHeapFreeSize() / 1048576L
            val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize() / 1048576L
            
            Log.d(TAG, "- Native Heap Size: $nativeHeapSize MB")
            Log.d(TAG, "- Native Heap Free: $nativeHeapFree MB")
            Log.d(TAG, "- Native Heap Allocated: $nativeHeapAllocated MB")
        }
    }
    
    /**
     * Logs storage usage statistics
     */
    fun logStorageUsage() {
        // App-specific storage
        val internalDir = context.filesDir
        val internalStorageStats = getStorageStats(internalDir)
        
        Log.d(TAG, "Storage Usage:")
        Log.d(TAG, "- Internal App Storage: ${internalStorageStats.first} MB used of ${internalStorageStats.second} MB")
        
        // Check if external storage is available
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val externalStorageStats = getStorageStats(externalDir)
                Log.d(TAG, "- External App Storage: ${externalStorageStats.first} MB used of ${externalStorageStats.second} MB")
            }
        }
        
        // Log specific directory sizes
        val kopiaConfigDir = File(context.filesDir, ".kopia")
        if (kopiaConfigDir.exists()) {
            val kopiaConfigSize = getDirSize(kopiaConfigDir) / 1048576L
            Log.d(TAG, "- Kopia Config Directory: $kopiaConfigSize MB")
        }
        
        val repoDir = File(context.filesDir, "repo")
        if (repoDir.exists()) {
            val repoSize = getDirSize(repoDir) / 1048576L
            Log.d(TAG, "- Repository Directory: $repoSize MB")
        }
    }
    
    /**
     * Gets storage statistics for a directory
     * @param dir Directory to analyze
     * @return Pair of used space (MB) and total space (MB)
     */
    private fun getStorageStats(dir: File): Pair<Long, Long> {
        try {
            val stat = StatFs(dir.path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val totalBlocks = stat.blockCountLong
            
            val totalSpace = totalBlocks * blockSize / 1048576L
            val usedSpace = (totalBlocks - availableBlocks) * blockSize / 1048576L
            
            return Pair(usedSpace, totalSpace)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage stats", e)
            return Pair(0, 0)
        }
    }
    
    /**
     * Recursively calculates the size of a directory
     * @param dir Directory to measure
     * @return Size in bytes
     */
    private fun getDirSize(dir: File): Long {
        var size: Long = 0
        
        try {
            val files = dir.listFiles()
            if (files != null) {
                for (file in files) {
                    size += if (file.isDirectory) {
                        getDirSize(file)
                    } else {
                        file.length()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating directory size", e)
        }
        
        return size
    }
    
    /**
     * Generates a performance report with all collected metrics
     * @return Report as a formatted string
     */
    fun generateReport(): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        
        sb.appendLine("=== Kopia Android Performance Report ===")
        sb.appendLine("Generated: ${dateFormat.format(Date())}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()
        
        sb.appendLine("--- Operation Timings ---")
        timings.forEach { (operation, times) ->
            val avg = times.average()
            val min = times.minOrNull() ?: 0
            val max = times.maxOrNull() ?: 0
            sb.appendLine("$operation: avg=${avg.toInt()}ms, min=${min}ms, max=${max}ms, count=${times.size}")
        }
        sb.appendLine()
        
        // Add memory metrics
        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
        
        sb.appendLine("--- Memory Usage ---")
        sb.appendLine("Used Memory: $usedMemInMB MB")
        sb.appendLine("Max Heap Size: $maxHeapSizeInMB MB")
        sb.appendLine("Available Heap: ${maxHeapSizeInMB - usedMemInMB} MB")
        
        // Add storage metrics
        val internalDir = context.filesDir
        val internalStorageStats = getStorageStats(internalDir)
        
        sb.appendLine()
        sb.appendLine("--- Storage Usage ---")
        sb.appendLine("Internal App Storage: ${internalStorageStats.first} MB used of ${internalStorageStats.second} MB")
        
        return sb.toString()
    }
    
    /**
     * Resets all collected metrics
     */
    fun reset() {
        metrics.clear()
        timings.clear()
    }
    
    companion object {
        private var instance: PerformanceMonitor? = null
        
        @Synchronized
        fun getInstance(context: Context): PerformanceMonitor {
            if (instance == null) {
                instance = PerformanceMonitor(context.applicationContext)
            }
            return instance!!
        }
    }
}
