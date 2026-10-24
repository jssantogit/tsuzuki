package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.sync.interactor.PreviewCanonicalProgressV2Migration
import tachiyomi.domain.tsuzuki.sync.interactor.ProgressMigrationPreview
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision

class PreviewCanonicalProgressV2MigrationTest {
    private val preview = PreviewCanonicalProgressV2Migration()

    @Test
    fun `legacy progress gets deterministic v2 mapping without changing the document`() {
        val chapter = chapter("local-device-a")
        val legacy = document(chapter)
        val result = preview.assess(legacy, listOf(chapter))
        val target = CanonicalChapterSyncKey.fromVersion2(chapter)!!.recordId

        result shouldBe ProgressMigrationPreview.Ready(
            mapOf(legacy.records.keys.single() to target),
        )
        legacy.kind shouldBe SyncDocumentKind.READING_PROGRESS
        legacy.schemaVersion shouldBe 1
    }

    @Test
    fun `older v1 progress without a variant field can still be safely previewed`() {
        val chapter = chapter("legacy-without-variant")
        val legacy = document(chapter)
        val original = legacy.records.values.single()
        val withoutVariant = original.copy(
            fields = buildJsonObject {
                put("canonicalTitleId", chapter.canonicalTitleId)
                put("chapterKey", CanonicalChapterSyncKey.from(chapter)!!.chapterKey)
                put("read", false)
                put("lastPageRead", 2)
            },
        )

        preview.assess(
            legacy.copy(records = mapOf(original.id to withoutVariant)),
            listOf(chapter),
        ) shouldBe ProgressMigrationPreview.Ready(
            mapOf(original.id to CanonicalChapterSyncKey.fromVersion2(chapter)!!.recordId),
        )
    }

    @Test
    fun `chapter numbering reset between volumes blocks ambiguous legacy remap`() {
        val first = chapter("volume-one").copy(volume = 1)
        val second = chapter("volume-two").copy(volume = 2)
        val legacy = document(first)

        preview.assess(legacy, listOf(first, second)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.AMBIGUOUS_LEGACY_IDENTITY)
    }

    @Test
    fun `exact decimal metadata cannot be guessed from a legacy checkpoint`() {
        val legacyChapter = chapter("legacy").copy(part = 5)
        val exact = legacyChapter.copy(
            id = "exact-005",
            displayNumber = "12.05",
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )

        preview.assess(document(legacyChapter), listOf(exact)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNRESOLVED_LEGACY_KEY)
    }

    @Test
    fun `remote progress key must agree with its field payload`() {
        val chapter = chapter("local")
        val legacy = document(chapter)
        val old = legacy.records.values.single()
        val tampered = old.copy(
            fields = buildJsonObject {
                put("canonicalTitleId", chapter.canonicalTitleId)
                put("chapterKey", "identity:forged")
                put("read", false)
                put("lastPageRead", 2)
                put("lastVariantEvidenceKey", JsonNull)
            },
        )

        preview.assess(legacy.copy(records = mapOf(old.id to tampered)), listOf(chapter)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.MALFORMED_REMOTE_RECORD)
    }

    @Test
    fun `variant-specific checkpoints require a separately reviewed source mapping`() {
        val chapter = chapter("source")
        val legacy = document(chapter)
        val record = legacy.records.values.single()
        val withVariant = record.copy(
            fields = buildJsonObject {
                put("canonicalTitleId", chapter.canonicalTitleId)
                put("chapterKey", CanonicalChapterSyncKey.from(chapter)!!.chapterKey)
                put("read", false)
                put("lastPageRead", 2)
                put("lastVariantEvidenceKey", "source:legacy-variant")
            },
        )

        preview.assess(
            legacy.copy(records = mapOf(withVariant.id to withVariant)),
            listOf(chapter),
        ) shouldBe ProgressMigrationPreview.Blocked(
            ProgressMigrationPreview.Reason.VARIANT_EVIDENCE_REQUIRES_REVIEW,
        )
    }

