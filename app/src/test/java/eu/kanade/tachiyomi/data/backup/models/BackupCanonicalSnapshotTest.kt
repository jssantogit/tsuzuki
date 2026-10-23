package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.shouldBe
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class BackupCanonicalSnapshotTest {

    private val codec = ProtoBuf

    @Test
    fun `legacy backup decodes with no canonical snapshot`() {
        val legacy = codec.encodeToByteArray(LegacyBackup.serializer(), LegacyBackup())
        val decoded = codec.decodeFromByteArray(Backup.serializer(), legacy)

        decoded.backupManga shouldBe emptyList()
        decoded.canonicalSnapshot shouldBe null
    }

    @Test
    fun `canonical snapshot survives protobuf roundtrip with intact section hash`() {
        val section = BackupCanonicalSection.fromJson(
            key = "tsuzuki.titles.v1",
            json = """{"records":[]}""",
        )
        val snapshot = BackupCanonicalSnapshot(sections = listOf(section))
        val backup = Backup(backupManga = emptyList(), canonicalSnapshot = snapshot)

        val decoded = codec.decodeFromByteArray(
            Backup.serializer(),
            codec.encodeToByteArray(Backup.serializer(), backup),
        )

        decoded.canonicalSnapshot?.validate()
        decoded.canonicalSnapshot shouldBe snapshot
    }

    @Test
    fun `tampered canonical section fails validation instead of silently restoring`() {
        val section = BackupCanonicalSection.fromJson("tsuzuki.titles.v1", """{"records":[]}""")
        val damaged = section.copy(payloadJson = """{"records":["untrusted"]}""")

        runCatching {
            BackupCanonicalSnapshot(sections = listOf(damaged)).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `checksum alone does not make malformed JSON a valid backup section`() {
        runCatching {
            BackupCanonicalSection.fromJson("tsuzuki.core.v1", "{malformed json")
        }.isFailure shouldBe true
    }

    @Test
    fun `checksum-valid core section with orphan library reference is rejected`() {
        val invalidCore = BackupCanonicalCore(
            library = listOf(BackupCoreLibraryEntry("missing", "READING", true, 1, 2)),
        )
        val section = BackupCanonicalSection.fromJson(
            key = BackupCanonicalCore.SECTION_KEY,
            json = Json.encodeToString(BackupCanonicalCore.serializer(), invalidCore),
        )
        runCatching {
            BackupCanonicalSnapshot(sections = listOf(section)).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `unsupported snapshot version and duplicate sections are rejected`() {
        val section = BackupCanonicalSection.fromJson("tsuzuki.library.v1", "[]")

        runCatching {
            BackupCanonicalSnapshot(formatVersion = 99, sections = listOf(section)).validate()
        }.isFailure shouldBe true
        runCatching {
            BackupCanonicalSnapshot(sections = listOf(section, section)).validate()
        }.isFailure shouldBe true
    }

    @kotlinx.serialization.Serializable
    private data class LegacyBackup(
        @kotlinx.serialization.protobuf.ProtoNumber(1)
        val backupManga: List<BackupManga> = emptyList(),
    )
}
