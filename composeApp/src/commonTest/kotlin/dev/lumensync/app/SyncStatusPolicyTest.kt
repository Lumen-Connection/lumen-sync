package dev.lumensync.app

import dev.lumensync.app.model.DeviceSummary
import dev.lumensync.app.model.SyncPhase
import dev.lumensync.app.model.SyncthingFolderStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStatusPolicyTest {
    @Test
    fun aNewUnpairedFolderWaitsForADevice() {
        val status = SyncStatusPolicy.derive(
            folderConfigured = true,
            folderStatus = SyncthingFolderStatus(state = "idle"),
            devices = emptyList(),
            pending = emptyList(),
            errors = emptyList(),
        )

        assertEquals(SyncPhase.WAITING_FOR_PEER, status.phase)
    }

    @Test
    fun pendingApprovalTakesPrecedenceOverIdleFolder() {
        val status = SyncStatusPolicy.derive(
            folderConfigured = true,
            folderStatus = SyncthingFolderStatus(state = "idle"),
            devices = emptyList(),
            pending = listOf(DeviceSummary("id", "Phone", connected = false, pending = true)),
            errors = emptyList(),
        )

        assertEquals(SyncPhase.ATTENTION, status.phase)
        assertEquals("A device is waiting for approval", status.message)
    }

    @Test
    fun reportsWorkRemainingAsSyncing() {
        val status = SyncStatusPolicy.derive(
            folderConfigured = true,
            folderStatus = SyncthingFolderStatus(needBytes = 42, needTotalItems = 3, state = "syncing"),
            devices = listOf(DeviceSummary("id", "Phone", connected = true)),
            pending = emptyList(),
            errors = emptyList(),
        )

        assertEquals(SyncPhase.SYNCING, status.phase)
        assertEquals(42, status.needBytes)
        assertEquals(3, status.needItems)
    }

    @Test
    fun distinguishesOfflinePeersFromFullyUpToDateMesh() {
        val status = SyncStatusPolicy.derive(
            folderConfigured = true,
            folderStatus = SyncthingFolderStatus(state = "idle"),
            devices = listOf(
                DeviceSummary("one", "Phone", connected = true),
                DeviceSummary("two", "Laptop", connected = false),
            ),
            pending = emptyList(),
            errors = emptyList(),
        )

        assertEquals(SyncPhase.UP_TO_DATE, status.phase)
        assertEquals("Up to date with reachable devices", status.message)
        assertEquals(1, status.connectedDevices)
        assertEquals(2, status.totalDevices)
    }
}
