package dev.lumensync.app

import dev.lumensync.app.model.InviteV1
import kotlinx.serialization.json.Json

object InviteCodec {
    private const val PREFIX = "lumensync://invite/"
    private val json = Json { encodeDefaults = true }

    fun encode(invite: InviteV1): String {
        val payload = json.encodeToString(InviteV1.serializer(), invite)
        return PREFIX + Base64Url.encode(payload.encodeToByteArray())
    }

    fun decode(value: String): InviteV1 {
        val normalized = value.trim()
        require(normalized.startsWith(PREFIX)) { "This is not a Lumen Sync invite" }
        val bytes = Base64Url.decode(normalized.removePrefix(PREFIX))
        return json.decodeFromString(InviteV1.serializer(), bytes.decodeToString())
    }
}

internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(input: ByteArray): String {
        val output = StringBuilder((input.size * 4 + 2) / 3)
        var index = 0
        while (index < input.size) {
            val a = input[index++].toInt() and 0xff
            val b = if (index < input.size) input[index++].toInt() and 0xff else -1
            val c = if (index < input.size) input[index++].toInt() and 0xff else -1
            output.append(ALPHABET[a ushr 2])
            output.append(ALPHABET[((a and 3) shl 4) or if (b >= 0) b ushr 4 else 0])
            if (b >= 0) output.append(ALPHABET[((b and 15) shl 2) or if (c >= 0) c ushr 6 else 0])
            if (c >= 0) output.append(ALPHABET[c and 63])
        }
        return output.toString()
    }

    fun decode(input: String): ByteArray {
        require(input.all { it in ALPHABET }) { "Invite contains invalid characters" }
        val output = ArrayList<Byte>(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        input.forEach { character ->
            buffer = (buffer shl 6) or ALPHABET.indexOf(character)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output += ((buffer ushr bits) and 0xff).toByte()
            }
        }
        return output.toByteArray()
    }
}

