package eu.kanade.tachiyomi.data.backup.create

import com.hippo.unifile.UniFile

/**
 * Backup failures must not delete a caller-provided document URI. The only
 * disposable file is one freshly created for this automatic backup attempt.
 */
internal object BackupOutputCleanupPolicy {
    fun onFailure(file: UniFile?, createdByThisBackup: Boolean) {
        if (createdByThisBackup) file?.delete()
    }
}
