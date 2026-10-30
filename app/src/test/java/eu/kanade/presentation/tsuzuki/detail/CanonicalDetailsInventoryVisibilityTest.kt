package eu.kanade.presentation.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-level guard for a screen whose Compose content is large and whose default sections
 * must not expose provider IDs, release pagination, or raw inventory observations.
 * An opt-in diagnostics sheet keeps manual candidate review available.
 */
class CanonicalDetailsInventoryVisibilityTest {

    @Test
    fun `compact and full chapter modes do not render the inventory diagnostics panel inline`() {
        val source = screenSource()
        val defaultBody = source.substringAfter("when (mode) {").substringBefore("DetailsToolbar(")

        defaultBody.contains("IndependentInventoryInfo(") shouldBe false
        defaultBody.contains("independent-inventory") shouldBe false
        defaultBody.contains("inventoryRefresh.refreshing") shouldBe false
        defaultBody.contains("ContextualChapterRail(") shouldBe true
    }

    @Test
    fun `inventory details and candidate review remain available only by explicit menu action`() {
        val source = screenSource()
        val sheetBody = source.substringAfter("sheet?.let { current ->")
            .substringBefore("private fun IndependentInventoryInfo(")

        sheetBody.contains("DetailsSheet.INVENTORY ->") shouldBe true
        sheetBody.contains("IndependentInventoryInfo(") shouldBe true
        source.contains("onInventory = { sheet = DetailsSheet.INVENTORY }") shouldBe true
        source.contains("onInventory?.let { action ->") shouldBe true
        source.contains("onReviewCandidates(source)") shouldBe true
    }

    private fun screenSource(): String {
        val suffix = "src/main/java/eu/kanade/presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt"
        return sequenceOf(File(suffix), File("app/$suffix"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing CanonicalTitleDetailScreen source for UI visibility contract")
    }
}
