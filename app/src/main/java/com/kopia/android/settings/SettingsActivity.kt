package com.kopia.android.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kopia.android.R
import com.kopia.android.databinding.ActivitySettingsBinding
import com.kopia.android.repository.RepositoryManager
import com.kopia.android.util.FilesystemPermissionManager
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repositoryManager: RepositoryManager
    private var repositoryUri: Uri? = null
    
    // Shared preferences for storing settings
    private val sharedPrefs by lazy {
        getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
    }
    
    // Register for repository directory selection result
    private val selectRepositoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Persist permission to access this directory
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            repositoryUri = it
            binding.currentRepositoryTextView.text = "Repository: ${it.path}"
            
            // Save the repository URI to shared preferences
            sharedPrefs.edit().putString("repository_uri", it.toString()).apply()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repositoryManager = RepositoryManager(this)

        // Load saved settings
        loadSettings()

        // Update filesystem permission status
        updateFilesystemPermissionStatus()

        // Set up UI listeners
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // Update permission status when returning from settings
        updateFilesystemPermissionStatus()
    }

    private fun updateFilesystemPermissionStatus() {
        val hasAccess = FilesystemPermissionManager.hasFullFilesystemAccess(this)
        val statusMessage = FilesystemPermissionManager.getPermissionStatusMessage(this)

        binding.filesystemStatusTextView.text = statusMessage

        // Show/hide the permission request button based on current status
        if (hasAccess) {
            binding.requestFilesystemAccessButton.visibility = View.GONE
        } else {
            binding.requestFilesystemAccessButton.visibility = View.VISIBLE
        }
    }
    
    private fun loadSettings() {
        // Load server settings
        binding.serverPortEditText.setText(sharedPrefs.getString("server_port", "51515"))
        binding.insecureCheckBox.isChecked = sharedPrefs.getBoolean("allow_insecure", true)
        binding.autoStartCheckBox.isChecked = sharedPrefs.getBoolean("auto_start", true)
        
        // Load repository settings
        val repoUriString = sharedPrefs.getString("repository_uri", null)
        if (repoUriString != null) {
            try {
                repositoryUri = Uri.parse(repoUriString)
                binding.currentRepositoryTextView.text = "Repository: ${repositoryUri?.path}"
            } catch (e: Exception) {
                binding.currentRepositoryTextView.text = "No repository selected"
            }
        }
    }
    
    private fun setupListeners() {
        // Request filesystem access button
        binding.requestFilesystemAccessButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                FilesystemPermissionManager.requestFilesystemPermission(this)
                Toast.makeText(
                    this,
                    "Please enable 'Allow access to manage all files' in the next screen",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Your Android version has full filesystem access by default",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Select repository button
        binding.selectRepositoryButton.setOnClickListener {
            selectRepositoryLauncher.launch(null)
        }
        
        // Create repository button
        binding.createRepositoryButton.setOnClickListener {
            if (repositoryUri == null) {
                Toast.makeText(this, "Please select a repository location first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                val result = repositoryManager.initializeRepository(repositoryUri!!)
                result.fold(
                    onSuccess = { message ->
                        Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@SettingsActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        
        // Clear cache button
        binding.clearCacheButton.setOnClickListener {
            lifecycleScope.launch {
                clearCache()
                Toast.makeText(this@SettingsActivity, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Save settings button
        binding.saveSettingsButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun saveSettings() {
        sharedPrefs.edit().apply {
            putString("server_port", binding.serverPortEditText.text.toString())
            putBoolean("allow_insecure", binding.insecureCheckBox.isChecked)
            putBoolean("auto_start", binding.autoStartCheckBox.isChecked)
            if (repositoryUri != null) {
                putString("repository_uri", repositoryUri.toString())
            }
            apply()
        }
    }
    
    private fun clearCache() {
        // Clear temporary files but keep the kopia binary
        val kopiaFile = File(filesDir, "kopia")
        val kopiaExists = kopiaFile.exists()
        
        // Delete all files in the app's internal storage except the kopia binary
        filesDir.listFiles()?.forEach { file ->
            if (file.name != "kopia") {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
        }
        
        // Recreate necessary directories
        File(filesDir, "repo").mkdirs()
        File(filesDir, ".kopia").mkdirs()
    }
    
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SettingsActivity::class.java)
            context.startActivity(intent)
        }
    }
}
