package dev.lumensync.app.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SingleInstanceGuard private constructor(
    private val server: ServerSocket,
    private val onActivate: () -> Unit,
) : AutoCloseable {
    init {
        thread(name = "lumen-sync-activation", isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept().use { it.getInputStream().read() } }
                    .onSuccess { onActivate() }
            }
        }
    }

    override fun close() {
        server.close()
    }

    companion object {
        private const val PORT = 43121

        fun acquire(onActivate: () -> Unit): SingleInstanceGuard? = runCatching {
            SingleInstanceGuard(ServerSocket(PORT, 1, InetAddress.getLoopbackAddress()), onActivate)
        }.getOrNull()

        fun activateExisting(): Boolean = runCatching {
            Socket(InetAddress.getLoopbackAddress(), PORT).use { it.getOutputStream().write(1) }
            true
        }.getOrDefault(false)
    }
}

