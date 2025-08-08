package com.kopia.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kopia.android.MainActivity
import com.kopia.android.R
import com.kopia.android.util.OptimizationUtility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class KopiaServerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var kopiaProcess: Process? = null
    private var serverPort = 51515
    private val notificationId = 1001
    private val channelId = "kopia_server_channel"
    private var allowInsecure = true
    private lateinit var optimizationUtility: OptimizationUtility

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("KopiaServer", "Service onCreate()")
        createNotificationChannel()
        optimizationUtility = OptimizationUtility.getInstance(this)
    }

    private fun validateElfExecutable(path: String): Boolean {
        // Minimal ELF sanity checks: magic, class=ELF64, machine=AARCH64(183), type=ET_DYN(3) -> PIE
        val f = File(path)
        if (!f.exists() || f.length() < 0x38) return false
        return try {
            f.inputStream().use { ins ->
                val hdr = ByteArray(64)
                if (ins.read(hdr) < 64) return false
                // 0..3: 0x7F 'E' 'L' 'F'
                val magicOk = hdr[0] == 0x7F.toByte() && hdr[1] == 'E'.code.toByte() && hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte()
                val elfClass = hdr[4].toInt() // 2 => 64-bit
                val dataLE = hdr[5].toInt() // 1 => little-endian
                // e_type at 0x10 (16) little-endian
                fun u16(off: Int): Int = (hdr[off].toInt() and 0xFF) or ((hdr[off+1].toInt() and 0xFF) shl 8)
                fun u16be(off: Int): Int = ((hdr[off].toInt() and 0xFF) shl 8) or (hdr[off+1].toInt() and 0xFF)
                val eType = if (dataLE == 1) u16(16) else u16be(16)
                // e_machine at 0x12 (18)
                val eMachine = if (dataLE == 1) u16(18) else u16be(18)
                val ok = magicOk && elfClass == 2 && eMachine == 183 && (eType == 3)
                if (!ok) {
                    android.util.Log.e("KopiaServer", "ELF check failed: magic=$magicOk class=$elfClass eMachine=$eMachine eType=$eType (expect class=2, eMachine=183 AARCH64, eType=3 PIE)")
                }
                ok
            }
        } catch (t: Throwable) {
            android.util.Log.e("KopiaServer", "ELF validation error", t)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("KopiaServer", "onStartCommand() called: intent=$intent flags=$flags startId=$startId")
        val notification = createNotification("Kopia Server is starting")
        startForeground(notificationId, notification)

        // Get server port if provided
        serverPort = intent?.getIntExtra("SERVER_PORT", 51515) ?: 51515
        android.util.Log.d("KopiaServer", "Using serverPort=$serverPort")
        
        // Get security settings
        allowInsecure = intent?.getBooleanExtra("ALLOW_INSECURE", true) ?: true
        android.util.Log.d("KopiaServer", "allowInsecure=$allowInsecure")

        // Get repository URI if provided
        val repositoryUriString = intent?.getStringExtra("REPOSITORY_URI")
        val repositoryUri = if (repositoryUriString != null) Uri.parse(repositoryUriString) else null
        android.util.Log.d("KopiaServer", "repositoryUri=$repositoryUri")

        serviceScope.launch {
            android.util.Log.d("KopiaServer", "Launching startKopiaServer() coroutine")
            startKopiaServer(repositoryUri)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Kopia Server"
            val descriptionText = "Kopia Server running in background"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kopia Server")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startKopiaServer(repositoryUri: Uri?) {
        try {
            val kopiaPath = ensureExecutableBinaryPath()
            android.util.Log.d("KopiaServer", "kopiaPath=$kopiaPath exists=${File(kopiaPath).exists()} size=${File(kopiaPath).length()}")
            if (!validateElfExecutable(kopiaPath)) {
                android.util.Log.e("KopiaServer", "Kopia binary is not a valid Android ARM64 PIE executable. Aborting start.")
                stopSelf()
                return
            }
            
            // Ensure necessary directories exist
            val repoDir = File(filesDir, "repo")
            if (!repoDir.exists()) {
                repoDir.mkdirs()
            }
            
            val configDir = File(filesDir, ".kopia")
            if (!configDir.exists()) {
                configDir.mkdirs()
            }

            // Ensure repository is initialized (v0.21.1 requires a repo for server UI)
            val repoConfig = File(configDir, "repository.config")
            // Determine repository password:
            // Prefer password persisted by Kopia in repository.config.kopia-password,
            // then a user-provided fallback file password.txt, else default to "android".
            val kopiaPasswordFile = File(configDir, "repository.config.kopia-password")
            val fallbackPasswordFile = File(configDir, "password.txt")
            val repoPassword: String = try {
                when {
                    kopiaPasswordFile.exists() -> {
                        android.util.Log.d("KopiaServer", "Using password from repository.config.kopia-password")
                        kopiaPasswordFile.readText().trim()
                    }
                    fallbackPasswordFile.exists() -> {
                        android.util.Log.d("KopiaServer", "Using password from password.txt")
                        fallbackPasswordFile.readText().trim()
                    }
                    else -> {
                        android.util.Log.d("KopiaServer", "Using default repository password")
                        "android"
                    }
                }
            } catch (_: Throwable) {
                android.util.Log.d("KopiaServer", "Falling back to default repository password due to read error")
                "android"
            }
            fun runKopia(args: List<String>): Pair<Int, String> {
                val pb = ProcessBuilder(listOf(kopiaPath) + args).apply {
                    redirectErrorStream(true)
                    environment().apply {
                        put("HOME", filesDir.absolutePath)
                        put("KOPIA_CONFIG_PATH", File(configDir, "repository.config").absolutePath)
                        put("KOPIA_PASSWORD", repoPassword)
                    }
                }
                android.util.Log.d("KopiaServer", "Exec: ${listOf(kopiaPath)+args}")
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                val exit = p.waitFor()
                android.util.Log.d("KopiaServer", "Exit=$exit Output=${out.take(500)}")
                return exit to out
            }

            if (!repoConfig.exists()) {
                android.util.Log.d("KopiaServer", "No repository.config; checking status")
                val (stExit, _) = runKopia(listOf("repository", "status"))
                if (stExit != 0) {
                    val hasData = repoDir.exists() && (repoDir.listFiles()?.isNotEmpty() == true)
                    if (hasData) {
                        android.util.Log.d("KopiaServer", "Existing data detected in ${repoDir.absolutePath}, attempting connect")
                        val (cxExit, _) = runKopia(listOf("repository", "connect", "filesystem", "--path=${repoDir.absolutePath}"))
                        if (cxExit != 0 && !repoConfig.exists()) {
                            android.util.Log.d("KopiaServer", "Connect failed, will try create new repository")
                            val (crExit, _) = runKopia(listOf("repository", "create", "filesystem", "--path=${repoDir.absolutePath}"))
                            if (crExit != 0 && !repoConfig.exists()) {
                                android.util.Log.e("KopiaServer", "Repository create failed. Aborting.")
                                stopSelf(); return
                            }
                        }
                    } else {
                        android.util.Log.d("KopiaServer", "No existing data, creating new repository at ${repoDir.absolutePath}")
                        val (crExit, _) = runKopia(listOf("repository", "create", "filesystem", "--path=${repoDir.absolutePath}"))
                        if (crExit != 0 && !repoConfig.exists()) {
                            android.util.Log.e("KopiaServer", "Repository create failed. Aborting.")
                            stopSelf(); return
                        }
                    }
                }
            }
            
            // Build the command to start Kopia server
            val commandList = mutableListOf(
                kopiaPath,
                "server",
                "start",
                "--address=127.0.0.1:$serverPort"
            )
            
            // Add security options
            if (allowInsecure) {
                commandList.add("--insecure")
                // Set explicit UI credentials instead of disabling password
                commandList.addAll(listOf("--server-username=admin", "--server-password=admin"))
            }
            
            // Start the process
            val processBuilder = ProcessBuilder(commandList)
            processBuilder.redirectErrorStream(true)
            // Get optimized parameters for Kopia based on device capabilities
            val optimizedParams = optimizationUtility.getOptimizedKopiaParameters()
            
            processBuilder.environment().apply {
                put("HOME", filesDir.absolutePath)
                put("KOPIA_CONFIG_PATH", File(configDir, "repository.config").absolutePath)
                // Use the resolved password to open repo non-interactively
                put("KOPIA_PASSWORD", repoPassword)
                
                // Apply optimized parameters
                optimizedParams.forEach { (key, value) ->
                    put(key, value)
                }
            }
            
            // Log the command being executed
            android.util.Log.d("KopiaServer", "Starting Kopia server with command: ${commandList.joinToString(" ")}")
            
            kopiaProcess = processBuilder.start()
            android.util.Log.d("KopiaServer", "kopiaProcess started pid=${kopiaProcess}")
            
            // Update notification with port information
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = createNotification("Kopia Server running on port $serverPort")
            notificationManager.notify(notificationId, notification)
            
            // Read the output for logging purposes
            val inputStream = kopiaProcess?.inputStream
            inputStream?.bufferedReader()?.forEachLine { line ->
                // Log or handle output as needed
                android.util.Log.d("KopiaServer", line)
            }
        } catch (e: IOException) {
            android.util.Log.e("KopiaServer", "Failed to start Kopia server on first attempt", e)
            // Handle permission denied by retrying from codeCacheDir
            if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                try {
                    val altPath = ensureExecutableBinaryPath(useCodeCache = true)
                    android.util.Log.d("KopiaServer", "Retrying with alt executable at $altPath")
                    val commandList = mutableListOf(
                        altPath,
                        "server",
                        "start",
                        "--address=127.0.0.1:$serverPort"
                    ).apply {
                        if (allowInsecure) { add("--insecure"); add("--without-password") }
                    }
                    val processBuilder = ProcessBuilder(commandList).apply {
                        redirectErrorStream(true)
                        environment().apply {
                            put("HOME", filesDir.absolutePath)
                            put("KOPIA_CONFIG_PATH", File(filesDir, ".kopia").absolutePath)
                            optimizationUtility.getOptimizedKopiaParameters().forEach { (k,v) -> put(k,v) }
                        }
                    }
                    android.util.Log.d("KopiaServer", "Starting Kopia server (retry) with command: ${commandList.joinToString(" ")}")
                    kopiaProcess = processBuilder.start()
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(notificationId, createNotification("Kopia Server running on port $serverPort"))
                    kopiaProcess?.inputStream?.bufferedReader()?.forEachLine { line -> android.util.Log.d("KopiaServer", line) }
                    return
                } catch (ee: IOException) {
                    android.util.Log.e("KopiaServer", "Retry start failed", ee)
                }
            }
            stopSelf()
        }
    }

    private fun ensureExecutableBinaryPath(useCodeCache: Boolean = false): String {
        // 1) Prefer binary packaged in nativeLibraryDir (exec allowed)
        try {
            val libDir = applicationInfo.nativeLibraryDir
            android.util.Log.d("KopiaServer", "nativeLibraryDir=$libDir")
            if (!libDir.isNullOrBlank()) {
                try {
                    val names = File(libDir).list()?.joinToString(", ") ?: "<empty>"
                    android.util.Log.d("KopiaServer", "nativeLibraryDir contents: $names")
                } catch (_: Throwable) { }
                val candidates = listOf(
                    File(libDir, "kopia"),
                    File(libDir, "libkopia.so")
                )
                candidates.firstOrNull { it.exists() }?.let { found ->
                    android.util.Log.d("KopiaServer", "Using nativeLibraryDir binary: ${found.absolutePath}")
                    return found.absolutePath
                }
            }
        } catch (_: Throwable) { /* ignore */ }

        // 2) Then try app filesDir (legacy approach)
        val src = File(filesDir, "kopia")
        if (!useCodeCache) {
            try {
                if (src.exists()) {
                    src.setExecutable(true, true)
                    return src.absolutePath
                }
            } catch (_: Throwable) { /* ignore */ }
        }

        // 3) Fallback: copy to codeCacheDir and chmod 700
        val dst = File(codeCacheDir, "kopia")
        try {
            if (src.exists() && (!dst.exists() || dst.length() != src.length())) {
                src.inputStream().use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
            }
            if (dst.exists()) {
                dst.setReadable(true, true)
                dst.setExecutable(true, true)
                try { ProcessBuilder("/system/bin/chmod", "700", dst.absolutePath).start().waitFor() } catch (_: Exception) {}
            }
        } catch (t: Throwable) {
            android.util.Log.e("KopiaServer", "Failed preparing executable in codeCacheDir", t)
        }
        return dst.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("KopiaServer", "Stopping Kopia server")
        kopiaProcess?.destroy()
        kopiaProcess = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
