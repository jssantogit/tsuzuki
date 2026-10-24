package eu.kanade.tachiyomi.data.backup.create

import com.hippo.unifile.UniFile

/**
 * Backup failures must not delete a caller-provided document URI. The only
 * disposable file is one freshly created for this automatic backup attempt.
 */
internal object BackupOutputCleanupPolicy {
    fun onFailure(
        file: UniFile?,
        createdByThisBackup: Boolean,
        successfullyValidated: Boolean = false,
    ) {
        // A validated backup is durable even if timestamp or retention work
        // fails afterwards. Deleting it could leave fewer recovery copies.
        if (createdByThisBackup && !successfullyValidated) file?.delete()
    }
}
