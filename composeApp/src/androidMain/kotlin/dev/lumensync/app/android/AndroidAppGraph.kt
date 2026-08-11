package dev.lumensync.app.android

import android.content.Context
import dev.lumensync.app.DefaultSyncEngine
import dev.lumensync.app.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidAppGraph private constructor(context: Context) {
    private val initializationMutex = Mutex()
    private var initialized = false
    @Volatile var serviceRunning: Boolean = false
    val runtime = AndroidSyncRuntime(context)
    val engine: SyncEngine = DefaultSyncEngine(
        runtime = runtime,
        settingsStore = AndroidSettingsStore(context),
        httpClientFactory = { HttpClient(OkHttp) },
    )

    suspend fun initialize() = initializationMutex.withLock {
        if (!initialized) {
            engine.initialize(startRuntime = false)
            initialized = true
        }
    }

    companion object {
        @Volatile private var instance: AndroidAppGraph? = null

        fun create(context: Context): AndroidAppGraph = instance ?: synchronized(this) {
            instance ?: AndroidAppGraph(context.applicationContext).also { instance = it }
        }

        fun get(context: Context): AndroidAppGraph = create(context)
    }
}
