package org.kopiaKt.app.bridge

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor

/**
 * Provides and manages the Flutter engine singleton.
 * Pre-warms the engine for faster Flutter view initialization.
 */
object FlutterEngineProvider {

    const val ENGINE_ID = "kopia_flutter_engine"

    private var flutterEngine: FlutterEngine? = null

    /**
     * Initializes and pre-warms the Flutter engine.
     * Should be called early in the application lifecycle (e.g., in Application.onCreate).
     */
    fun initialize(context: Context) {
        if (flutterEngine != null) return

        flutterEngine = FlutterEngine(context.applicationContext).apply {
            // Start executing Dart code
            dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint.createDefault()
            )
        }

        // Cache the engine for later retrieval
        FlutterEngineCache.getInstance().put(ENGINE_ID, flutterEngine!!)
    }

    /**
     * Returns the cached Flutter engine, or null if not initialized.
     */
    fun getEngine(): FlutterEngine? = flutterEngine

    /**
     * Returns the cached Flutter engine, throwing if not initialized.
     */
    fun requireEngine(): FlutterEngine =
        flutterEngine ?: throw IllegalStateException("FlutterEngine not initialized. Call initialize() first.")

    /**
     * Destroys the Flutter engine and clears the cache.
     * Should be called when the application is terminating.
     */
    fun destroy() {
        FlutterEngineCache.getInstance().remove(ENGINE_ID)
        flutterEngine?.destroy()
        flutterEngine = null
    }
}
