package dev.lumensync.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSyncRuntimeTest {
    @Test
    fun readsGuiAddressWithoutConfusingItWithOtherAddresses() {
        val config = """
            <configuration>
                <folder><address>dynamic</address></folder>
                <gui enabled="true">
                    <address>127.0.0.1:57041</address>
                </gui>
            </configuration>
        """.trimIndent()

        assertEquals("http://127.0.0.1:57041", syncthingGuiBaseUrl(config))
    }

    @Test
    fun acceptsIpv6Loopback() {
        val config = """<configuration><gui><address>[::1]:8384</address></gui></configuration>"""

        assertEquals("http://[::1]:8384", syncthingGuiBaseUrl(config))
    }

    @Test
    fun rejectsNonLocalOrInvalidGuiAddresses() {
        assertNull(syncthingGuiBaseUrl("<gui><address>0.0.0.0:8384</address></gui>"))
        assertNull(syncthingGuiBaseUrl("<gui><address>example.com:8384</address></gui>"))
        assertNull(syncthingGuiBaseUrl("<gui><address>127.0.0.1</address></gui>"))
    }
}
