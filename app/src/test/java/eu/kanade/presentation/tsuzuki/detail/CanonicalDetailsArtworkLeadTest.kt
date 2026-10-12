package eu.kanade.presentation.tsuzuki.detail

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
}
