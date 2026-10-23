package eu.kanade.tachiyomi.data.backup.create

/**
 * Selects only older backups for retirement after the replacement was written
 * and independently validated. URI, not filename, identifies the new file.
 */
internal object AutomaticBackupRetentionPolicy {
    data class BackupFile(
        val uri: String,
        val filename: String,
    )

    fun obsoleteAfterSuccess(
        backups: List<BackupFile>,
        validatedUri: String,
        maximumCopies: Int,
    ): Set<String> {
        require(maximumCopies > 0) { "At least one backup must be retained" }
        require(backups.any { it.uri == validatedUri }) {
            "Validated backup is not present in the retention directory"
        }
        return backups
            .filterNot { it.uri == validatedUri }
            .sortedByDescending { it.filename }
            .drop(maximumCopies - 1)
            .mapTo(mutableSetOf()) { it.uri }
    }
}
