package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class BackupCanonicalCoreTest {

    @Test
    fun `unverified identity provisional chapter and offline-only checkpoint roundtrip`() {
        val original = core()
        original.validate()

        val json = Json.encodeToString(BackupCanonicalCore.serializer(), original)
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), json)

        decoded.validate()
        decoded shouldBe original
        decoded.chapters.single().confirmation shouldBe "PROVISIONAL"
        decoded.identities.single().verified shouldBe false
    }

    @Test
    fun `orphaned progress or unrelated source variant are rejected before restore`() {
        runCatching {
            core().copy(progress = listOf(BackupCoreProgress("missing", true, 1, null, 10))).validate()
        }.isFailure shouldBe true
        runCatching {
            core().copy(progress = listOf(BackupCoreProgress("chapter-1", true, 1, "unknown", 10))).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `duplicate canonical titles or read checkpoints fail closed`() {
        val original = core()
        runCatching {
            original.copy(titles = original.titles + original.titles).validate()
        }.isFailure shouldBe true
        runCatching {
            original.copy(progress = original.progress + original.progress).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `variant cannot be restored using a source mapping from a different title`() {
        val original = core()
        val wrongTitle = BackupCoreTitle("title-2", "Other", "SOURCE_ONLY", 1, 2)
        val wrongMapping = original.sourceMappings.single().copy(
            id = "mapping-2",
            canonicalTitleId = "title-2",
            sourceId = 99,
        )
        runCatching {
            original.copy(
                titles = original.titles + wrongTitle,
                sourceMappings = original.sourceMappings + wrongMapping,
                variants = original.variants.map { it.copy(sourceMappingId = "mapping-2") },
            ).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `exact decimal chapter survives portable JSON roundtrip`() {
        val source = core()
        val exact = source.copy(
            chapters = source.chapters.map {
                it.copy(
                    displayNumber = "12.05",
                    baseNumber = 12,
                    part = 5,
                    numberingKind = "DECIMAL",
                    decimalNumerator = 5,
                    decimalScale = 2,
                )
            },
        )
        exact.validate()
        val encoded = Json.encodeToString(BackupCanonicalCore.serializer(), exact)
        Json.decodeFromString(BackupCanonicalCore.serializer(), encoded) shouldBe exact
        source.chapters.single().numberingKind shouldBe null
    }

    @Test
    fun `malformed exact chapter fraction cannot enter a backup`() {
        val source = core()
        listOf(
            source.chapters.single().copy(numberingKind = "DECIMAL", decimalNumerator = 5),
            source.chapters.single().copy(numberingKind = "DECIMAL", decimalNumerator = 5, decimalScale = 2),
            source.chapters.single().copy(numberingKind = "UNKNOWN"),
            source.chapters.single().copy(decimalNumerator = 5, decimalScale = 2),
        ).forEach { malformed ->
            runCatching { source.copy(chapters = listOf(malformed)).validate() }.isFailure shouldBe true
        }
    }

    @Test
    fun `unknown schema version is not silently imported`() {
        runCatching { core().copy(schemaVersion = 200).validate() }.isFailure shouldBe true
    }

    private fun core() = BackupCanonicalCore(
        titles = listOf(BackupCoreTitle("title-1", "Book", "SOURCE_ONLY", 1, 2)),
        identities = listOf(BackupCoreIdentity("title-1", "source", "remote-1", false, 1)),
        sourceMappings = listOf(
            BackupCoreSourceMapping(
                id = "mapping-1",
                canonicalTitleId = "title-1",
                mihonMangaId = null,
                sourceId = 42,
                sourceUrl = "https://example.invalid/book",
                language = "pt-BR",
                matchConfidence = 0.9,
                verifiedByUser = false,
                availability = "AVAILABLE",
                preferredOverride = false,
                createdAt = 1,
                updatedAt = 2,
            ),
        ),
        library = listOf(BackupCoreLibraryEntry("title-1", "READING", true, 2, 3)),
        chapters = listOf(
            BackupCoreChapter(
                id = "chapter-1",
                canonicalTitleId = "title-1",
                displayNumber = "1",
                volume = null,
                title = "Opening",
                type = "REGULAR",
                baseNumber = 1,
                part = null,
                alphaSuffix = null,
                confidence = 0.6,
                createdAt = 2,
                updatedAt = 3,
                confirmation = "PROVISIONAL",
            ),
        ),
        variants = listOf(
            BackupCoreVariant(
                id = "variant-1",
                canonicalChapterId = "chapter-1",
                sourceMappingId = "mapping-1",
                sourceId = 42,
                mihonMangaId = null,
                mihonChapterId = null,
                sourceChapterId = "chapter-url",
                sourceChapterUrl = "https://example.invalid/ch1",
                language = "pt-BR",
                scanlationGroup = null,
                version = null,
                releaseDate = null,
                rawName = "Chapter 1",
                rawNumberHint = 1.0,
                rawSourceOrder = null,
                rawSourceMetadataJson = """{"source":{"custom":"metadata"}}""",
                createdAt = 2,
                updatedAt = 3,
            ),
        ),
        progress = listOf(BackupCoreProgress("chapter-1", false, 13, "variant-1", 4)),
        history = listOf(BackupCoreHistory("chapter-1", "variant-1", 5, 120)),
    )
}
