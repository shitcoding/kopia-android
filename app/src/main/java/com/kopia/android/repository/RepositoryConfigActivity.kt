package com.kopia.android.repository

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kopia.android.MainActivity
import com.kopia.android.R
import com.kopia.android.databinding.ActivityRepositoryConfigBinding
import com.kopia.android.service.ServerStatusManager
import com.kopia.android.util.FileUtils
import com.kopia.android.util.ProgressManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * Activity for configuring a new Kopia repository.
 * Collects repository parameters (password, name, location) before creation.
 */
class RepositoryConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRepositoryConfigBinding
    private lateinit var progressManager: ProgressManager
    private lateinit var serverStatusManager: ServerStatusManager
    private var repositoryUri: Uri? = null
    private var repositoryPath: String? = null
    private var isExistingRepository: Boolean = false

    // Shared preferences for storing settings
    private val sharedPrefs by lazy {
        getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRepositoryConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        progressManager = ProgressManager(this)
        serverStatusManager = ServerStatusManager.getInstance(this)

        // Initialize server status as disconnected (no server running yet)
        serverStatusManager.setStatus(ServerStatusManager.ServerStatus.DISCONNECTED)
        updateServerStatusUI(ServerStatusManager.ServerStatus.DISCONNECTED)

        // Get repository location and type from intent
        val uriString = intent.getStringExtra(EXTRA_REPOSITORY_URI)
        isExistingRepository = intent.getBooleanExtra(EXTRA_IS_EXISTING, false)

        if (uriString != null) {
            repositoryUri = Uri.parse(uriString)
            repositoryPath = FileUtils.getPathFromUri(this, repositoryUri!!)

            // Display the path
            binding.locationPathTextView.text = repositoryPath ?: repositoryUri.toString()
        } else {
            Log.e(TAG, "No repository URI provided")
            finish()
            return
        }

        // Adjust UI based on whether we're creating or opening
        if (isExistingRepository) {
            // Opening existing repository
            binding.nameInputLayout.visibility = View.GONE
            binding.confirmPasswordInputLayout.visibility = View.GONE
            binding.createButton.text = getString(R.string.open_repository_button)
            binding.passwordInputLayout.hint = getString(R.string.enter_repository_password)
        } else {
            // Creating new repository
            binding.nameInputLayout.visibility = View.VISIBLE
            binding.confirmPasswordInputLayout.visibility = View.VISIBLE
            binding.createButton.text = getString(R.string.create_repository_button)

            // Pre-fill repository name with folder name
            repositoryPath?.let { path ->
                val folderName = path.substringAfterLast("/")
                if (folderName.isNotEmpty()) {
                    binding.nameEditText.setText(folderName)
                }
            }
        }

        // Set up button listeners
        binding.createButton.setOnClickListener {
            validateAndCreate()
        }

        binding.cancelButton.setOnClickListener {
            // Navigate back to repository selection screen
            val intent = Intent(this, RepositorySelectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    private fun validateAndCreate() {
        val password = binding.passwordEditText.text.toString()
        val name = if (isExistingRepository) "" else binding.nameEditText.text.toString()

        // Clear previous errors
        binding.passwordInputLayout.error = null
        if (!isExistingRepository) {
            binding.confirmPasswordInputLayout.error = null
        }

        // Validate password
        if (password.isEmpty()) {
            binding.passwordInputLayout.error = getString(R.string.password_required)
            binding.passwordEditText.requestFocus()
            return
        }

        if (!isExistingRepository) {
            // Additional validation for new repositories
            val confirmPassword = binding.confirmPasswordEditText.text.toString()

            if (password.length < 8) {
                binding.passwordInputLayout.error = getString(R.string.password_too_short)
                binding.passwordEditText.requestFocus()
                return
            }

            if (password != confirmPassword) {
                binding.confirmPasswordInputLayout.error = getString(R.string.password_mismatch)
                binding.confirmPasswordEditText.requestFocus()
                return
            }
        }

        // Save repository configuration
        saveRepositoryConfig(name, password)
    }

    private fun saveRepositoryConfig(name: String, password: String) {
        lifecycleScope.launch {
            try {
                // Save password to file for server to use
                savePasswordToFile(password)

                // Save repository configuration to SharedPreferences
                sharedPrefs.edit().apply {
                    putString("repository_uri", repositoryUri.toString())
                    putString("repository_path", repositoryPath)
                    putString("repository_name", name)
                    putString("repository_password", password)
                    putBoolean("repository_configured", true)
                    putBoolean("repository_is_new", !isExistingRepository)
                    putLong("repository_configured_timestamp", System.currentTimeMillis())
                    apply()
                }

                // Navigate to MainActivity which will start the server with this repository
                // Password validation happens when server starts - if wrong, server will fail
                navigateToMainActivity()

            } catch (e: Exception) {
                Log.e(TAG, "Error saving repository config", e)
                progressManager.showToast("Error: ${e.message}")
            }
        }
    }

    private fun savePasswordToFile(password: String) {
        try {
            // Create .kopia directory if it doesn't exist
            val kopiaDir = File(filesDir, ".kopia")
            if (!kopiaDir.exists()) {
                kopiaDir.mkdirs()
            }

            // Save password to file (plain text, as required by kopia)
            val passwordFile = File(kopiaDir, "repository.config.kopia-password")
            passwordFile.writeText(password)

            Log.d(TAG, "Password saved to ${passwordFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save password to file", e)
            // Don't fail the whole operation, server can prompt for password
        }
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("create_repository", true)
        }
        startActivity(intent)
        finish()
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.createButton.isEnabled = false
        binding.cancelButton.isEnabled = false
        binding.nameEditText.isEnabled = false
        binding.passwordEditText.isEnabled = false
        binding.confirmPasswordEditText.isEnabled = false
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
        binding.createButton.isEnabled = true
        binding.cancelButton.isEnabled = true
        binding.nameEditText.isEnabled = true
        binding.passwordEditText.isEnabled = true
        binding.confirmPasswordEditText.isEnabled = true
    }

    private fun updateServerStatusUI(status: ServerStatusManager.ServerStatus) {
        val (color, text) = when (status) {
            ServerStatusManager.ServerStatus.DISCONNECTED -> {
                Color.RED to getString(R.string.server_status_disconnected)
            }
            ServerStatusManager.ServerStatus.CONNECTING -> {
                Color.YELLOW to getString(R.string.server_status_connecting)
            }
            ServerStatusManager.ServerStatus.CONNECTED -> {
                Color.GREEN to getString(R.string.server_status_connected)
            }
            ServerStatusManager.ServerStatus.ERROR -> {
                Color.RED to getString(R.string.server_status_error)
            }
        }

        // Update status indicator color
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        binding.serverStatusIndicator.background = drawable

        // Update status text
        binding.serverStatusText.text = text
    }

    companion object {
        private const val TAG = "RepositoryConfig"
        private const val EXTRA_REPOSITORY_URI = "repository_uri"
        private const val EXTRA_IS_EXISTING = "is_existing"

        fun start(context: Context, repositoryUri: Uri, isExisting: Boolean = false) {
            val intent = Intent(context, RepositoryConfigActivity::class.java).apply {
                putExtra(EXTRA_REPOSITORY_URI, repositoryUri.toString())
                putExtra(EXTRA_IS_EXISTING, isExisting)
            }
            context.startActivity(intent)
        }
    }
}
