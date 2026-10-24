package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class ParseCanonicalChapterExactNumberingTest {

    private val parser = ParseCanonicalChapterLabel()

    @Test
    fun `explicit decimal preserves scale and normalizes trailing fractional zeros`() {
        val fiveHundredths = parser.previewExactNumbering("Chapter 12.05")
        val fiveTenths = parser.previewExactNumbering("Ch. 12.5")
        val normalized = parser.previewExactNumbering("Capítulo 12.50 - Uma história")

        fiveHundredths shouldBe CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        fiveTenths shouldBe normalized
        (fiveHundredths == fiveTenths) shouldBe false
    }

    @Test
    fun `ordinal parts cannot be confused with decimal fractions`() {
        val ordinal = parser.previewExactNumbering("Chapter 12 Part 5")
        val decimal = parser.previewExactNumbering("Chapter 12.5")

        ordinal shouldBe CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.ORDINAL_PART,
        )
        (ordinal == decimal) shouldBe false
    }

    @Test
    fun `well formed volume prefix does not alter exact decimal identity`() {
        val withVolume = parser.previewExactNumbering("Vol. 3 Ch. 12.05")
        val withoutVolume = parser.previewExactNumbering("Ch. 12.05")

        withVolume shouldBe withoutVolume
    }

    @Test
    fun `ambiguous compact numeric labels are not exact decimal proof`() {
        parser.previewExactNumbering("9.46") shouldBe null
        parser.previewExactNumbering("12.05") shouldBe null
        parser.previewExactNumbering("Vol.none Ch. 12.05") shouldBe null
        parser.previewExactNumbering("Vol. 3 Ch. 12.05 - Vol. 4") shouldBe null
        parser.previewExactNumbering("Chapter 12.05 6") shouldBe null
        parser.previewExactNumbering("Chapter 12.000") shouldBe null
        parser.previewExactNumbering("Chapter 12.0000000001") shouldBe null
        parser.previewExactNumbering("Chapter 12.2147483648") shouldBe null
        parser.previewExactNumbering("Chapter 12.05 Part 2") shouldBe null
        parser.previewExactNumbering("Chapter 12") shouldBe null
        parser.previewExactNumbering("Chapter 12a") shouldBe null
        parser.previewExactNumbering("Bonus 12.05") shouldBe null
    }

    @Test
    fun `preview never changes the legacy parser interpretation`() {
        parser.execute("Chapter 12.05").type shouldBe CanonicalChapterType.UNKNOWN
        parser.previewExactNumbering("Chapter 12.05")?.numberingKind shouldBe
            CanonicalChapterNumberingKind.DECIMAL
        parser.execute("Chapter 12.05").type shouldBe CanonicalChapterType.UNKNOWN
    }
}
