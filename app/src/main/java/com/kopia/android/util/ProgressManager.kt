package com.kopia.android.util

import android.app.Activity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.snackbar.Snackbar
import com.kopia.android.R

/**
 * Utility class for managing progress indicators and error messages
 */
class ProgressManager(private val activity: Activity) {
    
    // These views may not exist in all layouts. Fall back to existing IDs.
    private val progressOverlay: View? by lazy {
        activity.findViewById<View?>(R.id.progressOverlay)
            ?: activity.findViewById(R.id.progressBar)
    }
    
    private val progressMessage: TextView? by lazy {
        activity.findViewById<TextView?>(R.id.progressMessage)
            ?: activity.findViewById(R.id.statusText)
    }
    
    /**
     * Show the progress overlay with a message
     * 
     * @param message The message to display
     */
    fun showProgress(message: String) {
        activity.runOnUiThread {
            progressMessage?.text = message
            progressMessage?.visibility = View.VISIBLE
            progressOverlay?.visibility = View.VISIBLE
        }
    }
    
    /**
     * Hide the progress overlay
     */
    fun hideProgress() {
        activity.runOnUiThread {
            progressOverlay?.visibility = View.GONE
        }
    }
    
    /**
     * Show a toast message
     * 
     * @param message The message to display
     * @param duration The duration of the toast (Toast.LENGTH_SHORT or Toast.LENGTH_LONG)
     */
    fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, duration).show()
        }
    }
    
    /**
     * Show a snackbar message
     * 
     * @param message The message to display
     * @param duration The duration of the snackbar (Snackbar.LENGTH_SHORT, Snackbar.LENGTH_LONG, or Snackbar.LENGTH_INDEFINITE)
     * @param actionText The text for the action button (null for no action)
     * @param action The action to perform when the button is clicked
     */
    fun showSnackbar(
        message: String,
        duration: Int = Snackbar.LENGTH_LONG,
        actionText: String? = null,
        action: (() -> Unit)? = null
    ) {
        activity.runOnUiThread {
            val rootView = activity.findViewById<View>(android.R.id.content)
            val snackbar = Snackbar.make(rootView, message, duration)
            
            if (actionText != null && action != null) {
                snackbar.setAction(actionText) { action() }
            }
            
            snackbar.show()
        }
    }
    
    /**
     * Handle an error with appropriate UI feedback
     * 
     * @param error The exception or error
     * @param message Optional custom message to display
     * @param showSnackbar Whether to show a snackbar (true) or toast (false)
     */
    fun handleError(error: Throwable, message: String? = null, showSnackbar: Boolean = true) {
        val errorMessage = message ?: "Error: ${error.message ?: "Unknown error"}"
        
        activity.runOnUiThread {
            hideProgress()
            
            if (showSnackbar) {
                showSnackbar(errorMessage, Snackbar.LENGTH_LONG, "Dismiss", null)
            } else {
                showToast(errorMessage, Toast.LENGTH_LONG)
            }
        }
        
        // Log the error
        android.util.Log.e("KopiaAndroid", "Error: ${error.message}", error)
    }
}
