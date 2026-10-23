package eu.kanade.tachiyomi.data.backup

import okio.Buffer
import okio.BufferedSource
import java.io.IOException

/**
 * Limits decompressed backup bytes before protobuf parsing. In particular,
 * a small gzip stream must not expand without bound inside the app process.
 */
internal object BackupArchiveByteLimit {
    const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024

    fun read(source: BufferedSource, limit: Long = MAX_UNCOMPRESSED_BYTES): ByteArray {
        require(limit in 0..MAX_UNCOMPRESSED_BYTES) { "Invalid backup size limit" }
        val destination = Buffer()
        var totalBytes = 0L

        while (true) {
            // Read one byte beyond the boundary to distinguish an exact-fit
            // archive from oversized data, without buffering the whole excess.
            val requestBytes = minOf(8192L, limit - totalBytes + 1)
            val received = source.read(destination, requestBytes)
            if (received == -1L) break
            totalBytes += received
            if (totalBytes > limit) {
                throw IOException("Backup exceeds the safe uncompressed size limit")
            }
        }
        return destination.readByteArray()
    }
}
