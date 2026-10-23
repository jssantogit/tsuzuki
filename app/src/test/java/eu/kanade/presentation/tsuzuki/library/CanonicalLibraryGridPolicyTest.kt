package eu.kanade.presentation.tsuzuki.library

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalLibraryGridPolicyTest {

    @Test
    fun `phone and tablet widths produce bounded columns with expected cell widths`() {
        val phoneWidth = 360.dp
        val phoneColumns = canonicalLibraryGridColumnCount(phoneWidth)
        phoneColumns shouldBe 3
        val phoneCellWidth = (phoneWidth - 32.dp - 24.dp) / phoneColumns
        (phoneCellWidth >= 101.dp) shouldBe true
        (phoneCellWidth < 102.dp) shouldBe true

        val tabletColumns = canonicalLibraryGridColumnCount(800.dp)
        (tabletColumns > phoneColumns) shouldBe true
        canonicalLibraryGridColumnCount(120.dp) shouldBe 1
    }
}
