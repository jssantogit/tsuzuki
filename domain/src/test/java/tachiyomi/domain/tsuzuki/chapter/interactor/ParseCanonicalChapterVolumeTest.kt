package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ParseCanonicalChapterVolumeTest {

    private val parse = ParseCanonicalChapterVolume()

    @Test
    fun `explicit volume chapter labels return the numeric volume`() {
        parse.execute("Vol.1 Ch.1") shouldBe 1
        parse.execute("Vol. 12 - Chapter 137") shouldBe 12
        parse.execute("Volume 3: Ch. 37.5") shouldBe 3
    }

    @Test
    fun `volume is unknown when the label omits a numeric volume`() {
        parse.execute("Chapter 37").shouldBeNull()
        parse.execute("Vol.none Ch. 37").shouldBeNull()
        parse.execute("Vol. 3 Extra").shouldBeNull()
    }

    @Test
    fun `repeated consistent volume in chapter subtitle is not mistaken for conflicting editions`() {
        parse.execute("Vol. 1 Ch. 1 - Chapter 1 - Volume 1 (1r0n)") shouldBe 1
        parse.execute("Vol. 1 Ch. 1 - Chapter 1 - Volume 112").shouldBeNull()
        parse.execute("Ch. 0 - Volume 112").shouldBeNull()
    }

    @Test
    fun `numbered chapter with consistent subtitle volume is parsed`() {
        (1..6).forEach { chapter ->
            parse.execute("Ch. $chapter - Chapter $chapter - Volume 1 (1r0n)") shouldBe 1
        }
        parse.execute("Ch. 1 - Chapter 2 - Volume 1").shouldBeNull()
        parse.execute("Ch. 0 - Volume 112").shouldBeNull()
    }

    @Test
    fun `ambiguous or out of range volume labels are not guessed`() {
        parse.execute("Vol. 1 Ch. 37 Vol. 2 Ch. 37").shouldBeNull()
        parse.execute("Vol. 1-2 Ch. 37").shouldBeNull()
        parse.execute("Vol. 999999999999999999 Ch. 37").shouldBeNull()
    }
}
