package dev.lumensync.app.android

import android.content.Context
import dev.lumensync.app.platform.RuntimeConnection
import dev.lumensync.app.platform.SyncRuntime
import dev.lumensync.app.syncthing.syncthingGuiBaseUrl
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
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

class AndroidSyncRuntime(private val context: Context) : SyncRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableRunning = MutableStateFlow(false)
    private var process: Process? = null
    private var connection: RuntimeConnection? = null
    private var stopping = false
    private var restartCount = 0
    @Volatile private var lastFailure: String? = null

    override val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    override suspend fun start(): RuntimeConnection = mutex.withLock {
        connection?.let { current ->
            if (process?.isAlive == true || apiAvailable(current)) return@withLock current
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libsyncthing.so")
        check(binary.exists()) {
            "Syncthing native core is missing. Run the buildSyncthingAndroid task before packaging the APK."
        }
        check(binary.canExecute() || binary.setExecutable(true, true)) {
            "The packaged Syncthing core is not executable on this device."
        }
        val home = File(context.filesDir, "syncthing").also { it.mkdirs() }
        val log = File(context.filesDir, "syncthing.log")
        val apiKey = loadOrCreateApiKey()
        stopping = false
        restartCount = 0
        lastFailure = null

        findExistingConnection(home, apiKey)?.let { existing ->
            connection = existing
            mutableRunning.value = true
            return@withLock existing
        }

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val runtimeConnection = RuntimeConnection("http://127.0.0.1:$port", apiKey)
        startProcess(binary, home, log, runtimeConnection)
        connection = runtimeConnection
        runtimeConnection
    }

    override suspend fun stop() = mutex.withLock {
        stopping = true
        process?.let { current ->
            if (current.isAlive) {
                if (!current.waitFor(4, TimeUnit.SECONDS)) {
                    current.destroy()
                    if (!current.waitFor(2, TimeUnit.SECONDS)) current.destroyForcibly()
                }
            }
        }
        process = null
        connection = null
        mutableRunning.value = false
    }

    override fun startupFailure(): String? = lastFailure

    private fun startProcess(binary: File, home: File, log: File, runtimeConnection: RuntimeConnection) {
        val builder = ProcessBuilder(
            binary.absolutePath,
            "serve",
            "--home=${home.absolutePath}",
            "--gui-address=${runtimeConnection.baseUrl}",
            "--gui-apikey=${runtimeConnection.apiKey}",
            "--no-browser",
            "--no-restart",
            "--no-upgrade",
            "--log-file=${log.absolutePath}",
            "--log-max-size=1048576",
            "--log-max-old-files=3",
        ).directory(context.filesDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        builder.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("STHOMEDIR", home.absolutePath)
            put("STMONITORED", "1")
            put("STNOUPGRADE", "1")
            put("SQLITE_TMPDIR", context.cacheDir.absolutePath)
        }
        process = builder.start()
        mutableRunning.value = true
        supervise(process!!, binary, home, log, runtimeConnection)
    }

    private fun supervise(
        started: Process,
        binary: File,
        home: File,
        log: File,
        runtimeConnection: RuntimeConnection,
    ) {
        scope.launch {
            val exitCode = started.waitFor()
            if (process !== started) return@launch
            mutableRunning.value = false
            if (!stopping && restartCount < 3) {
                restartCount += 1
                delay(500L * (1 shl (restartCount - 1)))
                if (!stopping && process === started) {
                    runCatching { startProcess(binary, home, log, runtimeConnection) }
                        .onFailure { lastFailure = it.message ?: "Syncthing could not be restarted" }
                }
            } else if (!stopping) {
                lastFailure = syncthingExitMessage(exitCode, log)
            }
        }
    }

    private suspend fun findExistingConnection(home: File, apiKey: String): RuntimeConnection? = withContext(Dispatchers.IO) {
        val configFile = File(home, "config.xml")
        if (!configFile.exists()) return@withContext null
        val baseUrl = runCatching { syncthingGuiBaseUrl(configFile.readText()) }.getOrNull()
            ?: return@withContext null
        RuntimeConnection(baseUrl, apiKey).takeIf { apiAvailableBlocking(it) }
    }

    private suspend fun apiAvailable(runtimeConnection: RuntimeConnection): Boolean = withContext(Dispatchers.IO) {
        apiAvailableBlocking(runtimeConnection)
    }

    private fun apiAvailableBlocking(runtimeConnection: RuntimeConnection): Boolean = runCatching {
        val request = URI.create("${runtimeConnection.baseUrl}/rest/system/ping")
            .toURL()
            .openConnection() as HttpURLConnection
        try {
            request.requestMethod = "GET"
            request.connectTimeout = 750
            request.readTimeout = 750
            request.setRequestProperty("X-API-Key", runtimeConnection.apiKey)
            request.responseCode in 200..299
        } finally {
            request.disconnect()
        }
    }.getOrDefault(false)

    private fun loadOrCreateApiKey(): String {
        val preferences = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        preferences.getString("api_key", null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        preferences.edit().putString("api_key", key).commit()
        return key
    }
}

private fun syncthingExitMessage(exitCode: Int, logFile: File): String {
    val detail = runCatching {
        logFile.useLines { lines -> lines.filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n") }
    }.getOrDefault("")
    return buildString {
        append("Syncthing exited with code ").append(exitCode)
        if (detail.isNotBlank()) append(":\n").append(detail)
    }
}
