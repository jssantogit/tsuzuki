package eu.kanade.tachiyomi.data.backup.create

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ManualBackupDestinationPolicyTest {

    @Test
    fun `empty newly created document is eligible for a backup`() {
        assertDoesNotThrow {
            ManualBackupDestinationPolicy.requireEmpty(ByteArrayInputStream(byteArrayOf()))
        }
    }

    @Test
    fun `existing document bytes must never be overwritten`() {
        assertThrows(IOException::class.java) {
            ManualBackupDestinationPolicy.requireEmpty(ByteArrayInputStream(byteArrayOf(0x1f, 0x8b.toByte())))
        }
    }

    @Test
    fun `missing readable document fails closed before writing`() {
        assertThrows(IOException::class.java) {
            ManualBackupDestinationPolicy.requireEmpty(null)
        }
    }

    @Test
    fun `copy of a verified archive writes exactly the staged bytes`() {
        val staged = File.createTempFile("manual-backup-test-", ".tachibk")
        try {
            val archiveBytes = byteArrayOf(31, -117, 8, 0, 1, 2)
            staged.writeBytes(archiveBytes)
            val document = ByteArrayOutputStream()

            ManualBackupDestinationPolicy.copyVerifiedArchive(
                verifiedArchive = staged,
                openExistingDocument = { ByteArrayInputStream(byteArrayOf()) },
                openDocumentOutput = { document },
            )

            assertArrayEquals(archiveBytes, document.toByteArray())
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `populated SAF document is refused before output is opened`() {
        val staged = File.createTempFile("manual-backup-test-", ".tachibk")
        try {
            staged.writeBytes(byteArrayOf(1, 2))
            var openedForOutput = false

            assertThrows(IOException::class.java) {
                ManualBackupDestinationPolicy.copyVerifiedArchive(
                    verifiedArchive = staged,
                    openExistingDocument = { ByteArrayInputStream(byteArrayOf(42)) },
                    openDocumentOutput = {
                        openedForOutput = true
                        ByteArrayOutputStream()
                    },
                )
            }

            org.junit.jupiter.api.Assertions.assertFalse(openedForOutput)
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `provider write failure propagates rather than marking a partial document successful`() {
        val staged = File.createTempFile("manual-backup-test-", ".tachibk")
        try {
            staged.writeBytes(byteArrayOf(1, 2, 3))
            var bytesWritten = 0
            val failingOutput = object : OutputStream() {
                override fun write(value: Int) {
                    if (++bytesWritten > 1) throw IOException("simulated provider failure")
                }
            }

            assertThrows(IOException::class.java) {
                ManualBackupDestinationPolicy.copyVerifiedArchive(
                    verifiedArchive = staged,
                    openExistingDocument = { ByteArrayInputStream(byteArrayOf()) },
                    openDocumentOutput = { failingOutput },
                )
            }
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `provider read error fails closed before writing`() {
        val unreadableDocument = object : InputStream() {
            override fun read(): Int = throw IOException("provider error")
        }
        assertThrows(IOException::class.java) {
            ManualBackupDestinationPolicy.requireEmpty(unreadableDocument)
        }
    }
}
