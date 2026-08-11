package dev.lumensync.app.syncthing

import dev.lumensync.app.model.DeviceSummary
import dev.lumensync.app.model.SyncthingFolderStatus
import dev.lumensync.app.model.SyncthingSystemStatus
import dev.lumensync.app.platform.RuntimeConnection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SyncthingRestClient(
    private val httpClient: HttpClient,
    private val connection: RuntimeConnection,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun ping(): Boolean = runCatching {
        get("/rest/system/ping").jsonObject["ping"]?.jsonPrimitive?.content == "pong"
    }.getOrDefault(false)

    suspend fun systemStatus(): SyncthingSystemStatus =
        json.decodeFromJsonElement(SyncthingSystemStatus.serializer(), get("/rest/system/status"))

    suspend fun folderStatus(folderId: String): SyncthingFolderStatus =
        json.decodeFromJsonElement(
            SyncthingFolderStatus.serializer(),
            get("/rest/db/status", mapOf("folder" to folderId)),
        )

    suspend fun configuredDevices(): List<DeviceSummary> {
        val configured = get("/rest/config/devices").jsonArray
        val connections = get("/rest/system/connections").jsonObject["connections"].jsonObjectOrEmpty()
        return configured.map { item ->
            val device = item.jsonObject
            val id = device.string("deviceID")
            val connection = connections[id] as? JsonObject
            DeviceSummary(
                id = id,
                name = device.string("name").ifBlank { shortId(id) },
                connected = connection?.get("connected")?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }

    suspend fun pendingDevices(): List<DeviceSummary> {
        val response = get("/rest/cluster/pending/devices").jsonObject
        return response.map { (id, value) ->
            DeviceSummary(
                id = id,
                name = value.jsonObject.string("name").ifBlank { shortId(id) },
                connected = false,
                pending = true,
            )
        }
    }

    suspend fun pendingFolders(): Map<String, List<String>> {
        val response = get("/rest/cluster/pending/folders").jsonObject
        return response.mapValues { (_, value) ->
            value.jsonObject["offeredBy"].jsonObjectOrEmpty().keys.toList()
        }
    }

    suspend fun createFolder(folderId: String, path: String, deviceIds: List<String> = emptyList()) {
        val localDeviceId = systemStatus().myId
        val template = get("/rest/config/defaults/folder").jsonObject.toMutableMap()
        template["id"] = JsonPrimitive(folderId)
        template["label"] = JsonPrimitive("Lumen Sync")
        template["path"] = JsonPrimitive(path)
        template["type"] = JsonPrimitive("sendreceive")
        template["fsWatcherEnabled"] = JsonPrimitive(true)
        template["rescanIntervalS"] = JsonPrimitive(3600)
        template["versioning"] = buildJsonObject { put("type", JsonPrimitive("")) }
        template["devices"] = buildJsonArray {
            (deviceIds + localDeviceId).filter { it.isNotBlank() }.distinct().forEach { id ->
                add(buildJsonObject { put("deviceID", JsonPrimitive(id)) })
            }
        }
        postJson("/rest/config/folders", JsonObject(template))
    }

    suspend fun addDevice(deviceId: String, name: String, folderId: String? = null) {
        val template = get("/rest/config/defaults/device").jsonObject.toMutableMap()
        template["deviceID"] = JsonPrimitive(deviceId)
        template["name"] = JsonPrimitive(name)
        template["addresses"] = JsonArray(listOf(JsonPrimitive("dynamic")))
        template["introducer"] = JsonPrimitive(false)
        postJson("/rest/config/devices", JsonObject(template))

        if (!folderId.isNullOrBlank()) {
            val folder = get("/rest/config/folders/$folderId").jsonObject
            val devices = folder["devices"].jsonArrayOrEmpty()
            if (devices.none { it.jsonObject.string("deviceID") == deviceId }) {
                patchJson(
                    "/rest/config/folders/$folderId",
                    buildJsonObject {
                        put("devices", JsonArray(devices + buildJsonObject {
                            put("deviceID", JsonPrimitive(deviceId))
                        }))
                    },
                )
            }
        }
    }

    suspend fun dismissPendingDevice(deviceId: String) {
        delete("/rest/cluster/pending/devices", mapOf("device" to deviceId))
    }

    suspend fun dismissPendingFolder(folderId: String) {
        delete("/rest/cluster/pending/folders", mapOf("folder" to folderId))
    }

    suspend fun scan(folderId: String) {
        post("/rest/db/scan", mapOf("folder" to folderId))
    }

    suspend fun folderErrors(folderId: String): List<String> {
        val response = get("/rest/folder/errors", mapOf("folder" to folderId)).jsonObject
        return parseFolderErrors(response)
    }

    suspend fun setLocalDeviceName(name: String) {
        val localId = systemStatus().myId
        if (name.isNotBlank() && localId.isNotBlank()) {
            runCatching {
                patchJson(
                    "/rest/config/devices/$localId",
                    buildJsonObject { put("name", JsonPrimitive(name.trim())) },
                )
            }
        }
        patchJson("/rest/config/options", buildJsonObject { put("urAccepted", JsonPrimitive(-1)) })
        patchJson("/rest/config/gui", buildJsonObject { put("insecureAdminAccess", JsonPrimitive(false)) })
        patchJson(
            "/rest/config/options",
            buildJsonObject { put("localAnnounceEnabled", JsonPrimitive(true)) },
        )
    }

    suspend fun shutdown() {
        runCatching { post("/rest/system/shutdown") }
    }

    suspend fun close() {
        httpClient.close()
    }

    private suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): JsonElement {
        val response = httpClient.get(connection.baseUrl + path) {
            header("X-API-Key", connection.apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }
        check(response.status.isSuccess()) { "$path failed: ${response.status}: ${response.bodyAsText()}" }
        return response.body<JsonElement>()
    }

    private suspend fun post(path: String, parameters: Map<String, String> = emptyMap()) {
        val response = httpClient.post(connection.baseUrl + path) {
            header("X-API-Key", connection.apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }
        check(response.status.isSuccess()) { "$path failed: ${response.status}: ${response.bodyAsText()}" }
    }

    private suspend fun postJson(path: String, body: JsonElement) {
        val response = httpClient.post(connection.baseUrl + path) {
            header("X-API-Key", connection.apiKey)
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        check(response.status.isSuccess()) { "$path failed: ${response.status}: ${response.bodyAsText()}" }
    }

    private suspend fun patchJson(path: String, body: JsonElement) {
        val response = httpClient.patch(connection.baseUrl + path) {
            header("X-API-Key", connection.apiKey)
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }
        check(response.status.isSuccess()) { "$path failed: ${response.status}: ${response.bodyAsText()}" }
    }

    private suspend fun delete(path: String, parameters: Map<String, String>) {
        val response = httpClient.delete(connection.baseUrl + path) {
            header("X-API-Key", connection.apiKey)
            parameters.forEach { (name, value) -> parameter(name, value) }
        }
        check(response.status.isSuccess()) { "$path failed: ${response.status}: ${response.bodyAsText()}" }
    }

    companion object {
        fun create(httpClient: HttpClient, connection: RuntimeConnection): SyncthingRestClient =
            SyncthingRestClient(
                httpClient.config {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                    }
                },
                connection,
            )

        private fun shortId(id: String): String = id.substringBefore('-').ifBlank { "Unknown device" }
    }
}

private fun JsonObject.string(name: String): String = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonElement?.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement?.jsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

internal fun parseFolderErrors(response: JsonObject): List<String> =
    response["errors"].jsonArrayOrEmpty().map { error ->
        val item = error.jsonObject
        listOf(item.string("path"), item.string("error")).filter { it.isNotBlank() }.joinToString(": ")
    }
