package dev.lumensync.app.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.lumensync.app.platform.PlatformActions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class AndroidPlatformActions(private val activity: ComponentActivity) : PlatformActions {
    private var folderContinuation: ((String?) -> Unit)? = null
    private var permissionContinuation: ((Boolean) -> Unit)? = null
    private var storagePermissionContinuation: ((Boolean) -> Unit)? = null
    private var scanContinuation: ((String?) -> Unit)? = null

    private val folderLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::treeUriToPath)?.takeIf(::isAllowedFolder)
        } else null
        folderContinuation?.invoke(path)
        folderContinuation = null
    }

    private val allFilesLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        permissionContinuation?.invoke(Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
        permissionContinuation = null
    }

    private val storagePermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        storagePermissionContinuation?.invoke(results.values.all { it })
        storagePermissionContinuation = null
    }

    private val scannerLauncher = activity.registerForActivityResult(ScanContract()) { result ->
        scanContinuation?.invoke(result.contents)
        scanContinuation = null
    }

    override val platformName: String = "Android"
    override val defaultDeviceName: String = Build.MODEL.ifBlank { "My Android device" }
    override val canScanQr: Boolean = true
    override val usesManualSessions: Boolean = true

    override suspend fun chooseFolder(): String? {
        if (!ensureFileAccess()) return null
        return suspendCancellableCoroutine { continuation ->
            folderContinuation = { continuation.resume(it) }
            continuation.invokeOnCancellation { folderContinuation = null }
            folderLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
        }
    }

    override suspend fun openFolder(path: String) {
        if (!ensureFileAccess()) {
            error("Storage permission is required to open the synced folder.")
        }
        val treeUri = pathToTreeUri(path)
            ?: error("Unable to open the synced folder in the file manager.")
        activity.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        })
    }

    override suspend fun copyToClipboard(value: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Lumen Sync invite", value))
    }

    override suspend fun scanInvite(): String? = suspendCancellableCoroutine { continuation ->
        scanContinuation = { continuation.resume(it) }
        continuation.invokeOnCancellation { scanContinuation = null }
        scannerLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan a Lumen Sync invite")
                .setBeepEnabled(false),
        )
    }

    override suspend fun startSyncSession() {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
        val graph = AndroidAppGraph.get(activity)
        graph.serviceRunning = true
        try {
            ContextCompat.startForegroundService(
                activity,
                Intent(activity, SyncForegroundService::class.java).setAction(SyncForegroundService.ACTION_START),
            )
        } catch (error: Throwable) {
            graph.serviceRunning = false
            throw error
        }
    }

    override suspend fun stopSyncSession() {
        activity.startService(
            Intent(activity, SyncForegroundService::class.java).setAction(SyncForegroundService.ACTION_STOP),
        )
    }

    override fun qrMatrix(value: String, size: Int): List<List<Boolean>> {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        return List(matrix.height) { y -> List(matrix.width) { x -> matrix[x, y] } }
    }

    override fun configureAutostart(enabled: Boolean) = Unit

    private suspend fun ensureFileAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) {
            val permissions = buildList {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (permissions.all {
                    activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
            ) {
                return true
            }
            return suspendCancellableCoroutine { continuation ->
                storagePermissionContinuation = { continuation.resume(it) }
                continuation.invokeOnCancellation { storagePermissionContinuation = null }
                storagePermissionLauncher.launch(permissions.toTypedArray())
            }
        }
        if (Environment.isExternalStorageManager()) return true
        return suspendCancellableCoroutine { continuation ->
            permissionContinuation = { continuation.resume(it) }
            continuation.invokeOnCancellation { permissionContinuation = null }
            allFilesLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
    }

    private fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        val volume = parts.firstOrNull() ?: return null
        val relative = parts.getOrNull(1).orEmpty()
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volume)
        }
        return File(root, relative).canonicalPath
    }

    private fun pathToTreeUri(path: String): Uri? {
        val folder = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
        if (primaryRoot != null && isWithin(folder, primaryRoot)) {
            val relative = if (folder == primaryRoot) {
                ""
            } else {
                folder.relativeTo(primaryRoot).path.replace(File.separatorChar, '/')
            }
            return DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:$relative",
            )
        }

        val storageRoot = runCatching { File("/storage").canonicalFile }.getOrNull() ?: return null
        if (!isWithin(folder, storageRoot)) return null
        val pathParts = folder.relativeTo(storageRoot).path
            .replace(File.separatorChar, '/')
            .split('/')
            .filter(String::isNotEmpty)
        val volume = pathParts.firstOrNull() ?: return null
        val relative = pathParts.drop(1).joinToString("/")
        return DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "$volume:$relative",
        )
    }

    private fun isWithin(path: File, root: File): Boolean =
        path == root || path.path.startsWith(root.path + File.separator)

    private fun isAllowedFolder(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        if (normalized.contains("/android/data") || normalized.contains("/android/obb")) return false
        val folder = File(path)
        return folder.exists() && folder.isDirectory && folder.canRead() && folder.canWrite()
    }
}
