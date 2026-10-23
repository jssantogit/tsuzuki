package eu.kanade.presentation.tsuzuki.components

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TsuzukiDefaultSkinCoverPolicyTest {

    @Test
    fun `standard and library variants share a capped 126 by 189 frame`() {
        TsuzukiDefaultSkinCoverSize.Standard.width shouldBe 126.dp
        (TsuzukiDefaultSkinCoverSize.Standard.width / TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO) shouldBe 189.dp
        TsuzukiDefaultSkinCoverSize.Library.width shouldBe 126.dp
        (TsuzukiDefaultSkinCoverSize.Library.width / TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO) shouldBe 189.dp
        TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO shouldBe 2f / 3f
    }
}
