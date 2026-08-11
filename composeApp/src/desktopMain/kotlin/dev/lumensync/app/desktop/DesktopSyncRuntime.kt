package dev.lumensync.app.desktop

import dev.lumensync.app.platform.RuntimeConnection
import dev.lumensync.app.platform.SyncRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DesktopSyncRuntime : SyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableRunning = MutableStateFlow(false)
    private var process: Process? = null
    private var stopping = false
    private var connection: RuntimeConnection? = null
    private var restartCount = 0

    override val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    override suspend fun start(): RuntimeConnection = mutex.withLock {
        connection?.takeIf { process?.isAlive == true }?.let { return@withLock it }
        stopping = false
        restartCount = 0
        val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val apiKey = loadOrCreateApiKey()
        val runtimeConnection = RuntimeConnection("http://127.0.0.1:$port", apiKey)
        startProcess(runtimeConnection)
        connection = runtimeConnection
        runtimeConnection
    }

    override suspend fun stop() = mutex.withLock {
        stopping = true
        val current = process
        if (current != null && current.isAlive) {
            if (!current.waitFor(4, TimeUnit.SECONDS)) {
                current.destroy()
                if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
            }
        }
        process = null
        connection = null
        mutableRunning.value = false
    }

    private fun startProcess(runtimeConnection: RuntimeConnection) {
        val command = mutableListOf(
            findSyncthingBinary(),
            "serve",
            "--home=${DesktopPaths.syncthingHome.absolutePathString()}",
            "--gui-address=${runtimeConnection.baseUrl}",
            "--gui-apikey=${runtimeConnection.apiKey}",
            "--no-browser",
            "--no-restart",
            "--no-upgrade",
            "--log-file=${DesktopPaths.logFile.absolutePathString()}",
            "--log-max-size=1048576",
            "--log-max-old-files=3",
        )
        if (System.getProperty("os.name").lowercase().contains("win")) command += "--no-console"

        process = ProcessBuilder(command)
            .directory(DesktopPaths.appData.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(DesktopPaths.logFile.toFile()))
            .start()
        mutableRunning.value = true
        supervise(process!!, runtimeConnection)
    }

    private fun supervise(started: Process, runtimeConnection: RuntimeConnection) {
        scope.launch {
            started.waitFor()
            if (process !== started) return@launch
            mutableRunning.value = false
            if (!stopping && restartCount < 3) {
                restartCount += 1
                delay(500L * (1 shl (restartCount - 1)))
                runCatching { startProcess(runtimeConnection) }
            }
        }
    }

    private fun findSyncthingBinary(): String {
        System.getenv("LUMEN_SYNC_SYNCTHING")?.takeIf { it.isNotBlank() }?.let { return it }
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val executable = if (isWindows) "syncthing.exe" else "syncthing"
        val resourcePlatform = if (isWindows) "windows" else "linux"
        val candidates = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it, executable)) }
            add(Path.of("composeApp", "src", "desktopMain", "appResources", resourcePlatform, executable))
            add(Path.of("src", "desktopMain", "appResources", resourcePlatform, executable))
            add(Path.of("third_party", "syncthing", "bin", executable))
            add(Path.of("third_party", "syncthing", executable))
        }
        candidates.firstOrNull { it.exists() && (isWindows || it.isExecutable()) }
            ?.let { return it.toAbsolutePath().toString() }
        return executable
    }

    private fun loadOrCreateApiKey(): String {
        if (DesktopPaths.apiKeyFile.exists()) return DesktopPaths.apiKeyFile.readText().trim()
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        DesktopPaths.apiKeyFile.writeText(key)
        runCatching {
            Files.setPosixFilePermissions(
                DesktopPaths.apiKeyFile,
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            )
        }
        return key
    }
}
