package eu.kanade.tachiyomi.data.backup.create

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutomaticBackupRetentionPolicyTest {

    @Test
    fun `validated backup is retained and oldest previous copy is pruned`() {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("old-4", "app_2026-10-19_12-00.tachibk"),
            backup("old-3", "app_2026-10-20_12-00.tachibk"),
            backup("old-2", "app_2026-10-21_12-00.tachibk"),
            backup("old-1", "app_2026-10-22_12-00.tachibk"),
        )

        AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 4,
        ) shouldBe setOf("old-4")
    }

    @Test
    fun `clock rollback never retires just validated backup`() {
        val backups = listOf(
            backup("new", "app_2026-10-01_12-00.tachibk"),
            backup("old-1", "app_2026-10-22_12-00.tachibk"),
            backup("old-2", "app_2026-10-21_12-00.tachibk"),
            backup("old-3", "app_2026-10-20_12-00.tachibk"),
            backup("old-4", "app_2026-10-19_12-00.tachibk"),
        )

        AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 4,
        ) shouldBe setOf("old-4")
    }

    @Test
    fun `older simultaneous backup cannot retire newer validated sibling archives`() {
        val backups = listOf(
            backup("older-writer", "app_2026-10-23_12-00-00-001_0123456789abcdef0123456789abcdef.tachibk"),
            backup("newer-1", "app_2026-10-23_12-00-00-002_0123456789abcdef0123456789abcdef.tachibk"),
            backup("newer-2", "app_2026-10-23_12-00-00-003_0123456789abcdef0123456789abcdef.tachibk"),
            backup("newer-3", "app_2026-10-23_12-00-00-004_0123456789abcdef0123456789abcdef.tachibk"),
            backup("newer-4", "app_2026-10-23_12-00-00-005_0123456789abcdef0123456789abcdef.tachibk"),
        )

        AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(
            backups = backups,
            validatedUri = "older-writer",
            maximumCopies = 4,
        ) shouldBe emptySet()
    }

    @Test
    fun `unknown validated uri cannot authorize backup deletion`() {
        val backups = listOf(backup("only", "app_2026-10-21_12-00.tachibk"))
        kotlin.runCatching {
            AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(
                backups = backups,
                validatedUri = "not-found",
                maximumCopies = 4,
            )
        }.isFailure shouldBe true
    }

    private fun backup(uri: String, name: String) =
        AutomaticBackupRetentionPolicy.BackupFile(uri, name)
}
