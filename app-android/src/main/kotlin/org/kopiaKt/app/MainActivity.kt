package org.kopiaKt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dagger.hilt.android.AndroidEntryPoint
import org.kopiaKt.app.bridge.FlutterEngineProvider
import org.kopiaKt.app.bridge.KopiaBridgeHandler
import org.kopiaKt.app.navigation.KopiaNavHost
import org.kopiaKt.app.ui.theme.KopiaKtTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var bridgeHandler: KopiaBridgeHandler? = null

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set up Flutter bridge
        setupFlutterBridge()

        setContent {
            KopiaKtTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background
                ) {
                    KopiaNavHost()
                }
            }
        }
    }

    private fun setupFlutterBridge() {
        val engine = FlutterEngineProvider.getEngine() ?: return

        bridgeHandler = KopiaBridgeHandler(
            context = applicationContext,
            activity = this
        ).also { handler ->
            handler.setUp(engine.dartExecutor.binaryMessenger)
        }
    }
}
