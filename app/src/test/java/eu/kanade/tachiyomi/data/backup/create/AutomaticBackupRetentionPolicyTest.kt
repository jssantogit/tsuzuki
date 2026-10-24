package eu.kanade.tachiyomi.data.backup.create

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
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
        ) shouldBe emptySet() // Newer concurrent writers are not deletion candidates.
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

    @Test
    fun `partial older archive is excluded from deletion and valid-copy accounting`() {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("good-1", "app_2026-10-22_12-00.tachibk"),
            backup("good-2", "app_2026-10-21_12-00.tachibk"),
            backup("good-3", "app_2026-10-20_12-00.tachibk"),
            backup("partial", "app_2026-10-19_12-00.tachibk", restorable = false),
            backup("good-4", "app_2026-10-18_12-00.tachibk"),
        )

        AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 4,
        ) shouldBe setOf("good-4")
    }

    @Test
    fun `unknown or unverified replacement is never allowed to prune prior copies`() {
        val backups = listOf(
            backup("unverified", "app_2026-10-23_12-00.tachibk", restorable = false),
            backup("old", "app_2026-10-22_12-00.tachibk"),
        )
        shouldThrow<IllegalArgumentException> {
            AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(backups, "unverified", 1)
        }
    }

    @Test
    fun `verification inspects siblings and never reopens the just validated output`(): Unit = runBlocking {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk", restorable = false),
            backup("partial", "app_2026-10-22_12-00.tachibk", restorable = false),
            backup("old", "app_2026-10-21_12-00.tachibk", restorable = false),
        )
        val checked = mutableListOf<String>()

        val retired = AutomaticBackupRetentionPolicy.obsoleteAfterVerifying(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 1,
            maximumInspectionCount = 16,
            verify = { candidate ->
                checked += candidate.uri
                candidate.uri == "old"
            },
        )

        retired shouldBe setOf("old")
        checked shouldBe listOf("partial", "old")
    }

    @Test
    fun `unreadable gzip candidate remains untouched while verified older copy can retire`(): Unit = runBlocking {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("broken", "app_2026-10-22_12-00.tachibk", restorable = false),
            backup("old", "app_2026-10-21_12-00.tachibk", restorable = false),
        )
        val retired = AutomaticBackupRetentionPolicy.obsoleteAfterVerifying(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 1,
            maximumInspectionCount = 16,
            verify = { candidate ->
                if (candidate.uri == "broken") error("Truncated gzip trailer")
                true
            },
        )

        retired shouldBe setOf("old")
    }

    @Test
    fun `oversized directory inspection skips all deletion and decoding`(): Unit = runBlocking {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("older", "app_2026-10-22_12-00.tachibk"),
        )
        var checked = false

        AutomaticBackupRetentionPolicy.obsoleteAfterVerifying(
            backups = backups,
            validatedUri = "new",
            maximumCopies = 1,
            maximumInspectionCount = 1,
            verify = {
                checked = true
                true
            },
        ) shouldBe emptySet()
        checked shouldBe false
    }

    @Test
    fun `duplicate URI listing must never authorize deletion`() {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("new", "app_2026-10-22_12-00.tachibk"),
        )
        shouldThrow<IllegalArgumentException> {
            AutomaticBackupRetentionPolicy.obsoleteAfterSuccess(backups, "new", 1)
        }
    }

    @Test
    fun `retention verification propagates coroutine cancellation`(): Unit = runBlocking {
        val backups = listOf(
            backup("new", "app_2026-10-23_12-00.tachibk"),
            backup("older", "app_2026-10-22_12-00.tachibk"),
        )
        shouldThrow<kotlin.coroutines.cancellation.CancellationException> {
            AutomaticBackupRetentionPolicy.obsoleteAfterVerifying(
                backups = backups,
                validatedUri = "new",
                maximumCopies = 1,
                maximumInspectionCount = 16,
                verify = { throw kotlin.coroutines.cancellation.CancellationException("stop") },
            )
        }
    }

    private fun backup(uri: String, name: String, restorable: Boolean = true) =
        AutomaticBackupRetentionPolicy.BackupFile(uri, name, verifiedRestorable = restorable)
}
