package dev.lumensync.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumensync.app.SyncEngine
import dev.lumensync.app.model.DeviceSummary
import dev.lumensync.app.model.SessionMode
import dev.lumensync.app.model.SyncPhase
import dev.lumensync.app.model.SyncStatus
import dev.lumensync.app.platform.PlatformActions
import kotlinx.coroutines.launch

private val LumenGreen = Color(0xFF70E1B2)
private val LumenSurface = Color(0xFF182026)
private val LumenBackground = Color(0xFF0E1418)
private val LumenWarning = Color(0xFFFFCA6A)

@Composable
fun LumenSyncApp(engine: SyncEngine, platform: PlatformActions) {
    val settings by engine.settings.collectAsState()
    val status by engine.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var showInvite by remember { mutableStateOf(false) }

    MaterialTheme(
        colors = darkColors(
            primary = LumenGreen,
            background = LumenBackground,
            surface = LumenSurface,
            onPrimary = Color(0xFF07110D),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            if (!settings.isConfigured) {
                Onboarding(
                    platform = platform,
                    onCreate = { name, folder ->
                        scope.launch {
                            error = runCatching { engine.createSpace(name, folder) }.exceptionOrNull()?.message
                        }
                    },
                    onJoin = { name, folder, invite ->
                        scope.launch {
                            error = runCatching { engine.joinSpace(name, folder, invite) }.exceptionOrNull()?.message
                        }
                    },
                    error = error,
                )
            } else {
                Dashboard(
                    status = status,
                    localFolder = settings.localFolder,
                    autostart = settings.desktopAutostart,
                    platform = platform,
                    onInvite = { showInvite = true },
                    onStart = {
                        scope.launch {
                            error = runCatching {
                                if (platform.usesManualSessions) platform.startSyncSession()
                                else engine.start(SessionMode.CONTINUOUS)
                            }.exceptionOrNull()?.message
                        }
                    },
                    onStop = { scope.launch { platform.stopSyncSession() } },
                    onRescan = { scope.launch { error = runCatching { engine.rescan() }.exceptionOrNull()?.message } },
                    onApprove = { device ->
                        scope.launch {
                            error = runCatching { engine.approveDevice(device.id, device.name) }.exceptionOrNull()?.message
                        }
                    },
                    onReject = { device -> scope.launch { engine.rejectDevice(device.id) } },
                    onAutostart = { enabled ->
                        scope.launch {
                            platform.configureAutostart(enabled)
                            engine.setDesktopAutostart(enabled)
                        }
                    },
                    error = error,
                )
            }
        }

        if (showInvite) {
            InviteOverlay(engine, platform) { showInvite = false }
        }
    }
}

