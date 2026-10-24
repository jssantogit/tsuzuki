package eu.kanade.presentation.tsuzuki.library

import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.defaultSkinWorkGridColumnCount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalLibraryGridPolicyTest {

    @Test
    fun `phone and tablet widths produce bounded columns with expected cell widths`() {
        val phoneWidth = 360.dp
        val phoneColumns = defaultSkinWorkGridColumnCount(phoneWidth)
        phoneColumns shouldBe 3
        defaultSkinWorkGridColumnCount(320.dp) shouldBe 3
        defaultSkinWorkGridColumnCount(300.dp) shouldBe 3
        val phoneCellWidth = (phoneWidth - 32.dp - 24.dp) / phoneColumns
        (phoneCellWidth >= 101.dp) shouldBe true
        (phoneCellWidth < 102.dp) shouldBe true

        listOf(320, 360, 393, 412, 599).forEach { width ->
            defaultSkinWorkGridColumnCount(width.dp) shouldBe 3
            val cellWidth = (width.dp - 32.dp - 24.dp) / 3
            (cellWidth * 3 + 56.dp == width.dp) shouldBe true
        }
        val tabletColumns = defaultSkinWorkGridColumnCount(800.dp)
        (tabletColumns > phoneColumns) shouldBe true
        defaultSkinWorkGridColumnCount(120.dp) shouldBe 3
    }
}
