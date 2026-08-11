package dev.lumensync.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.lumensync.app.DefaultSyncEngine
import dev.lumensync.app.model.SessionMode
import dev.lumensync.app.ui.LumenSyncApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.SystemTray
import kotlin.system.exitProcess

fun main() {
    var activateWindow: (() -> Unit)? = null
    val guard = SingleInstanceGuard.acquire { activateWindow?.invoke() }
    if (guard == null) {
        SingleInstanceGuard.activateExisting()
        return
    }

    application {
        val runtime = remember { DesktopSyncRuntime() }
        val engine = remember {
            DefaultSyncEngine(runtime, DesktopSettingsStore(), { HttpClient(CIO) })
        }
        val platform = remember {
            DesktopPlatformActions(
                onStart = { engine.start(SessionMode.CONTINUOUS) },
                onStop = { engine.stop() },
            )
        }
        val scope = rememberCoroutineScope()
        var visible by remember { mutableStateOf(true) }
        val windowState = rememberWindowState(width = 760.dp, height = 700.dp)
        activateWindow = { visible = true }

        LaunchedEffect(Unit) {
            engine.initialize(startRuntime = true)
        }

        if (SystemTray.isSupported()) {
            Tray(
                icon = ColorPainter(Color(0xFF70E1B2)),
                tooltip = "Lumen Sync",
                onAction = { visible = true },
                menu = {
                    Item("Open Lumen Sync", onClick = { visible = true })
                    Item("Scan now", onClick = { scope.launch { engine.rescan() } })
                    Separator()
                    Item("Quit", onClick = {
                        scope.launch(Dispatchers.IO) {
                            engine.stop()
                            guard.close()
                            exitApplication()
                        }
                    })
                },
            )
        }

        Window(
            onCloseRequest = { visible = false },
            visible = visible,
            title = "Lumen Sync",
            state = windowState,
        ) {
            MenuBar {
                Menu("File") {
                    Item("Quit", onClick = {
                        scope.launch {
                            engine.stop()
                            guard.close()
                            exitApplication()
                        }
                    })
                }
            }
            LumenSyncApp(engine, platform)
        }
    }
}
