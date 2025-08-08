package com.kopia.android.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Utility class for optimizing app performance on different devices.
 * Provides methods to optimize memory usage, disk operations, and WebView rendering.
 */
class OptimizationUtility(private val context: Context) {
    
    private val TAG = "OptimizationUtility"
    private val backgroundExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * Optimize WebView performance
     * @param webView WebView to optimize
     */
    fun optimizeWebView(webView: WebView) {
        // Apply WebView optimizations
        webView.settings.apply {
            // Set cache mode based on device capabilities
            if (isLowEndDevice()) {
                // Modern alternative to setAppCacheEnabled(false)
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                domStorageEnabled = false
            } else {
                // Modern alternative to setAppCacheEnabled(true)
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                domStorageEnabled = true
            }
            
            // Optimize rendering
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            
            // Adjust text size for better readability on different devices
            textZoom = calculateOptimalTextZoom()
            
            // Disable features that might not be needed
            blockNetworkImage = false
            loadsImagesAutomatically = true
            
            // Set reasonable DOM storage limits
            domStorageEnabled = true
            databaseEnabled = true
            
            // Set bitmap quality based on device capabilities
            if (isLowEndDevice()) {
                // Use lower quality for low-end devices
                webView.setInitialScale(90)
            }
        }
        
        Log.d(TAG, "WebView optimizations applied")
    }
    
    /**
     * Clean up temporary files to free up storage space
     * @param maxAge Maximum age of files to keep (in milliseconds)
     */
    fun cleanupTemporaryFiles(maxAge: Long = 24 * 60 * 60 * 1000) {
        backgroundExecutor.execute {
            try {
                val cacheDir = context.cacheDir
                val tempFiles = cacheDir.listFiles { file ->
                    // Keep only files older than maxAge and not essential
                    val isOld = System.currentTimeMillis() - file.lastModified() > maxAge
                    val isNotEssential = !file.name.contains("kopia") && !file.name.contains(".config")
                    isOld && isNotEssential
                }
                
                var bytesFreed = 0L
                tempFiles?.forEach { file ->
                    if (file.isFile) {
                        bytesFreed += file.length()
                        file.delete()
                    }
                }
                
                Log.d(TAG, "Cleaned up ${tempFiles?.size ?: 0} temporary files, freed ${bytesFreed / 1024} KB")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up temporary files", e)
            }
        }
    }
    
    /**
     * Optimize bitmap memory usage
     * @param bitmap Bitmap to optimize
     * @return Optimized bitmap
     */
    fun optimizeBitmap(bitmap: Bitmap): Bitmap {
        // For low-end devices, scale down large bitmaps
        if (isLowEndDevice() && (bitmap.width > 1024 || bitmap.height > 1024)) {
            val scaleFactor = 0.75f
            val newWidth = (bitmap.width * scaleFactor).toInt()
            val newHeight = (bitmap.height * scaleFactor).toInt()
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
        return bitmap
    }
    
    /**
     * Optimize Kopia server parameters based on device capabilities
     * @return Map of optimized parameters
     */
    fun getOptimizedKopiaParameters(): Map<String, String> {
        val params = mutableMapOf<String, String>()
        
        // Set cache size based on device memory
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        
        if (memoryClass <= 128) {
            // Low memory device
            params["KOPIA_CACHE_DIRECTORY_SIZE_MB"] = "100"
            params["KOPIA_CONTENT_CACHE_SIZE_MB"] = "50"
            params["KOPIA_MAX_DOWNLOAD_SPEED_MBPS"] = "5"
        } else if (memoryClass <= 256) {
            // Medium memory device
            params["KOPIA_CACHE_DIRECTORY_SIZE_MB"] = "200"
            params["KOPIA_CONTENT_CACHE_SIZE_MB"] = "100"
            params["KOPIA_MAX_DOWNLOAD_SPEED_MBPS"] = "10"
        } else {
            // High memory device
            params["KOPIA_CACHE_DIRECTORY_SIZE_MB"] = "500"
            params["KOPIA_CONTENT_CACHE_SIZE_MB"] = "250"
            params["KOPIA_MAX_DOWNLOAD_SPEED_MBPS"] = "20"
        }
        
        return params
    }
    
    /**
     * Schedule periodic optimization tasks
     */
    fun schedulePeriodicOptimizations() {
        // Schedule cleanup every 6 hours
        val cleanupRunnable = object : Runnable {
            override fun run() {
                cleanupTemporaryFiles()
                mainHandler.postDelayed(this, 6 * 60 * 60 * 1000)
            }
        }
        
        mainHandler.post(cleanupRunnable)
        Log.d(TAG, "Scheduled periodic optimizations")
    }
    
    /**
     * Determine if this is a low-end device
     * @return true if device is considered low-end
     */
    private fun isLowEndDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val memoryClass = activityManager.memoryClass
        val isLowRam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activityManager.isLowRamDevice
        } else {
            memoryClass <= 64
        }
        
        return isLowRam || memoryClass <= 128
    }
    
    /**
     * Calculate optimal text zoom based on device display density
     * @return text zoom percentage
     */
    private fun calculateOptimalTextZoom(): Int {
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        
        return when {
            density >= 3.0 -> 100  // xxxhdpi
            density >= 2.0 -> 100  // xxhdpi
            density >= 1.5 -> 110  // hdpi
            density >= 1.0 -> 120  // mdpi
            else -> 130            // ldpi
        }
    }
    
    companion object {
        private var instance: OptimizationUtility? = null
        
        @Synchronized
        fun getInstance(context: Context): OptimizationUtility {
            if (instance == null) {
                instance = OptimizationUtility(context.applicationContext)
            }
            return instance!!
        }
    }
}