    @Test
    fun `unknown unnumbered chapter needs stable evidence to be reviewable`() {
        val chapter = chapter("unknown").copy(
            type = CanonicalChapterType.UNKNOWN,
            baseNumber = null,
            displayNumber = "?",
        )
        val stable = "provider:chapter-123"
        val legacy = document(chapter, stable)

        preview.assess(legacy, listOf(chapter)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNRESOLVED_LEGACY_KEY)

        preview.assess(
            legacy,
            listOf(chapter),
            stableEvidenceByChapterId = mapOf(chapter.id to stable),
        ) shouldBe ProgressMigrationPreview.Ready(
            mapOf(legacy.records.keys.single() to CanonicalChapterSyncKey.fromVersion2(chapter, stable)!!.recordId),
        )
    }

    @Test
    fun `unresolved remote record blocks entire preview instead of silently dropping progress`() {
        val chapter = chapter("source")
        val destination = chapter.copy(baseNumber = 17, id = "wrong-chapter")
        preview.assess(document(chapter), listOf(destination)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNRESOLVED_LEGACY_KEY)
    }

    @Test
    fun `remote tombstones cannot become reading progress without reviewed deletion policy`() {
        val chapter = chapter("source")
        val legacy = document(chapter)
        val record = legacy.records.values.single()
        preview.assess(
            legacy.copy(records = mapOf(record.id to record.copy(deletedAtEpochMillis = 5))),
            listOf(chapter),
        ) shouldBe ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.MALFORMED_REMOTE_RECORD)
    }

    @Test
    fun `wrong namespace or schema is never reviewed as legacy progress`() {
        val chapter = chapter("source")
        val legacy = document(chapter)
        preview.assess(legacy.copy(schemaVersion = 2), listOf(chapter)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNSUPPORTED_DOCUMENT)
        preview.assess(legacy.copy(kind = SyncDocumentKind.READING_PROGRESS_V2), listOf(chapter)) shouldBe
            ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNSUPPORTED_DOCUMENT)
    }

    @Test
    fun `migration preview refuses quoted numeric and boolean scalars`() {
        val chapter = chapter("strict-preview")
        val original = document(chapter)
        val record = original.records.values.single()

        for ((field, forgedValue) in listOf(
            "read" to JsonPrimitive("false"),
            "lastPageRead" to JsonPrimitive("2"),
        )) {
            val forged = record.copy(
                fields = JsonObject(record.fields + (field to forgedValue)),
            )
            preview.assess(
                original.copy(records = mapOf(record.id to forged)),
                listOf(chapter),
            ) shouldBe ProgressMigrationPreview.Blocked(
                ProgressMigrationPreview.Reason.MALFORMED_REMOTE_RECORD,
            )
        }
    }

    @Test
    fun `blank variant evidence cannot become a reviewed mapping`() {
        val chapter = chapter("blank-variant")
        val original = document(chapter)
        val record = original.records.values.single()
        val forged = record.copy(
            fields = JsonObject(record.fields + ("lastVariantEvidenceKey" to JsonPrimitive(" "))),
        )

        preview.assess(
            original.copy(records = mapOf(record.id to forged)),
            listOf(chapter),
        ) shouldBe ProgressMigrationPreview.Blocked(
            ProgressMigrationPreview.Reason.MALFORMED_REMOTE_RECORD,
        )
    }

    private fun chapter(id: String) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = "12",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 12,
    )

    private fun document(chapter: CanonicalChapter, stableEvidence: String? = null): SyncDocumentEnvelope {
        val key = CanonicalChapterSyncKey.from(chapter, stableEvidence)!!
        val record = SyncRecordEnvelope(
            id = key.recordId,
            revision = SyncRevision("device-one", 1),
            updatedAtEpochMillis = 10,
            fields = buildJsonObject {
                put("canonicalTitleId", key.canonicalTitleId)
                put("chapterKey", key.chapterKey)
                put("read", false)
                put("lastPageRead", 2)
                put("lastVariantEvidenceKey", JsonNull)
            },
        )
        return SyncDocumentEnvelope(
            schemaVersion = 1,
            kind = SyncDocumentKind.READING_PROGRESS,
            revision = SyncRevision("device-one", 2),
            generatedAtEpochMillis = 10,
            records = mapOf(record.id to record),
        )
    }
}
