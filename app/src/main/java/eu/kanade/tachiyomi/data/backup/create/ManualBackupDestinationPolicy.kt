package eu.kanade.tachiyomi.data.backup.create

import java.io.IOException
import java.io.InputStream

/**
 * ACTION_CREATE_DOCUMENT normally produces a new, empty document. Some SAF
 * implementations may instead expose an existing one. Never truncate bytes
 * already stored at a caller-owned manual destination.
 *
 * This is a pre-write guard, not an atomic SAF compare-and-swap. Providers
 * can race after the check, so physical provider testing is still required.
 */
internal object ManualBackupDestinationPolicy {

    fun requireEmpty(input: InputStream?) {
        val readable = input ?: throw IOException("Cannot verify the selected backup document is empty")
        readable.use {
            if (it.read() != -1) {
                throw IOException("Selected backup document already contains data; choose a new document")
            }
        }
    }
}
