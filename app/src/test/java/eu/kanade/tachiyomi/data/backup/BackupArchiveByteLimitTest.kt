package eu.kanade.tachiyomi.data.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okio.Buffer
import okio.GzipSink
import okio.buffer
import okio.gzip
import org.junit.jupiter.api.Test
import java.io.IOException

class BackupArchiveByteLimitTest {

    @Test
    fun `normal and exact-limit legacy backup bytes remain readable`() {
        BackupArchiveByteLimit.read(Buffer().writeUtf8("backup"), 6).decodeToString() shouldBe "backup"
        BackupArchiveByteLimit.read(Buffer().writeUtf8(""), 0).size shouldBe 0
    }

    @Test
    fun `raw backup one byte above limit is rejected`() {
        val bytes = Buffer().writeUtf8("1234567")
        shouldThrow<IOException> {
            BackupArchiveByteLimit.read(bytes, 6)
        }
    }

    @Test
    fun `gzip bomb is bounded by decompressed bytes rather than compressed size`() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { sink ->
            sink.write(ByteArray(4096))
        }
        shouldThrow<IOException> {
            BackupArchiveByteLimit.read(compressed.gzip().buffer(), 1024)
        }
    }

    @Test
    fun `compressed backup exactly at decoded limit remains readable`() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { sink ->
            sink.writeUtf8("123456")
        }
        BackupArchiveByteLimit.read(compressed.gzip().buffer(), 6).decodeToString() shouldBe "123456"
    }
}
