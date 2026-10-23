package eu.kanade.presentation.tsuzuki.integration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RatingScaleLabelTest {

    @Test
    fun `floating point noise is never displayed as a raw Tsuzuki rating`() {
        ratingScaleLabel("tsuzuki", 8.463000000000001, 10.0) shouldBe "8.46/10"
        ratingScaleLabel("tsuzuki", 8.2, 10.0) shouldBe "8.2/10"
        ratingScaleLabel("mal", 8.0, 10.0) shouldBe "8/10"
    }

    @Test
    fun `percentage scores have at most two decimals`() {
        ratingScaleLabel("kitsu", 84.76999999999998, 100.0) shouldBe "84.77%"
        ratingScaleLabel("kitsu", 80.0, 100.0) shouldBe "80%"
    }
}
