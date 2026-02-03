package org.kopiaKt.app

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import dagger.hilt.android.AndroidEntryPoint
import org.kopiaKt.app.bridge.FlutterEngineProvider
import org.kopiaKt.app.bridge.KopiaBridgeHandler

@AndroidEntryPoint
class MainActivity : FlutterFragmentActivity() {

    private var bridgeHandler: KopiaBridgeHandler? = null

    override fun provideFlutterEngine(context: android.content.Context): FlutterEngine? {
        // Use the pre-warmed engine from FlutterEngineProvider
        return FlutterEngineProvider.getEngine()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Set up Flutter bridge
        bridgeHandler = KopiaBridgeHandler(
            context = applicationContext,
            activity = this
        ).also { handler ->
            handler.setUp(flutterEngine.dartExecutor.binaryMessenger)
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridgeHandler = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
