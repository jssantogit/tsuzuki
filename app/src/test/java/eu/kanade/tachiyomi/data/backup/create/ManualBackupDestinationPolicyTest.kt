package eu.kanade.tachiyomi.data.backup.create

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

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
    fun `provider read error fails closed before writing`() {
        val unreadableDocument = object : InputStream() {
            override fun read(): Int = throw IOException("provider error")
        }
        assertThrows(IOException::class.java) {
            ManualBackupDestinationPolicy.requireEmpty(unreadableDocument)
        }
    }
}
