package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction

class CanonicalChapterDecimalFractionTest {

    @Test
    fun `significant leading zeroes remain part of fractional value`() {
        val fiveTenths = CanonicalChapterDecimalFraction.parse("5")
        val fiveHundredths = CanonicalChapterDecimalFraction.parse("05")
        val fiveThousandths = CanonicalChapterDecimalFraction.parse("005")

        fiveTenths shouldBe CanonicalChapterDecimalFraction(5, 1)
        fiveHundredths shouldBe CanonicalChapterDecimalFraction(5, 2)
        fiveThousandths shouldBe CanonicalChapterDecimalFraction(5, 3)
        (fiveTenths == fiveHundredths) shouldBe false
        (fiveHundredths == fiveThousandths) shouldBe false
        (fiveThousandths!! < fiveHundredths!!) shouldBe true
        (fiveHundredths!! < fiveTenths!!) shouldBe true
    }

    @Test
    fun `trailing zeroes normalize to one exact fraction without floating point`() {
        CanonicalChapterDecimalFraction.parse("50") shouldBe CanonicalChapterDecimalFraction.parse("5")
        CanonicalChapterDecimalFraction.parse("0500") shouldBe CanonicalChapterDecimalFraction.parse("05")
        CanonicalChapterDecimalFraction.parse("1234500") shouldBe CanonicalChapterDecimalFraction(12345, 5)
    }

    @Test
    fun `invalid ambiguous zero and oversized fractions remain unparsed`() {
        CanonicalChapterDecimalFraction.parse("0") shouldBe null
        CanonicalChapterDecimalFraction.parse("000") shouldBe null
        CanonicalChapterDecimalFraction.parse("0000000001") shouldBe null
        CanonicalChapterDecimalFraction.parse("1234567890") shouldBe null
        CanonicalChapterDecimalFraction.parse("1a") shouldBe null
        CanonicalChapterDecimalFraction.parse("") shouldBe null
    }

    @Test
    fun `numeric comparison handles differing decimal scales correctly`() {
        val values = listOf("5", "005", "25", "05", "15", "50")
            .map { CanonicalChapterDecimalFraction.parse(it)!! }
            .sorted()

        values.map { it.asNormalizedDigits() } shouldBe listOf("005", "05", "15", "25", "5", "5")
        values.distinct().size shouldBe 5
    }
}
