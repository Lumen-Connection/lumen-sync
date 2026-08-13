package dev.lumensync.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
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
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

private object LumenColors {
    val appBackground = Color(0xFF0A0E12)
    val sidebar = Color(0xFF070A0D)
    val card = Color(0xFF121821)
    val cardHover = Color(0xFF1C2530)
    val input = Color(0xFF161D27)
    val border = Color(0xFF263240)
    val accent = Color(0xFFFF5722)
    val accentSoft = Color(0xFF542817)
    val text = Color(0xFFEEF3F6)
    val muted = Color(0xFF93A1AD)
    val faint = Color(0xFF5A6670)
    val success = Color(0xFF70E1B2)
    val danger = Color(0xFFFF4D4D)
}

private val panelShape = RoundedCornerShape(14.dp)

@Composable
fun LumenSyncApp(engine: SyncEngine, platform: PlatformActions) {
    val settings by engine.settings.collectAsState()
    val status by engine.status.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    MaterialTheme(
        colors = darkColors(
            primary = LumenColors.accent,
            primaryVariant = LumenColors.accent,
            secondary = LumenColors.success,
            background = LumenColors.appBackground,
            surface = LumenColors.card,
            onPrimary = Color.White,
            onBackground = LumenColors.text,
            onSurface = LumenColors.text,
            error = LumenColors.danger,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                if (!settings.isConfigured) {
                    Onboarding(
                        platform = platform,
                        onCreate = { name, folder ->
                            scope.launch {
                                error = runCatching { engine.createSpace(name, folder) }
                                    .exceptionOrNull()?.message
                            }
                        },
                        onJoin = { name, folder, invite ->
                            scope.launch {
                                error = runCatching { engine.joinSpace(name, folder, invite) }
                                    .exceptionOrNull()?.message
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
                        onSeeFolder = {
                            scope.launch {
                                error = runCatching { platform.openFolder(settings.localFolder) }
                                    .exceptionOrNull()?.message
                            }
                        },
                        onSync = {
                            scope.launch {
                                error = runCatching {
                                    if (platform.usesManualSessions) {
                                        platform.startSyncSession()
                                    } else {
                                        engine.sync()
                                    }
                                }.exceptionOrNull()?.message
                            }
                        },
                        onStop = { scope.launch { platform.stopSyncSession() } },
                        onApprove = { device ->
                            scope.launch {
                                error = runCatching { engine.approveDevice(device.id, device.name) }
                                    .exceptionOrNull()?.message
                            }
                        },
                        onReject = { device ->
                            scope.launch {
                                error = runCatching { engine.rejectDevice(device.id) }
                                    .exceptionOrNull()?.message
                            }
                        },
                        onAutostart = { enabled ->
                            scope.launch {
                                platform.configureAutostart(enabled)
                                engine.setDesktopAutostart(enabled)
                            }
                        },
                        onLeave = { showLeave = true },
                        leaving = leaving,
                        error = error,
                    )
                }

                if (showInvite) {
                    InviteOverlay(engine, platform) { showInvite = false }
                }
            }
        }

        if (showLeave) {
            LeaveSpaceDialog(
                leaving = leaving,
                error = error,
                onDismiss = { if (!leaving) showLeave = false },
                onConfirm = {
                    if (leaving) return@LeaveSpaceDialog
                    scope.launch {
                        leaving = true
                        val failure = runCatching {
                            engine.leaveSpace()
                            platform.configureAutostart(false)
                            if (platform.usesManualSessions) {
                                platform.stopSyncSession()
                            }
                        }.exceptionOrNull()
                        leaving = false
                        if (failure == null) {
                            error = null
                            showLeave = false
                        } else {
                            error = failure.message ?: "Unable to leave this space"
                        }
                    }
                },
            )
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 680.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                horizontal = if (compact) 20.dp else 48.dp,
                vertical = if (compact) 24.dp else 48.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader(
                eyebrow = "PRIVATE • PEER TO PEER",
                title = "Keep your files in step.",
                subtitle = "One folder, shared directly across the devices you trust.",
            )
            Spacer(Modifier.height(28.dp))
            Card(
                Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.width(620.dp)),
                backgroundColor = LumenColors.card,
                shape = panelShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, LumenColors.border),
                elevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(if (compact) 20.dp else 28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Set up a sync space", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                    Text(
                        "Create a new space or join one with an invite from another device.",
                        color = LumenColors.muted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { joining = false },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (!joining) LumenColors.accent else LumenColors.input,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Create") }
                        OutlinedButton(
                            onClick = { joining = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (joining) LumenColors.accent else LumenColors.text,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { Text("Join") }
                    }
                    LumenTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = "This device",
                    )
                    LumenTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = "Folder",
                        trailing = {
                            TextButton(onClick = {
                                scope.launch { platform.chooseFolder()?.let { folder = it } }
                            }) { Text("Choose", color = LumenColors.accent) }
                        },
                    )
                    if (joining) {
                        LumenTextField(
                            value = invite,
                            onValueChange = { invite = it },
                            label = "Invite code",
                            minLines = 3,
                        )
                        if (platform.canScanQr) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch { platform.scanInvite()?.let { invite = it } }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = LumenColors.accent),
                            ) { Text("Scan QR code") }
                        }
                    }
                    error?.let { ErrorNote(it) }
                    Button(
                        onClick = {
                            if (joining) onJoin(deviceName, folder, invite) else onCreate(deviceName, folder)
                        },
                        enabled = deviceName.isNotBlank() && folder.isNotBlank() && (!joining || invite.isNotBlank()),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = LumenColors.accent,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (joining) "Request access" else "Create space")
                    }
                    SafetyNote("Changes and deletions propagate immediately. Lumen Sync is not a backup.")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Lumen Sync", color = LumenColors.faint, style = MaterialTheme.typography.caption)
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
    onSeeFolder: () -> Unit,
    onSync: () -> Unit,
    onStop: () -> Unit,
    onApprove: (DeviceSummary) -> Unit,
    onReject: (DeviceSummary) -> Unit,
    onAutostart: (Boolean) -> Unit,
    onLeave: () -> Unit,
    leaving: Boolean,
    error: String?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 760.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                horizontal = if (compact) 20.dp else 36.dp,
                vertical = if (compact) 20.dp else 30.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("LUMEN SYNC", color = LumenColors.accent, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
                    Text("Your sync space", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                    Text(localFolder, color = LumenColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onSeeFolder,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LumenColors.accent),
                        modifier = Modifier.weight(1f),
                    ) { Text("See folder") }
                    Button(
                        onClick = onInvite,
                        colors = ButtonDefaults.buttonColors(backgroundColor = LumenColors.accent, contentColor = Color.White),
                        modifier = Modifier.weight(1f),
                    ) { Text("Add device") }
                }
            }

            if (compact) {
                StatusPanel(status, onSync, onStop, platform)
                DevicePanel(status.devices, onApprove, onReject)
                SettingsPanel(platform, autostart, onAutostart, onLeave, leaving)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatusPanel(status, onSync, onStop, platform)
                        SettingsPanel(platform, autostart, onAutostart, onLeave, leaving)
                    }
                    Column(Modifier.weight(1f)) {
                        DevicePanel(status.devices, onApprove, onReject)
                    }
                }
            }

            error?.let { ErrorNote(it) }
            status.errors.forEach { ErrorNote(it) }
            SafetyNote("Deletions sync to reachable devices. Conflict copies are preserved, but deleted files are not versioned.")
        }
    }
}

