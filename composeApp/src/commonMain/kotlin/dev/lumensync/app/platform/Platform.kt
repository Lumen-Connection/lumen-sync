package dev.lumensync.app.platform

import dev.lumensync.app.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

data class RuntimeConnection(
    val baseUrl: String,
    val apiKey: String,
)

interface SyncRuntime {
    val running: StateFlow<Boolean>
    suspend fun start(): RuntimeConnection
    suspend fun stop()
}

interface SettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}

interface PlatformActions {
    val platformName: String
    val defaultDeviceName: String
    val canScanQr: Boolean
    val usesManualSessions: Boolean
    suspend fun chooseFolder(): String?
    suspend fun copyToClipboard(value: String)
    suspend fun scanInvite(): String?
    suspend fun startSyncSession()
    suspend fun stopSyncSession()
    fun qrMatrix(value: String, size: Int = 33): List<List<Boolean>>
    fun configureAutostart(enabled: Boolean)
}
