package eu.kanade.presentation.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class F6DDetailsPolicyTest {
    @Test
    fun `hero genres omit blanks preserve order and cap at three real labels`() {
        detailsHeroGenres(listOf("", "Action", " ", "Adventure", "Drama", "Fantasy")) shouldBe
            listOf("Action", "Adventure", "Drama")
        detailsHeroGenres(emptyList()) shouldBe emptyList()
        detailsHeroGenres(listOf("Novel")) shouldBe listOf("Novel")
    }

    @Test
    fun `toolbar remains transparent over artwork and fades continuously at the visual boundary`() {
        detailsToolbarFraction(500, 80, 0, 0) shouldBe 0f
        detailsToolbarFraction(500, 80, 0, 340) shouldBe 0f
        detailsToolbarFraction(500, 80, 0, 380) shouldBe 0.5f
        detailsToolbarFraction(500, 80, 0, 420) shouldBe 1f
        detailsToolbarFraction(500, 80, 1, 0) shouldBe 1f
        detailsToolbarFraction(0, 80, 0, 0) shouldBe 0f
        detailsToolbarFraction(500, 80, 0, 0) shouldBe 0f
        val fractions = (0..600).map { detailsToolbarFraction(500, 80, 0, it) }
        fractions.zipWithNext().all { (a, b) -> b >= a && b - a < 0.013f } shouldBe true
    }
}
