package org.kopiaKt.app.bridge

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine

/**
 * Fragment that hosts a Flutter view for embedding in the Android app.
 * Can be used within Compose via AndroidView or in traditional Fragment transactions.
 */
class KopiaFlutterFragment : Fragment() {

    private var flutterView: FlutterView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val engine = FlutterEngineProvider.requireEngine()

        flutterView = FlutterView(requireContext()).apply {
            attachToFlutterEngine(engine)
        }

        return flutterView!!
    }

    override fun onStart() {
        super.onStart()
        flutterView?.let { view ->
            FlutterEngineProvider.getEngine()?.lifecycleChannel?.appIsResumed()
        }
    }

    override fun onResume() {
        super.onResume()
        FlutterEngineProvider.getEngine()?.lifecycleChannel?.appIsResumed()
    }

    override fun onPause() {
        super.onPause()
        FlutterEngineProvider.getEngine()?.lifecycleChannel?.appIsInactive()
    }

    override fun onStop() {
        super.onStop()
        FlutterEngineProvider.getEngine()?.lifecycleChannel?.appIsPaused()
    }

    override fun onDestroyView() {
        flutterView?.detachFromFlutterEngine()
        flutterView = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): KopiaFlutterFragment = KopiaFlutterFragment()
    }
}

/**
 * Composable that embeds a Flutter view within a Compose UI.
 * Use this to display Flutter screens within the Compose navigation graph.
 *
 * @param modifier Modifier for the Flutter view container
 * @param route Optional route to navigate to in Flutter (for future use with go_router)
 */
@Composable
fun FlutterScreen(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    route: String? = null
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val engine = FlutterEngineProvider.requireEngine()
            FlutterView(context).apply {
                attachToFlutterEngine(engine)
            }
        },
        onRelease = { flutterView ->
            flutterView.detachFromFlutterEngine()
        }
    )
}
