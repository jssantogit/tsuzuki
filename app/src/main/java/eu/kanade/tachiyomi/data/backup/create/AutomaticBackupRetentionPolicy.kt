package eu.kanade.tachiyomi.data.backup.create

import kotlin.coroutines.cancellation.CancellationException

/**
 * A matching filename is not proof that an archive is a completed backup.
 * Only backups whose full contents can be decoded are deletion candidates.
 */
internal object AutomaticBackupRetentionPolicy {
    data class BackupFile(
        val uri: String,
        val filename: String,
        val verifiedRestorable: Boolean,
    )

    /**
     * Cap archive inspections because each may require substantial memory.
     * Preserve everything if the limit is exceeded or verification is uncertain.
     */
    suspend fun obsoleteAfterVerifying(
        backups: List<BackupFile>,
        validatedUri: String,
        maximumCopies: Int,
        maximumInspectionCount: Int,
        verify: suspend (BackupFile) -> Boolean,
    ): Set<String> {
        require(maximumInspectionCount > 0) { "Invalid retention inspection budget" }
        if (backups.size > maximumInspectionCount) return emptySet()

        val inspected = backups.map { candidate ->
            if (candidate.uri == validatedUri) {
                candidate.copy(verifiedRestorable = true)
            } else {
                val restorable = try {
                    verify(candidate)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false // Incomplete and unreadable files must never be deleted.
                }
                candidate.copy(verifiedRestorable = restorable)
            }
        }
        return obsoleteAfterSuccess(inspected, validatedUri, maximumCopies)
    }

    fun obsoleteAfterSuccess(
        backups: List<BackupFile>,
        validatedUri: String,
        maximumCopies: Int,
    ): Set<String> {
        require(maximumCopies > 0) { "At least one backup must be retained" }
        require(backups.distinctBy { it.uri }.size == backups.size) {
            "Duplicate backup URI in retention listing"
        }
        val newlyValidated = requireNotNull(
            backups.singleOrNull { it.uri == validatedUri && it.verifiedRestorable },
        ) { "The replacement must be present and verified before retention" }

        return backups
            .asSequence()
            .filter { it.verifiedRestorable && it.uri != validatedUri }
            .sortedByDescending { it.filename }
            .drop(maximumCopies - 1)
            // Never let an older concurrent attempt retire a newer backup.
            // This is conservative under clock changes and overlapping jobs.
            .filter { it.filename < newlyValidated.filename }
            .map { it.uri }
            .toSet()
    }
}
