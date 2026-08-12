package dev.lumensync.app

import dev.lumensync.app.model.AppSettings
import dev.lumensync.app.model.InviteV1
import dev.lumensync.app.model.OnboardingMode
import dev.lumensync.app.model.SessionMode
import dev.lumensync.app.model.SyncPhase
import dev.lumensync.app.model.SyncStatus
import dev.lumensync.app.platform.SettingsStore
import dev.lumensync.app.platform.SyncRuntime
import dev.lumensync.app.syncthing.SyncthingRestClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

interface SyncEngine {
    val status: StateFlow<SyncStatus>
    val settings: StateFlow<AppSettings>
    suspend fun initialize(startRuntime: Boolean)
    suspend fun start(mode: SessionMode): SyncStatus
    suspend fun stop(resultMessage: String? = null)
    suspend fun createSpace(deviceName: String, path: String)
    suspend fun joinSpace(deviceName: String, path: String, inviteText: String)
    suspend fun createInvite(): String
    suspend fun approveDevice(deviceId: String, name: String)
    suspend fun rejectDevice(deviceId: String)
    suspend fun sync()
    suspend fun leaveSpace()
    suspend fun setDesktopAutostart(enabled: Boolean)
}

class DefaultSyncEngine(
    private val runtime: SyncRuntime,
    private val settingsStore: SettingsStore,
    private val httpClientFactory: () -> HttpClient,
    parentScope: CoroutineScope? = null,
) : SyncEngine {
    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableStatus = MutableStateFlow(SyncStatus())
    private val mutableSettings = MutableStateFlow(AppSettings())
    private var client: SyncthingRestClient? = null
    private var monitorJob: Job? = null

    override val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()
    override val settings: StateFlow<AppSettings> = mutableSettings.asStateFlow()

    override suspend fun initialize(startRuntime: Boolean) {
        mutableSettings.value = settingsStore.load()
        if (startRuntime) start(SessionMode.CONTINUOUS)
    }

    override suspend fun start(mode: SessionMode): SyncStatus {
        mutableStatus.value = SyncStatus(SyncPhase.STARTING, "Starting Syncthing…")
        return runCatching {
            ensureClient()
            val current = mutableSettings.value
            if (current.folderId.isNotBlank()) client!!.scan(current.folderId)
            startMonitor(mode)
            refreshStatus()
        }.getOrElse { error ->
            SyncStatus(SyncPhase.FAILED, error.message ?: "Unable to start Syncthing").also {
                mutableStatus.value = it
            }
        }
    }

    override suspend fun stop(resultMessage: String?) {
        monitorJob?.cancel()
        monitorJob = null
        client?.shutdown()
        client?.close()
        client = null
        runtime.stop()
        mutableStatus.value = SyncStatus(SyncPhase.STOPPED, resultMessage ?: "Not running")
    }

    override suspend fun createSpace(deviceName: String, path: String) {
        require(deviceName.isNotBlank()) { "Enter a device name" }
        require(path.isNotBlank()) { "Choose a folder" }
        ensureClient()
        val folderId = randomFolderId()
        client!!.setLocalDeviceName(deviceName.trim())
        client!!.createFolder(folderId, path)
        saveSettings(
            mutableSettings.value.copy(
                deviceName = deviceName.trim(),
                localFolder = path,
                folderId = folderId,
                onboardingMode = OnboardingMode.CREATED,
            ),
        )
        refreshStatus()
    }

    override suspend fun joinSpace(deviceName: String, path: String, inviteText: String) {
        require(deviceName.isNotBlank()) { "Enter a device name" }
        require(path.isNotBlank()) { "Choose a folder" }
        val invite = InviteCodec.decode(inviteText)
        ensureClient()
        client!!.setLocalDeviceName(deviceName.trim())
        client!!.addDevice(invite.deviceId, invite.deviceName)
        client!!.createFolder(invite.folderId, path, listOf(invite.deviceId))
        saveSettings(
            mutableSettings.value.copy(
                deviceName = deviceName.trim(),
                localFolder = path,
                folderId = invite.folderId,
                onboardingMode = OnboardingMode.JOINING,
            ),
        )
        startMonitor(SessionMode.CONTINUOUS)
        refreshStatus()
    }

    override suspend fun createInvite(): String {
        ensureClient()
        val system = client!!.systemStatus()
        val current = mutableSettings.value
        require(current.folderId.isNotBlank()) { "Create or join a sync space first" }
        startMonitor(SessionMode.CONTINUOUS)
        return InviteCodec.encode(
            InviteV1(
                folderId = current.folderId,
                deviceId = system.myId,
                deviceName = current.deviceName,
            ),
        )
    }

    override suspend fun approveDevice(deviceId: String, name: String) {
        ensureClient()
        val folderId = mutableSettings.value.folderId
        require(folderId.isNotBlank()) { "Create or join a sync space first" }
        client!!.addDevice(deviceId, name, folderId)
        client!!.dismissPendingDevice(deviceId)
        refreshStatus()
    }

    override suspend fun rejectDevice(deviceId: String) {
        ensureClient()
        client!!.dismissPendingDevice(deviceId)
        refreshStatus()
    }

    override suspend fun sync() {
        val folderId = mutableSettings.value.folderId
        require(folderId.isNotBlank()) { "No folder is configured" }
        ensureClient()
        client!!.scan(folderId)
        refreshStatus()
    }

    override suspend fun leaveSpace() {
        val current = mutableSettings.value
        if (current.folderId.isNotBlank()) {
            val api = ensureClient()
            val localDeviceId = api.systemStatus().myId
            val configuredPeers = api.configuredDevices().filterNot { it.id == localDeviceId }
            val pendingPeers = api.pendingDevices()
            val pendingFolders = api.pendingFolders().keys.filter { it == current.folderId }

            // Remove peer configuration before the folder. This keeps a retry possible if
            // the folder deletion itself fails, and the REST operations are idempotent.
            configuredPeers.forEach { api.removeDevice(it.id) }
            pendingPeers.forEach { api.dismissPendingDevice(it.id) }
            pendingFolders.forEach { api.dismissPendingFolder(it) }
            api.removeFolder(current.folderId)
        }

        stop("Space left")
        saveSettings(AppSettings())
    }

    override suspend fun setDesktopAutostart(enabled: Boolean) {
        saveSettings(mutableSettings.value.copy(desktopAutostart = enabled))
    }

    private suspend fun ensureClient(): SyncthingRestClient {
        client?.let { return it }
        val connection = runtime.start()
        val newClient = SyncthingRestClient.create(httpClientFactory(), connection)
        repeat(STARTUP_ATTEMPTS) {
            if (newClient.ping()) {
                client = newClient
                return newClient
            }
            runtime.startupFailure()?.let { failure ->
                newClient.close()
                error(failure)
            }
            delay(250)
        }
        newClient.close()
        error(runtime.startupFailure() ?: "Syncthing did not expose its local API after 30 seconds")
    }

    private fun startMonitor(mode: SessionMode) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && runtime.running.value) {
                runCatching { refreshStatus() }
                    .onFailure { error ->
                        mutableStatus.value = mutableStatus.value.copy(
                            phase = SyncPhase.ATTENTION,
                            message = error.message ?: "Unable to read sync status",
                        )
                    }
                delay(if (mode == SessionMode.UNTIL_UP_TO_DATE) 1_000 else 2_500)
            }
        }
    }

    private suspend fun refreshStatus(): SyncStatus {
        val api = client ?: return mutableStatus.value
        val appSettings = mutableSettings.value
        val system = api.systemStatus()
        val devices = api.configuredDevices().filterNot { it.id == system.myId }
        val pending = api.pendingDevices()
        val folderStatus = appSettings.folderId.takeIf { it.isNotBlank() }?.let { api.folderStatus(it) }
        val errors = appSettings.folderId.takeIf { it.isNotBlank() }?.let { api.folderErrors(it) }.orEmpty()
        return SyncStatusPolicy.derive(
            folderConfigured = appSettings.folderId.isNotBlank(),
            folderStatus = folderStatus,
            devices = devices,
            pending = pending,
            errors = errors,
            deviceId = system.myId,
            previousMessage = mutableStatus.value.message,
        ).also { mutableStatus.value = it }
    }

    private suspend fun saveSettings(settings: AppSettings) {
        settingsStore.save(settings)
        mutableSettings.value = settings
    }

    private fun randomFolderId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        fun part(length: Int) = buildString(length) {
            repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        return "${part(5)}-${part(5)}"
    }
}

