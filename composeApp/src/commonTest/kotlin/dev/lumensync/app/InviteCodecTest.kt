package dev.lumensync.app

import dev.lumensync.app.model.InviteV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class InviteCodecTest {
    private val deviceId = "ABCDEFG-HIJKLMN-OPQRSTU-VWXYZ23-4567ABC-DEFGHIJ-KLMNOPQ-RSTUVWX"

    @Test
    fun roundTripsVersionedInviteWithoutPaddingCharacters() {
        val invite = InviteV1(folderId = "abcde-23456", deviceId = deviceId, deviceName = "Living Room PC")

        val encoded = InviteCodec.encode(invite)

        assertFalse(encoded.contains('='))
        assertEquals(invite, InviteCodec.decode(encoded))
    }

    @Test
    fun rejectsForeignLinks() {
        assertFailsWith<IllegalArgumentException> {
            InviteCodec.decode("https://example.com/invite")
        }
    }

    @Test
    fun rejectsMalformedDeviceIds() {
        assertFailsWith<IllegalArgumentException> {
            InviteV1(folderId = "abcde-23456", deviceId = "not-a-device", deviceName = "Phone")
        }
    }

    @Test
    fun rejectsFolderIdsThatCouldEscapeARestPath() {
        assertFailsWith<IllegalArgumentException> {
            InviteV1(folderId = "../../config", deviceId = deviceId, deviceName = "Phone")
        }
    }
}
