package org.kopiaKt.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.kopiaKt.app.bridge.FlutterEngineProvider

@HiltAndroidApp
class KopiaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Pre-warm Flutter engine for faster view initialization
        FlutterEngineProvider.initialize(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        FlutterEngineProvider.destroy()
    }
}
