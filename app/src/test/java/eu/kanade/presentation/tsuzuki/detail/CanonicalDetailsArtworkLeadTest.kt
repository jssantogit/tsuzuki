package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalDetailsArtworkLeadTest {

    @Test
    fun `artwork lead preserves its width ratio within the established bounds`() {
        canonicalDetailsArtworkLead(180.dp) shouldBe 120.dp
        canonicalDetailsArtworkLead(320.dp) shouldBe 137.6.dp
        canonicalDetailsArtworkLead(1_024.dp) shouldBe 224.dp
    }

    @Test
    fun `hero fade leaves the upper artwork clear and reaches the frame background at its edge`() {
        val stops = canonicalDetailsHeroFadeStops(Color.White)

        stops.zipWithNext().all { (start, end) -> start.first < end.first } shouldBe true
        stops.first().second.alpha shouldBe 0f
        (stops.first { it.second.alpha > 0f }.first >= 0.44f) shouldBe true
        (stops.first { it.second.alpha >= 0.7f }.first >= 0.6f) shouldBe true
        stops.last().first shouldBe 1f
        stops.last().second shouldBe Color.White
    }
}
