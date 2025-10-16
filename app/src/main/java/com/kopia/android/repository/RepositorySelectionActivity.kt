package com.kopia.android.repository

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.kopia.android.MainActivity
import com.kopia.android.R
import com.kopia.android.databinding.ActivityRepositorySelectionBinding
import com.kopia.android.service.KopiaServerService
import com.kopia.android.service.ServerStatusManager
import com.kopia.android.util.ProgressManager
import kotlinx.coroutines.launch

/**
 * Activity for selecting or creating a Kopia repository.
 * This is shown when no repository is configured yet.
 */
class RepositorySelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRepositorySelectionBinding
    private lateinit var progressManager: ProgressManager
    private lateinit var repositoryManager: RepositoryManager
    private lateinit var serverStatusManager: ServerStatusManager

    private var isCreatingRepository = false

    // Shared preferences for storing repository state
    private val sharedPrefs by lazy {
        getSharedPreferences("kopia_settings", Context.MODE_PRIVATE)
    }

    // Register for repository directory selection result
    private val selectRepositoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val tree = DocumentFile.fromTreeUri(this, it)
            // Some top-level collections (e.g., Downloads root) are not selectable; require subfolder
            if (tree == null || !tree.canWrite()) {
                progressManager.showToast("This folder can't be used. Please create or choose a subfolder.")
                // Reopen picker to let user create/select a subfolder
                if (isCreatingRepository) {
                    launchCreateRepository()
                } else {
                    launchOpenRepository()
                }
                return@registerForActivityResult
            }

            // Persist permission to access this directory
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            if (isCreatingRepository) {
                createRepository(it)
            } else {
                openRepository(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRepositorySelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        progressManager = ProgressManager(this)
        repositoryManager = RepositoryManager(this)
        serverStatusManager = ServerStatusManager.getInstance(this)

        // Set up button click listeners
        binding.createRepositoryButton.setOnClickListener {
            launchCreateRepository()
        }

        binding.openRepositoryButton.setOnClickListener {
            launchOpenRepository()
        }

        // Initialize server status as disconnected (no server running yet)
        serverStatusManager.setStatus(ServerStatusManager.ServerStatus.DISCONNECTED)
        updateServerStatusUI(ServerStatusManager.ServerStatus.DISCONNECTED)
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

    private fun launchCreateRepository() {
        isCreatingRepository = true
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // Hint to show advanced locations and Documents; not all devices honor these
                putExtra("android.content.extra.SHOW_ADVANCED", true)
                putExtra("android.provider.extra.INITIAL_URI", android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            selectRepositoryLauncher.launch(null)
        } catch (_: Exception) {
            // Fallback without extras
            selectRepositoryLauncher.launch(null)
        }
    }

    private fun launchOpenRepository() {
        isCreatingRepository = false
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                // Hint to show advanced locations and Documents; not all devices honor these
                putExtra("android.content.extra.SHOW_ADVANCED", true)
                putExtra("android.provider.extra.INITIAL_URI", android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            selectRepositoryLauncher.launch(null)
        } catch (_: Exception) {
            // Fallback without extras
            selectRepositoryLauncher.launch(null)
        }
    }

    private fun createRepository(uri: Uri) {
        // Navigate to configuration screen where user can set password and other settings
        RepositoryConfigActivity.start(this, uri)
        finish()
    }

    private fun openRepository(uri: Uri) {
        // Navigate to configuration screen where user can enter password
        // Pass isNew=false to indicate this is an existing repository
        RepositoryConfigActivity.start(this, uri, isExisting = true)
        finish()
    }

    private fun saveRepositoryConfig(uri: Uri, isNew: Boolean) {
        sharedPrefs.edit().apply {
            putString("repository_uri", uri.toString())
            putBoolean("repository_configured", true)
            putBoolean("repository_is_new", isNew)
            putLong("repository_configured_timestamp", System.currentTimeMillis())
            apply()
        }
    }

    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showLoading(message: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.createRepositoryButton.isEnabled = false
        binding.openRepositoryButton.isEnabled = false
        progressManager.showToast(message)
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
        binding.createRepositoryButton.isEnabled = true
        binding.openRepositoryButton.isEnabled = true
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.repository_error)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val TAG = "RepositorySelection"

        fun start(context: Context) {
            val intent = Intent(context, RepositorySelectionActivity::class.java)
            context.startActivity(intent)
        }
    }
}
