package eu.kanade.presentation.tsuzuki.components

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TsuzukiDefaultSkinCoverPolicyTest {

    @Test
    fun `standard and library variants share the canonical two thirds frame`() {
        TsuzukiDefaultSkinCoverSize.Standard.width shouldBe 148.dp
        (TsuzukiDefaultSkinCoverSize.Standard.width / TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO) shouldBe 222.dp
        TsuzukiDefaultSkinCoverSize.Library.width shouldBe 96.dp
        (TsuzukiDefaultSkinCoverSize.Library.width / TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO) shouldBe 144.dp
        TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO shouldBe 2f / 3f
    }
}
