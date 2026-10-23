package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.security.MessageDigest

/**
 * Cloud sync state (pending batches, event cursors and accepted bases) is still
 * installation-scoped in the legacy database. It must never be sent using a
 * different account's token.
 *
 * This marker lives in Android's no-backup directory, alongside the client ID.
 * Missing markers with pre-existing cloud state are intentionally NOT migrated
 * by guessing that the currently logged-in account owns that state.
 */
fun interface SyncAccountScopeGuard {
    suspend fun permits(accountId: String): Boolean
}

internal class PersistentSupabaseAccountScopeGuard(
    private val directory: File,
    private val hasLegacyCloudState: suspend () -> Boolean,
) : SyncAccountScopeGuard {

    override suspend fun permits(accountId: String): Boolean {
        val identity = accountId.trim().takeIf(String::isNotBlank) ?: return false
        val fingerprint = fingerprint(identity)
        mutex.lock()
        try {
            val marker = File(directory, FILE_NAME)
            if (marker.exists()) {
                // Invalid or inaccessible markers fail closed instead of rebinding.
                return marker.isFile && marker.readText().trim() == fingerprint
            }

            if (hasLegacyCloudState()) return false

            if (!directory.isDirectory && !directory.mkdirs()) return false
            val temporary = File(directory, "$FILE_NAME.tmp")
            try {
                temporary.writeText(fingerprint)
                // Same-directory rename is atomic on the Android filesystems
                // used for app-private storage. Never overwrite an owner marker.
                if (marker.exists()) return false
                if (!temporary.renameTo(marker)) return false
                return true
            } finally {
                temporary.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            mutex.unlock()
        }
    }

    private fun fingerprint(accountId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val FILE_NAME = "tsuzuki_supabase_sync_owner_sha256"

        // All graph/worker instances inside this process serialize first bind.
        private val mutex = Mutex()
    }
}
