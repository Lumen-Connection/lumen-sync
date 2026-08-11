package dev.lumensync.app.syncthing

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
}
