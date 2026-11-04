package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSnapshot

/**
 * Version-one standalone Tsuzuki backup. Never mix the non-transactional
 * Mihon legacy restoration with the atomic canonical importer.
 *
 * Explicitly isolated until a cross-graph conflict/migration plan exists.
 */
object CanonicalStandaloneArchive {
    fun compose(section: BackupCanonicalSection): Backup {
        require(section.key == BackupCanonicalCore.SECTION_KEY) { "Unsupported canonical section" }
        val snapshot = BackupCanonicalSnapshot(sections = listOf(section))
        snapshot.validate()
        return Backup(backupManga = emptyList(), canonicalSnapshot = snapshot)
    }

    fun requireRestorable(backup: Backup, librarySelected: Boolean): BackupCanonicalSection {
        require(librarySelected) { "Canonical library restore must be explicitly selected" }
        val snapshot = requireNotNull(backup.canonicalSnapshot) { "No canonical archive section" }
        require(
            backup.backupManga.isEmpty() &&
                backup.backupCategories.isEmpty() &&
                backup.backupSources.isEmpty() &&
                backup.backupPreferences.isEmpty() &&
                backup.backupExtensionStores.isEmpty() &&
                backup.backupSourcePreferences.isEmpty(),
        ) { "Mixed Mihon and canonical archives cannot be restored transactionally" }
        require(snapshot.sections.size == 1) { "Incomplete or unknown canonical archive sections" }
        val section = snapshot.sections.single()
        require(section.key == BackupCanonicalCore.SECTION_KEY) { "Unsupported canonical archive section" }
        snapshot.validate()
        return section
    }
}
