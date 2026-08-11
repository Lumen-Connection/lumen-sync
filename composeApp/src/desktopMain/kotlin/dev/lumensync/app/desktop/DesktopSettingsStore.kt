package dev.lumensync.app.desktop

import dev.lumensync.app.model.AppSettings
import dev.lumensync.app.platform.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopSettingsStore : SettingsStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        if (!DesktopPaths.settingsFile.exists()) return@withContext AppSettings()
        runCatching {
            json.decodeFromString(AppSettings.serializer(), DesktopPaths.settingsFile.readText())
        }.getOrDefault(AppSettings())
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        val temporary = DesktopPaths.settingsFile.resolveSibling("settings.json.tmp")
        temporary.writeText(json.encodeToString(AppSettings.serializer(), settings))
        runCatching {
            java.nio.file.Files.move(
                temporary,
                DesktopPaths.settingsFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            java.nio.file.Files.move(
                temporary,
                DesktopPaths.settingsFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Unit
    }
}

