package dev.lumensync.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val deviceName: String = "",
    val localFolder: String = "",
    val folderId: String = "",
    val desktopAutostart: Boolean = false,
    val onboardingMode: OnboardingMode = OnboardingMode.NONE,
) {
    val isConfigured: Boolean get() = localFolder.isNotBlank() && deviceName.isNotBlank()

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

@Serializable
enum class OnboardingMode {
    NONE,
    CREATED,
    JOINING,
}

@Serializable
data class InviteV1(
    val version: Int = 1,
    val folderId: String,
    val deviceId: String,
    val deviceName: String,
) {
    init {
        require(version == 1) { "Unsupported invite version: $version" }
        require(FOLDER_ID.matches(folderId)) { "Invalid folder ID" }
        require(DEVICE_ID.matches(deviceId)) { "Invalid Syncthing device ID" }
        require(deviceName.isNotBlank()) { "Device name cannot be blank" }
    }

    companion object {
        val FOLDER_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        val DEVICE_ID = Regex("^[A-Z2-7]{7}(?:-[A-Z2-7]{7}){7}$")
    }
}

data class DeviceSummary(
    val id: String,
    val name: String,
    val connected: Boolean,
    val pending: Boolean = false,
)

enum class SyncPhase {
    STOPPED,
    STARTING,
    WAITING_FOR_PEER,
    SCANNING,
    SYNCING,
    UP_TO_DATE,
    ATTENTION,
    FAILED,
}

data class SyncStatus(
    val phase: SyncPhase = SyncPhase.STOPPED,
    val message: String = "Not running",
    val needBytes: Long = 0,
    val needItems: Long = 0,
    val connectedDevices: Int = 0,
    val totalDevices: Int = 0,
    val devices: List<DeviceSummary> = emptyList(),
    val errors: List<String> = emptyList(),
    val deviceId: String = "",
) {
    val progressVisible: Boolean get() = phase == SyncPhase.SCANNING || phase == SyncPhase.SYNCING
    val isSettled: Boolean get() = phase == SyncPhase.UP_TO_DATE && needBytes == 0L && needItems == 0L
}

enum class SessionMode {
    CONTINUOUS,
    UNTIL_UP_TO_DATE,
}

@Serializable
internal data class SyncthingSystemStatus(
    @SerialName("myID") val myId: String = "",
)

@Serializable
internal data class SyncthingFolderStatus(
    val needBytes: Long = 0,
    val needTotalItems: Long = 0,
    val pullErrors: Long = 0,
    val state: String = "idle",
)
