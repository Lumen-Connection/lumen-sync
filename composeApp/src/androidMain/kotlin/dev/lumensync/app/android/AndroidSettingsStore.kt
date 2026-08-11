package dev.lumensync.app.android

import android.content.Context
import dev.lumensync.app.model.AppSettings
import dev.lumensync.app.platform.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class AndroidSettingsStore(context: Context) : SettingsStore {
    private val file = File(context.filesDir, "settings.json")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext AppSettings()
        runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }
            .getOrDefault(AppSettings())
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        val temporary = File(file.parentFile, "settings.json.tmp")
        temporary.writeText(json.encodeToString(AppSettings.serializer(), settings))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

