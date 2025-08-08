package com.kopia.android.diagnostic

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Date
import java.util.Locale
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.kopia.android.R
import com.kopia.android.databinding.ActivityDiagnosticBinding
import com.kopia.android.util.PerformanceMonitor
import com.kopia.android.util.TestUtility
import com.kopia.android.util.WorkflowValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class DiagnosticActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticBinding
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var testUtility: TestUtility
    private lateinit var workflowValidator: WorkflowValidator
    private val handler = Handler(Looper.getMainLooper())
    private val logBuffer = StringBuilder()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        performanceMonitor = PerformanceMonitor.getInstance(this)
        testUtility = TestUtility.getInstance(this)
        workflowValidator = WorkflowValidator.getInstance(this)
        
        setupUI()
        displayDeviceInfo()
        startLogCollection()
    }
    
    private fun setupUI() {
        binding.runTestsButton.setOnClickListener {
            runTests()
        }
        
        binding.collectPerformanceButton.setOnClickListener {
            collectPerformanceData()
        }
        
        binding.validateWorkflowsButton.setOnClickListener {
            validateWorkflows()
        }
        
        binding.shareReportButton.setOnClickListener {
            shareReport()
        }
    }
    
    private fun displayDeviceInfo() {
        val deviceInfo = StringBuilder()
        deviceInfo.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        deviceInfo.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        deviceInfo.appendLine("Device ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        deviceInfo.appendLine("App Version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
        deviceInfo.appendLine("Memory Class: ${activityManager.memoryClass} MB")
        
        binding.deviceInfoTextView.text = deviceInfo.toString()
    }
    
    private val activityManager by lazy {
        getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    }
    
    private fun runTests() {
        binding.testResultsTextView.text = "Running tests..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            val testResults = testUtility.runAllTests()
            val report = testUtility.generateReport()
            
            withContext(Dispatchers.Main) {
                binding.testResultsTextView.text = report
                Toast.makeText(this@DiagnosticActivity, "Tests completed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun collectPerformanceData() {
        binding.performanceTextView.text = "Collecting performance data..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Log current memory and storage usage
            performanceMonitor.logMemoryUsage()
            performanceMonitor.logStorageUsage()
            
            // Generate performance report
            val report = performanceMonitor.generateReport()
            
            withContext(Dispatchers.Main) {
                binding.performanceTextView.text = report
                appendToLog("Performance data collected")
                Toast.makeText(this@DiagnosticActivity, "Performance data collected", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun validateWorkflows() {
        binding.workflowValidationTextView.text = "Validating workflows..."
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Validate all workflows
            val validationResults = workflowValidator.validateAllWorkflows()
            
            // Generate validation report
            val report = workflowValidator.generateReport()
            
            // Count successes and failures
            val totalTests = validationResults.size
            val passedTests = validationResults.count { it.value.success }
            
            withContext(Dispatchers.Main) {
                binding.workflowValidationTextView.text = report
                appendToLog("Validated workflows: $passedTests/$totalTests passed")
                Toast.makeText(this@DiagnosticActivity, "Workflow validation complete", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun startLogCollection() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec("logcat -v threadtime")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                
                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    if (line?.contains("kopia", ignoreCase = true) == true || 
                        line?.contains("com.kopia.android", ignoreCase = true) == true) {
                        logBuffer.appendLine(line)
                        
                        // Update UI periodically
                        handler.post {
                            binding.logOutputTextView.text = logBuffer.toString()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DiagnosticActivity", "Error collecting logs", e)
            }
        }
    }
    
    private fun appendToLog(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        binding.logOutputTextView.append(logEntry)
    }
    
    private fun shareReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Create a comprehensive report
                val report = StringBuilder()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                val timestamp = dateFormat.format(Date())
                
                report.appendLine("=== Kopia Android Diagnostic Report ===")
                report.appendLine("Generated: $timestamp")
                report.appendLine()
                
                // Device info
                report.appendLine("--- DEVICE INFORMATION ---")
                report.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                report.appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                report.appendLine("Device ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                report.appendLine("App Version: ${packageManager.getPackageInfo(packageName, 0).versionName}")
                report.appendLine("Memory Class: ${activityManager.memoryClass} MB")
                report.appendLine()
                
                // Workflow validation results
                report.appendLine("--- WORKFLOW VALIDATION RESULTS ---")
                withContext(Dispatchers.Main) {
                    report.appendLine(binding.workflowValidationTextView.text)
                }
                report.appendLine()
                
                // Test results
                report.appendLine("--- TEST RESULTS ---")
                withContext(Dispatchers.Main) {
                    report.appendLine(binding.testResultsTextView.text)
                }
                report.appendLine()
                
                // Performance data
                report.appendLine("--- PERFORMANCE DATA ---")
                withContext(Dispatchers.Main) {
                    report.appendLine(binding.performanceTextView.text)
                }
                report.appendLine()
                
                // Logs
                report.appendLine("--- APPLICATION LOGS ---")
                report.appendLine(logBuffer.toString())
                
                // Save report to file
                val reportFile = File(cacheDir, "kopia_android_report_$timestamp.txt")
                FileWriter(reportFile).use { it.write(report.toString()) }
                
                // Share the file
                val fileUri = FileProvider.getUriForFile(
                    this@DiagnosticActivity,
                    "${packageName}.fileprovider",
                    reportFile
                )
                
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Kopia Android Diagnostic Report")
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(shareIntent, "Share Report"))
                }
                
            } catch (e: Exception) {
                Log.e("DiagnosticActivity", "Error sharing report", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@DiagnosticActivity,
                        "Error sharing report: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DiagnosticActivity::class.java)
            context.startActivity(intent)
        }
    }
}