@Composable
private fun StatusPanel(
    status: SyncStatus,
    onSync: () -> Unit,
    onStop: () -> Unit,
    platform: PlatformActions,
) {
    Panel {
        Text("SYNC STATUS", color = LumenColors.muted, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (status.progressVisible) {
                CircularProgressIndicator(Modifier.size(32.dp), color = LumenColors.accent, strokeWidth = 3.dp)
            } else {
                Box(Modifier.size(14.dp).background(statusColor(status.phase), RoundedCornerShape(7.dp)))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(status.message, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                Text(
                    "${status.connectedDevices}/${status.totalDevices} devices online" +
                        if (status.needItems > 0) " · ${status.needItems} items remaining" else "",
                    color = LumenColors.muted,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSync,
                colors = ButtonDefaults.buttonColors(backgroundColor = LumenColors.accent, contentColor = Color.White),
            ) { Text("Sync") }
            if (platform.usesManualSessions && status.phase != SyncPhase.STOPPED) {
                OutlinedButton(
                    onClick = onStop,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LumenColors.text),
                ) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun DevicePanel(
    devices: List<DeviceSummary>,
    onApprove: (DeviceSummary) -> Unit,
    onReject: (DeviceSummary) -> Unit,
) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("DEVICES", color = LumenColors.muted, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
                Text("Trusted peers", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            }
            Text("${devices.size}", color = LumenColors.accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        if (devices.isEmpty()) {
            Text("No other devices yet. Add one to begin syncing.", color = LumenColors.muted)
        } else {
            devices.forEachIndexed { index, device ->
                DeviceRow(device, onApprove, onReject)
                if (index < devices.lastIndex) Divider(color = LumenColors.border)
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    platform: PlatformActions,
    autostart: Boolean,
    onAutostart: (Boolean) -> Unit,
    onLeave: () -> Unit,
    leaving: Boolean,
) {
    Panel {
        Text("SETTINGS", color = LumenColors.muted, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
        if (!platform.usesManualSessions) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(
                    checked = autostart,
                    onCheckedChange = onAutostart,
                    colors = androidx.compose.material.CheckboxDefaults.colors(
                        checkedColor = LumenColors.accent,
                        uncheckedColor = LumenColors.muted,
                    ),
                )
                Text("Start at login")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onLeave,
            enabled = !leaving,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = LumenColors.danger),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (leaving) "Leaving…" else "Leave space") }
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
                    device.pending -> LumenColors.accent
                    device.connected -> LumenColors.success
                    else -> LumenColors.faint
                },
                RoundedCornerShape(5.dp),
            ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Text(device.id, color = LumenColors.muted, style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (device.pending) {
            TextButton(onClick = { onReject(device) }) { Text("Reject", color = LumenColors.muted) }
            Button(
                onClick = { onApprove(device) },
                colors = ButtonDefaults.buttonColors(backgroundColor = LumenColors.accent, contentColor = Color.White),
            ) { Text("Approve") }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        backgroundColor = LumenColors.card,
        shape = panelShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, LumenColors.border),
        elevation = 0.dp,
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun BrandHeader(eyebrow: String, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth(0.92f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(eyebrow, color = LumenColors.accent, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = LumenColors.muted)
    }
}

@Composable
private fun LumenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    trailing: (@Composable (() -> Unit))? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        singleLine = minLines == 1,
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = LumenColors.text,
            cursorColor = LumenColors.accent,
            focusedBorderColor = LumenColors.accent,
            unfocusedBorderColor = LumenColors.border,
            focusedLabelColor = LumenColors.accent,
            unfocusedLabelColor = LumenColors.muted,
            backgroundColor = LumenColors.input,
        ),
    )
}

@Composable
private fun ErrorNote(message: String) {
    Text(
        message,
        color = LumenColors.danger,
        modifier = Modifier.fillMaxWidth().background(LumenColors.danger.copy(alpha = 0.1f), panelShape).padding(12.dp),
    )
}

@Composable
private fun SafetyNote(message: String) {
    Text(
        message,
        color = LumenColors.muted,
        style = MaterialTheme.typography.caption,
        modifier = Modifier.fillMaxWidth().border(1.dp, LumenColors.border, panelShape).padding(12.dp),
    )
}

@Composable
private fun LeaveSpaceDialog(
    leaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave this space?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Syncing will stop and this device will forget the current space.")
                Text(
                    "Your files, app data, logs, and device identity stay on this device. Other devices are not changed and may still list this device.",
                    color = LumenColors.muted,
                )
                error?.let { Text(it, color = LumenColors.danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !leaving,
                colors = ButtonDefaults.buttonColors(backgroundColor = LumenColors.danger, contentColor = Color.White),
            ) { Text(if (leaving) "Leaving…" else "Leave space") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !leaving) { Text("Cancel", color = LumenColors.muted) }
        },
        backgroundColor = LumenColors.card,
        contentColor = LumenColors.text,
    )
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
        Modifier.fillMaxSize().background(Color(0xCC000000)).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.fillMaxWidth(),
            backgroundColor = LumenColors.card,
            shape = panelShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, LumenColors.border),
            elevation = 0.dp,
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Add a trusted device", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                Text("Scan this code or copy the invite. You will approve the new device before anything is shared.", color = LumenColors.muted)
                invite?.let { value ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(value, platform) }
                    Text(value, color = LumenColors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.caption)
                    OutlinedButton(
                        onClick = { scope.launch { platform.copyToClipboard(value) } },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LumenColors.accent),
                    ) { Text("Copy invite") }
                } ?: CircularProgressIndicator(color = LumenColors.accent)
                error?.let { ErrorNote(it) }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close", color = LumenColors.muted) }
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
    SyncPhase.UP_TO_DATE -> LumenColors.success
    SyncPhase.ATTENTION, SyncPhase.WAITING_FOR_PEER -> LumenColors.accent
    SyncPhase.FAILED -> LumenColors.danger
    else -> LumenColors.muted
}
