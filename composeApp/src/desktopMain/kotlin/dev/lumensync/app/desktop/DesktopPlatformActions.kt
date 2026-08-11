package dev.lumensync.app.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.lumensync.app.platform.PlatformActions
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

class DesktopPlatformActions(
    private val onStart: suspend () -> Unit,
    private val onStop: suspend () -> Unit,
) : PlatformActions {
    override val platformName: String = System.getProperty("os.name")
    override val defaultDeviceName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("My computer")
    override val canScanQr: Boolean = false
    override val usesManualSessions: Boolean = false

    override suspend fun chooseFolder(): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose the Lumen Sync folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    override suspend fun copyToClipboard(value: String) = withContext(Dispatchers.Swing) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }

    override suspend fun scanInvite(): String? = null
    override suspend fun startSyncSession() = onStart()
    override suspend fun stopSyncSession() = onStop()

    override fun qrMatrix(value: String, size: Int): List<List<Boolean>> {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        return List(matrix.height) { y -> List(matrix.width) { x -> matrix[x, y] } }
    }

    override fun configureAutostart(enabled: Boolean) {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("win")) configureWindowsAutostart(enabled) else configureLinuxAutostart(enabled)
    }

    private fun configureWindowsAutostart(enabled: Boolean) {
        val executable = ProcessHandle.current().info().command().orElse(null) ?: return
        val base = listOf("reg.exe", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "LumenSync")
        val command = if (enabled) base + listOf("/t", "REG_SZ", "/d", "\"$executable\"", "/f")
        else listOf("reg.exe", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "LumenSync", "/f")
        runCatching { ProcessBuilder(command).start().waitFor() }
    }

    private fun configureLinuxAutostart(enabled: Boolean) {
        val directory = Path.of(System.getProperty("user.home"), ".config", "autostart")
        val file = directory.resolve("lumen-sync.desktop")
        if (!enabled) {
            runCatching { Files.deleteIfExists(file) }
            return
        }
        val executable = ProcessHandle.current().info().command().orElse(null) ?: return
        Files.createDirectories(directory)
        Files.writeString(
            file,
            """[Desktop Entry]
Type=Application
Name=Lumen Sync
Exec=${executable.replace(" ", "\\ ")}
Terminal=false
X-GNOME-Autostart-enabled=true
""",
        )
    }
}
