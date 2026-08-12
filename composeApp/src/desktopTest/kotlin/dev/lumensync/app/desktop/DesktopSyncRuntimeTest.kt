package dev.lumensync.app.desktop

import dev.lumensync.app.syncthing.syncthingGuiBaseUrl
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.isExecutable
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
    fun acceptsExplicitHttpLoopback() {
        val config = """<configuration><gui><address>http://localhost:8384</address></gui></configuration>"""

        assertEquals("http://localhost:8384", syncthingGuiBaseUrl(config))
    }

    @Test
    fun rejectsNonLocalOrInvalidGuiAddresses() {
        assertNull(syncthingGuiBaseUrl("<gui><address>0.0.0.0:8384</address></gui>"))
        assertNull(syncthingGuiBaseUrl("<gui><address>example.com:8384</address></gui>"))
        assertNull(syncthingGuiBaseUrl("<gui><address>https://127.0.0.1:8384</address></gui>"))
        assertNull(syncthingGuiBaseUrl("<gui><address>127.0.0.1</address></gui>"))
    }

    @Test
    fun materializesNonExecutableLinuxCoreAsExecutable() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val temporaryDirectory = Files.createTempDirectory("lumen-sync-test-")
        try {
            val source = temporaryDirectory.resolve("packaged-syncthing")
            source.writeText("#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(
                source,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )

            val copied = copyLinuxExecutable(source, temporaryDirectory.resolve("state"))

            assertTrue(copied.isExecutable())
            assertEquals(source.readText(), copied.readText())
        } finally {
            temporaryDirectory.toFile().deleteRecursively()
        }
    }
}
