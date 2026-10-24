package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey

class CanonicalChapterSyncKeyTest {

    @Test
    fun `same structured chapter identity is portable across local chapter UUIDs`() {
        val first = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "12a",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            alphaSuffix = "A",
        )
        val second = first.copy(id = "local-b", alphaSuffix = "a")

        CanonicalChapterSyncKey.from(first) shouldBe
            CanonicalChapterSyncKey.from(second)
    }

    @Test
    fun `unnumbered extras require stable mapped evidence rather than only their semantic type`() {
        val bonusA = CanonicalChapter(
            id = "bonus-a",
            canonicalTitleId = "title-1",
            displayNumber = "Extra - Bonus A",
            type = CanonicalChapterType.EXTRA,
        )
        val bonusB = bonusA.copy(id = "bonus-b", displayNumber = "Extra - Bonus B")

        CanonicalChapterSyncKey.from(bonusA) shouldBe null
        CanonicalChapterSyncKey.from(bonusB) shouldBe null
        val keyA = CanonicalChapterSyncKey.from(bonusA, "addon:source:bonus-a")
        val keyB = CanonicalChapterSyncKey.from(bonusB, "addon:source:bonus-b")
        (keyA == keyB) shouldBe false
    }

    @Test
    fun `opt-in exact decimal remains local until portable sync protocol is versioned`() {
        val precise = CanonicalChapter(
            id = "local-exact",
            canonicalTitleId = "title-1",
            displayNumber = "12.05",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )

        CanonicalChapterSyncKey.from(precise) shouldBe null
        CanonicalChapterSyncKey.from(precise, stableEvidenceKey = "source:chapter") shouldBe null
    }

    @Test
    fun `version two keys separate exact fractional scales without local IDs`() {
        val exact = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "12.05",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        val otherScale = exact.copy(
            id = "local-b",
            displayNumber = "12.5",
            decimalFraction = CanonicalChapterDecimalFraction(5, 1),
        )
        val equivalent = exact.copy(id = "local-c", displayNumber = "12.050")
        val firstKey = CanonicalChapterSyncKey.fromVersion2(exact)

        firstKey shouldBe CanonicalChapterSyncKey.fromVersion2(equivalent)
        (firstKey == CanonicalChapterSyncKey.fromVersion2(otherScale)) shouldBe false
        firstKey?.chapterKey?.startsWith("identity-v2:") shouldBe true
        CanonicalChapterSyncKey.from(exact) shouldBe null
    }

    @Test
    fun `version two keys encode volume ordinal and semantic distinctions`() {
        val exact = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "12.5",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 1),
            volume = 1,
        )
        val volumeTwo = exact.copy(id = "local-b", volume = 2)
        val unknownVolume = exact.copy(id = "local-c", volume = null)
        val ordinal = exact.copy(
            id = "local-d",
            numberingKind = CanonicalChapterNumberingKind.ORDINAL_PART,
            decimalFraction = null,
        )
        val legacy = exact.copy(id = "local-e", numberingKind = null, decimalFraction = null)

        val keys = listOf(exact, volumeTwo, unknownVolume, ordinal, legacy)
            .map { CanonicalChapterSyncKey.fromVersion2(it)?.recordId }
        keys.distinct().size shouldBe keys.size
        keys.all { it != null } shouldBe true
        (CanonicalChapterSyncKey.fromVersion2(legacy) == CanonicalChapterSyncKey.from(legacy)) shouldBe false
    }

    @Test
    fun `version two never exports unknown or unnumbered releases without stable evidence`() {
        val unknown = CanonicalChapter(
            id = "unknown-local",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )
        CanonicalChapterSyncKey.fromVersion2(unknown) shouldBe null
        CanonicalChapterSyncKey.fromVersion2(unknown, stableEvidenceKey = "source-key") shouldBe
            CanonicalChapterSyncKey.fromVersion2(unknown.copy(id = "other-device"), "source-key")
    }

    @Test
    fun `unknown unstructured chapter is not published without stable evidence`() {
        val chapter = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )

        CanonicalChapterSyncKey.from(chapter) shouldBe null
    }

    @Test
    fun `stable mapped evidence makes unknown chapter portable without local UUID`() {
        val first = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )
        val second = first.copy(id = "local-b")

        CanonicalChapterSyncKey.from(
            chapter = first,
            stableEvidenceKey = "integration:kitsu:chapter-44",
        ) shouldBe CanonicalChapterSyncKey.from(
            chapter = second,
            stableEvidenceKey = "integration:kitsu:chapter-44",
        )
    }
}
