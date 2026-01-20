package org.kopiaKt.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Main application class for KopiaKt demo app.
 */
class KopiaKtApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
