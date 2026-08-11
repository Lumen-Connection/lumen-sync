package dev.lumensync.app.desktop

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

object DesktopPaths {
    val appData: Path by lazy {
        val os = System.getProperty("os.name").lowercase()
        val path = if (os.contains("win")) {
            val root = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            Path(root, "Lumen Sync")
        } else {
            val root = System.getenv("XDG_STATE_HOME")
                ?: Path(System.getProperty("user.home"), ".local", "state").toString()
            Path(root, "lumen-sync")
        }
        path.createDirectories()
    }

    val syncthingHome: Path get() = appData.resolve("syncthing").also { it.createDirectories() }
    val settingsFile: Path get() = appData.resolve("settings.json")
    val apiKeyFile: Path get() = appData.resolve("api-key")
    val logFile: Path get() = appData.resolve("syncthing.log")
}

