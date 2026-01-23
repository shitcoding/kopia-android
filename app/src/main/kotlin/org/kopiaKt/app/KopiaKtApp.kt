package org.kopiaKt.app

import android.app.Application
import androidx.work.Configuration

/**
 * Main application class for KopiaKt demo app.
 */
class KopiaKtApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        // WorkManager is initialized automatically via Configuration.Provider
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
