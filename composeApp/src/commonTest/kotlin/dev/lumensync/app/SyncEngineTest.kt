package dev.lumensync.app

import dev.lumensync.app.model.AppSettings
import dev.lumensync.app.platform.RuntimeConnection
import dev.lumensync.app.platform.SettingsStore
import dev.lumensync.app.platform.SyncRuntime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineTest {
    @Test
    fun leavingRemovesLocalPeersAndFolderButKeepsTheSelectedPath() = runBlocking {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val runtime = FakeRuntime()
        val store = FakeSettingsStore(
            AppSettings(
                deviceName = "Laptop",
                localFolder = "C:/Sync folder",
                folderId = "space-id",
                desktopAutostart = true,
            ),
        )
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request.method to request.url.encodedPath
                    respondJson(
                        when (request.url.encodedPath) {
                            "/rest/system/ping" -> "{\"ping\":\"pong\"}"
                            "/rest/system/status" -> "{\"myID\":\"LOCAL\"}"
                            "/rest/config/devices" -> "[{\"deviceID\":\"LOCAL\",\"name\":\"Laptop\"},{\"deviceID\":\"PEER\",\"name\":\"Phone\"}]"
                            "/rest/system/connections" -> "{\"connections\":{}}"
                            "/rest/cluster/pending/devices" -> "{}"
                            else -> "{}"
                        },
                    )
                }
            }
        }
        val engine = DefaultSyncEngine(
            runtime = runtime,
            settingsStore = store,
            httpClientFactory = { client },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        engine.initialize(startRuntime = false)
        engine.leaveSpace()

        assertEquals(AppSettings(), store.value)
        assertFalse(runtime.running.value)
        assertTrue(requests.contains(HttpMethod.Delete to "/rest/config/devices/PEER"))
        assertTrue(requests.contains(HttpMethod.Delete to "/rest/config/folders/space-id"))
        assertTrue(requests.contains(HttpMethod.Post to "/rest/system/shutdown"))
        assertEquals("C:/Sync folder", store.original.localFolder)
    }

    @Test
    fun leaveFailureKeepsSetupAvailableForRetry() = runBlocking {
        val runtime = FakeRuntime()
        val original = AppSettings(deviceName = "Laptop", localFolder = "C:/Sync", folderId = "space-id")
        val store = FakeSettingsStore(original)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.method == HttpMethod.Delete && request.url.encodedPath.contains("folders")) {
                        respond("failed", HttpStatusCode.InternalServerError)
                    } else {
                        respondJson(
                            when (request.url.encodedPath) {
                                "/rest/system/ping" -> "{\"ping\":\"pong\"}"
                                "/rest/system/status" -> "{\"myID\":\"LOCAL\"}"
                                "/rest/config/devices" -> "[{\"deviceID\":\"LOCAL\"}]"
                                "/rest/system/connections" -> "{\"connections\":{}}"
                                "/rest/cluster/pending/devices" -> "{}"
                                else -> "{}"
                            },
                        )
                    }
                }
            }
        }
        val engine = DefaultSyncEngine(
            runtime = runtime,
            settingsStore = store,
            httpClientFactory = { client },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        engine.initialize(startRuntime = false)
        val failure = runCatching { engine.leaveSpace() }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(original, store.value)
        assertTrue(runtime.running.value)
        engine.stop()
    }

    private class FakeRuntime : SyncRuntime {
        private val mutableRunning = MutableStateFlow(false)
        override val running: StateFlow<Boolean> = mutableRunning

        override suspend fun start(): RuntimeConnection {
            mutableRunning.value = true
            return RuntimeConnection("http://127.0.0.1:8384", "test-key")
        }

        override suspend fun stop() {
            mutableRunning.value = false
        }
    }

    private class FakeSettingsStore(initial: AppSettings) : SettingsStore {
        var value = initial
        val original = initial

        override suspend fun load(): AppSettings = value

        override suspend fun save(settings: AppSettings) {
            value = settings
        }
    }
}

private fun MockRequestHandleScope.respondJson(body: String) = respond(
    body,
    HttpStatusCode.OK,
    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)