private const val STARTUP_ATTEMPTS = 120

internal object SyncStatusPolicy {
    fun derive(
        folderConfigured: Boolean,
        folderStatus: dev.lumensync.app.model.SyncthingFolderStatus?,
        devices: List<dev.lumensync.app.model.DeviceSummary>,
        pending: List<dev.lumensync.app.model.DeviceSummary>,
        errors: List<String>,
        deviceId: String = "",
        previousMessage: String = "Starting Syncthing…",
    ): SyncStatus {
        val connected = devices.count { it.connected }
        val phase = when {
            errors.isNotEmpty() || pending.isNotEmpty() -> SyncPhase.ATTENTION
            !folderConfigured -> SyncPhase.WAITING_FOR_PEER
            folderStatus == null -> SyncPhase.STARTING
            folderStatus.state.contains("scan", ignoreCase = true) -> SyncPhase.SCANNING
            folderStatus.needBytes > 0 || folderStatus.needTotalItems > 0 || folderStatus.state != "idle" -> SyncPhase.SYNCING
            connected == 0 -> SyncPhase.WAITING_FOR_PEER
            else -> SyncPhase.UP_TO_DATE
        }
        val message = when (phase) {
            SyncPhase.ATTENTION -> if (pending.isNotEmpty()) "A device is waiting for approval" else "Sync needs attention"
            SyncPhase.WAITING_FOR_PEER -> if (!folderConfigured) "Waiting for the shared folder" else "No devices reachable"
            SyncPhase.SCANNING -> "Scanning for changes…"
            SyncPhase.SYNCING -> "Syncing ${folderStatus?.needTotalItems ?: 0} item(s)…"
            SyncPhase.UP_TO_DATE -> if (devices.any { !it.connected }) "Up to date with reachable devices" else "Up to date"
            else -> previousMessage
        }
        return SyncStatus(
            phase = phase,
            message = message,
            needBytes = folderStatus?.needBytes ?: 0,
            needItems = folderStatus?.needTotalItems ?: 0,
            connectedDevices = connected,
            totalDevices = devices.size,
            devices = devices + pending,
            errors = errors,
            deviceId = deviceId,
        )
    }
}