@Composable
private fun Onboarding(
    platform: PlatformActions,
    onCreate: (String, String) -> Unit,
    onJoin: (String, String, String) -> Unit,
    error: String?,
) {
    var joining by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(platform.defaultDeviceName) }
    var folder by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(18.dp), elevation = 10.dp) {
            Column(
                Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Lumen Sync", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
                Text("One folder, kept in step across your trusted devices.", color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { joining = false }) { Text("Create a sync space") }
                    OutlinedButton(onClick = { joining = true }) { Text("Join a device") }
                }
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("This device") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Folder") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            scope.launch { platform.chooseFolder()?.let { folder = it } }
                        }) { Text("Choose") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (joining) {
                    OutlinedTextField(
                        value = invite,
                        onValueChange = { invite = it },
                        label = { Text("Invite code") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (platform.canScanQr) {
                        OutlinedButton(onClick = {
                            scope.launch { platform.scanInvite()?.let { invite = it } }
                        }) { Text("Scan QR code") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colors.error) }
                Button(
                    onClick = {
                        if (joining) onJoin(deviceName, folder, invite) else onCreate(deviceName, folder)
                    },
                    enabled = deviceName.isNotBlank() && folder.isNotBlank() && (!joining || invite.isNotBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (joining) "Request access" else "Create")
                }
                Text(
                    "Changes and deletions propagate immediately. Lumen Sync is not a backup.",
                    color = LumenWarning,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun Dashboard(
    status: SyncStatus,
    localFolder: String,
    autostart: Boolean,
    platform: PlatformActions,
    onInvite: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRescan: () -> Unit,
    onApprove: (DeviceSummary) -> Unit,
    onReject: (DeviceSummary) -> Unit,
    onAutostart: (Boolean) -> Unit,
    error: String?,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lumen Sync", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                Text(localFolder, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onInvite) { Text("Add device") }
        }

        StatusCard(status)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (platform.usesManualSessions) {
                Button(onClick = onStart) { Text("Sync now") }
                if (status.phase != SyncPhase.STOPPED) OutlinedButton(onClick = onStop) { Text("Stop") }
            } else {
                OutlinedButton(onClick = onRescan) { Text("Scan now") }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colors.error) }
        status.errors.forEach { Text(it, color = MaterialTheme.colors.error) }

        Text("Devices", style = MaterialTheme.typography.h6)
        if (status.devices.isEmpty()) {
            Text("No other devices yet. Add one to begin syncing.", color = Color.Gray)
        } else {
            status.devices.forEach { device ->
                DeviceRow(device, onApprove, onReject)
                Divider(color = Color(0xFF29343B))
            }
        }

        if (!platform.usesManualSessions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autostart, onCheckedChange = onAutostart)
                Text("Start at login")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Pure mirror mode", fontWeight = FontWeight.SemiBold, color = LumenWarning)
        Text(
            "Deletions sync to every reachable device. Conflicting edits are preserved as conflict copies, but deleted files are not versioned.",
            color = Color.Gray,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun StatusCard(status: SyncStatus) {
    Card(Modifier.fillMaxWidth(), backgroundColor = LumenSurface, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.progressVisible) {
                CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 3.dp)
            } else {
                Box(Modifier.size(16.dp).background(statusColor(status.phase), RoundedCornerShape(8.dp)))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(status.message, fontWeight = FontWeight.SemiBold)
                Text(
                    "${status.connectedDevices}/${status.totalDevices} devices online" +
                        if (status.needItems > 0) " · ${status.needItems} items remaining" else "",
                    color = Color.Gray,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceSummary,
    onApprove: (DeviceSummary) -> Unit,
    onReject: (DeviceSummary) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(
                when {
                    device.pending -> LumenWarning
                    device.connected -> LumenGreen
                    else -> Color.Gray
                },
                RoundedCornerShape(5.dp),
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name)
            Text(device.id, color = Color.Gray, style = MaterialTheme.typography.caption, maxLines = 1)
        }
        if (device.pending) {
            TextButton(onClick = { onReject(device) }) { Text("Reject") }
            Button(onClick = { onApprove(device) }) { Text("Approve") }
        }
    }
}

@Composable
private fun InviteOverlay(engine: SyncEngine, platform: PlatformActions, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { engine.createInvite() }
            .onSuccess { invite = it }
            .onFailure { error = it.message }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Add a trusted device", style = MaterialTheme.typography.h6)
                Text("Scan this code or copy the invite. You will approve the new device here before anything is shared.")
                invite?.let { value ->
                    QrCode(value, platform)
                    Text(value, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
                    OutlinedButton(onClick = { scope.launch { platform.copyToClipboard(value) } }) {
                        Text("Copy invite")
                    }
                } ?: CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colors.error) }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun QrCode(value: String, platform: PlatformActions) {
    val matrix = remember(value) { platform.qrMatrix(value) }
    Canvas(Modifier.size(220.dp).background(Color.White)) {
        if (matrix.isEmpty()) return@Canvas
        val cell = size.minDimension / matrix.size
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

private fun statusColor(phase: SyncPhase): Color = when (phase) {
    SyncPhase.UP_TO_DATE -> LumenGreen
    SyncPhase.ATTENTION, SyncPhase.WAITING_FOR_PEER -> LumenWarning
    SyncPhase.FAILED -> Color(0xFFFF6B6B)
    else -> Color.Gray
}
