package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSnapshot
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class CanonicalStandaloneArchiveTest {
    private val core = BackupCanonicalSection.fromJson(
        BackupCanonicalCore.SECTION_KEY,
        Json.encodeToString(BackupCanonicalCore.serializer(), BackupCanonicalCore()),
    )

    @Test
    fun `canonical only mode is opt in and compatible with legacy stored options`() {
        BackupOptions().canonicalLibraryOnly shouldBe false
        BackupOptions.fromBooleanArray(BackupOptions().asBooleanArray()).canonicalLibraryOnly shouldBe false
        BackupOptions.fromBooleanArray(BooleanArray(10) { true }).canonicalLibraryOnly shouldBe false
        BackupOptions().copy(canonicalLibraryOnly = true).canCreate() shouldBe true
    }

    @Test
    fun `standalone writer creates a fully checked core section without legacy rows`() {
        val backup = CanonicalStandaloneArchive.compose(core)
        backup.backupManga shouldBe emptyList()
        backup.canonicalSnapshot?.sections?.size shouldBe 1
        CanonicalStandaloneArchive.requireRestorable(backup, true).sha256 shouldBe core.sha256
    }

    @Test
    fun `mixed legacy and canonical contents cannot silently restore`() {
        val archive = CanonicalStandaloneArchive.compose(core)
        shouldThrow<IllegalArgumentException> {
            CanonicalStandaloneArchive.requireRestorable(
                archive.copy(backupPreferences = listOf(BackupPreference("foo", StringPreferenceValue("bar")))),
                true,
            )
        }
    }

    @Test
    fun `empty unknown and disabled canonical restores fail closed`() {
        val archive = CanonicalStandaloneArchive.compose(core)
        shouldThrow<IllegalArgumentException> {
            CanonicalStandaloneArchive.requireRestorable(
                archive.copy(canonicalSnapshot = BackupCanonicalSnapshot()),
                true,
            )
        }
        shouldThrow<IllegalArgumentException> {
            CanonicalStandaloneArchive.requireRestorable(
                archive.copy(
                    canonicalSnapshot = BackupCanonicalSnapshot(
                        sections = listOf(BackupCanonicalSection.fromJson("tsuzuki.other.v1", "{}")),
                    ),
                ),
                true,
            )
        }
        shouldThrow<IllegalArgumentException> {
            CanonicalStandaloneArchive.requireRestorable(archive, false)
        }
    }
}
