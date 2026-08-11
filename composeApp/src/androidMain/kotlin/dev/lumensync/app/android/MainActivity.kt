package dev.lumensync.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.lumensync.app.ui.LumenSyncApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var graph: AndroidAppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AndroidAppGraph.get(this)
        val platform = AndroidPlatformActions(this)
        lifecycleScope.launch { graph.initialize() }
        setContent { LumenSyncApp(graph.engine, platform) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !graph.serviceRunning) {
            lifecycleScope.launch { graph.engine.stop() }
        }
    }
}
