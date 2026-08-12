package dev.lumensync.app.syncthing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import dev.lumensync.app.platform.RuntimeConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncthingRestClientTest {
    @Test
    fun nullFolderErrorsMeansNoErrors() {
        val response = Json.parseToJsonElement(
            """{"errors":null,"folder":"worvu-s5zor","page":1,"perpage":65536}""",
        ).jsonObject

        assertTrue(parseFolderErrors(response).isEmpty())
    }

    @Test
    fun folderErrorsRetainPathAndMessage() {
        val response = Json.parseToJsonElement(
            """{"errors":[{"path":"notes.txt","error":"access denied"}]}""",
        ).jsonObject

        assertEquals(listOf("notes.txt: access denied"), parseFolderErrors(response))
    }

    @Test
    fun localCleanupUsesFolderAndDeviceDeleteEndpoints() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    respond("", HttpStatusCode.OK)
                }
            }
        }
        val client = SyncthingRestClient.create(
            http,
            RuntimeConnection("http://127.0.0.1:8384", "test-key"),
        )

        client.removeDevice("remote-device")
        client.removeFolder("space-id")
        client.close()

        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Delete), requests.map { it.method })
        assertEquals(
            listOf("/rest/config/devices/remote-device", "/rest/config/folders/space-id"),
            requests.map { it.url.encodedPath },
        )
    }
}
