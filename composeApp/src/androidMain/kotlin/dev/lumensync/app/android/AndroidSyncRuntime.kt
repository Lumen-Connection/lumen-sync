package dev.lumensync.app.android

import android.content.Context
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
import java.io.File
import java.net.InetAddress
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

    override val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    override suspend fun start(): RuntimeConnection = mutex.withLock {
        connection?.takeIf { process?.isAlive == true }?.let { return@withLock it }
        val binary = File(context.applicationInfo.nativeLibraryDir, "libsyncthing.so")
        check(binary.exists()) {
            "Syncthing native core is missing. Run the buildSyncthingAndroid task before packaging the APK."
        }
        val home = File(context.filesDir, "syncthing").also { it.mkdirs() }
        val log = File(context.filesDir, "syncthing.log")
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val runtimeConnection = RuntimeConnection("http://127.0.0.1:$port", loadOrCreateApiKey())
        stopping = false
        restartCount = 0
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

    private fun startProcess(binary: File, home: File, log: File, runtimeConnection: RuntimeConnection) {
        process = ProcessBuilder(
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
            .start()
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
            started.waitFor()
            if (process !== started) return@launch
            mutableRunning.value = false
            if (!stopping && restartCount < 3) {
                restartCount += 1
                delay(500L * (1 shl (restartCount - 1)))
                runCatching { startProcess(binary, home, log, runtimeConnection) }
            }
        }
    }

    private fun loadOrCreateApiKey(): String {
        val preferences = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
        preferences.getString("api_key", null)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        preferences.edit().putString("api_key", key).commit()
        return key
    }
}

