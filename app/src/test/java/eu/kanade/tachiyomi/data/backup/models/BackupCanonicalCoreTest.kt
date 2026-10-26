package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

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
    fun `edition ownership survives a portable core roundtrip and rejects invalid ownership`() {
        val original = core().copy(
            chapters = core().chapters.map { it.copy(confirmation = "CONFIRMED") },
        )
        val link = BackupCoreEditorialChapterBinding(
            canonicalChapterId = "chapter-1",
            canonicalTitleId = "title-1",
            producerId = "editorial-test",
            externalWorkId = "work-1",
            editionKey = "edition-ja",
            scopeLanguage = "ja",
            sourceRecordId = "chapter-1",
            upstreamSourceId = "publisher-feed",
            identityKey = CanonicalChapterIdentity(CanonicalChapterType.REGULAR, 1).sortKey,
            volumeKey = 0L,
            observedAt = 42L,
        )
        val valid = original.copy(editorialChapterBindings = listOf(link))
        valid.validate()
        val json = Json.encodeToString(BackupCanonicalCore.serializer(), valid)
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), json)
        decoded shouldBe valid
        runCatching {
            valid.copy(editorialChapterBindings = listOf(link, link)).validate()
        }.isFailure shouldBe true
        runCatching {
            valid.copy(editorialChapterBindings = listOf(link.copy(canonicalTitleId = "wrong"))).validate()
        }.isFailure shouldBe true
        runCatching {
            valid.copy(editorialChapterBindings = listOf(link.copy(canonicalChapterId = "missing"))).validate()
        }.isFailure shouldBe true
        runCatching {
            valid.copy(editorialChapterBindings = listOf(link.copy(editionKey = " "))).validate()
        }.isFailure shouldBe true
        runCatching {
            valid.copy(editorialChapterBindings = listOf(link.copy(volumeKey = -1))).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `user adoption backup preserves exact personal provenance without editor claims`() {
        val receipt = BackupCoreUserAdoptionReceipt(
            canonicalChapterId = "chapter-1",
            receiptId = "receipt-1",
            canonicalTitleId = "title-1",
            producerId = "epub3-user-selected",
            externalWorkId = "book",
            editionKey = "edition",
            scopeLanguage = "ja",
            sourceRecordId = "unit-1",
            sourceKind = "USER_SELECTED_EPUB3_TOC",
            derivation = "USER_SUPPLIED_EDITION_TOC",
            originReference = "user-selected:edition",
            exactRevision = "r1",
            attributionReference = null,
            licenseReference = null,
            snapshotDigest = "sha256:" + "a".repeat(64),
            sourceUnitId = "chapter.xhtml",
            structuralCategory = "toc-chapter",
            acceptedLabel = "1",
            identityKey = CanonicalChapterIdentity(CanonicalChapterType.REGULAR, 1).sortKey,
            volumeKey = 0L,
            observedAt = 10L,
            acceptedAt = 20L,
            reviewFingerprint = "sha256:" + "b".repeat(64),
        )
        val original = core().copy(
            chapters = core().chapters.map { it.copy(confirmation = "CONFIRMED") },
            userAdoptionReceipts = listOf(receipt),
        )
        original.validate()
        val decoded = Json.decodeFromString(
            BackupCanonicalCore.serializer(),
            Json.encodeToString(BackupCanonicalCore.serializer(), original),
        )
        decoded shouldBe original
        runCatching {
            original.copy(userAdoptionReceipts = listOf(receipt, receipt)).validate()
        }.isFailure shouldBe true
        runCatching {
            original.copy(userAdoptionReceipts = listOf(receipt.copy(canonicalTitleId = "wrong"))).validate()
        }.isFailure shouldBe true
        runCatching {
            original.copy(userAdoptionReceipts = listOf(receipt.copy(snapshotDigest = "not-a-digest"))).validate()
        }.isFailure shouldBe true
        val otherChapter = original.chapters.single().copy(
            id = "chapter-2",
            displayNumber = "2",
            baseNumber = 2,
        )
        val mixedOriginReceipt = receipt.copy(
            canonicalChapterId = "chapter-2",
            sourceRecordId = "unit-2",
            sourceUnitId = "chapter-2.xhtml",
            acceptedLabel = "2",
            identityKey = CanonicalChapterIdentity(CanonicalChapterType.REGULAR, 2).sortKey,
            originReference = "user-selected:other-edition",
        )
        runCatching {
            original.copy(
                chapters = original.chapters + otherChapter,
                userAdoptionReceipts = listOf(receipt, mixedOriginReceipt),
            ).validate()
        }.isFailure shouldBe true

        val duplicateIdentity = receipt.copy(
            canonicalChapterId = "chapter-2",
            receiptId = "receipt-2",
            sourceRecordId = "unit-2",
            sourceUnitId = "chapter-2.xhtml",
        )
        runCatching {
            original.copy(
                chapters = original.chapters + original.chapters.single().copy(id = "chapter-2"),
                userAdoptionReceipts = listOf(receipt, duplicateIdentity),
            ).validate()
        }.isFailure shouldBe true
    }

    @Test
    fun `legacy count ID retains progress history and variant across portable backup roundtrip`() {
        val core = core()
        // A canonical-title merge changes the chapter owner, never its historical ID.
        val oldChapterId = "tsuzuki:count:previous-local-title:37"
        val legacy = core.copy(
            chapters = core.chapters.map {
                it.copy(
                    id = oldChapterId,
                    displayNumber = "37",
                    baseNumber = 37,
                    confirmation = "PROVISIONAL",
                )
            },
            variants = core.variants.map { it.copy(canonicalChapterId = oldChapterId) },
            progress = core.progress.map { it.copy(canonicalChapterId = oldChapterId) },
            history = core.history.map { it.copy(canonicalChapterId = oldChapterId) },
        )

        legacy.validate()
        val encoded = Json.encodeToString(BackupCanonicalCore.serializer(), legacy)
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), encoded)
        decoded.validate()

        decoded shouldBe legacy
        decoded.chapters.single().canonicalTitleId shouldBe "title-1"
        decoded.chapters.single().id shouldBe oldChapterId
        decoded.progress.single().canonicalChapterId shouldBe oldChapterId
        decoded.history.single().canonicalChapterId shouldBe oldChapterId
        decoded.variants.single().canonicalChapterId shouldBe oldChapterId
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
