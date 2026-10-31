package eu.kanade.presentation.tsuzuki.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** A manual edition choice is secondary and must not appear in the empty chapter state. */
class CanonicalDetailsEditionVisibilityTest {

    @Test
    fun `empty chapters only offer normal provider settings`() {
        val rail = source("CanonicalDetailsChapterComponents.kt")
            .substringAfter("internal fun ContextualChapterRail(")
            .substringBefore("internal fun")
        rail.contains("tsuzuki_edition_choose") shouldBe false
        rail.contains("onChooseEdition") shouldBe false
        rail.contains("tsuzuki_details_providers") shouldBe true
    }

    @Test
    fun `manual selection lives in three-dot actions and reuses existing sheet`() {
        val screen = source("CanonicalTitleDetailScreen.kt")
        val menu = screen.substringAfter("private fun DetailsToolbar(")
            .substringBefore("private fun ChapterJumpSheet(")
        menu.contains("onChooseEdition?.let { action ->") shouldBe true
        menu.contains("tsuzuki_edition_choose") shouldBe true
        menu.contains("action()") shouldBe true
        screen.contains("onChooseEdition = onChooseEdition,") shouldBe true
        screen.contains("DetailsSheet.EDITION ->") shouldBe true
    }

    private fun source(name: String): String {
        val suffix = "src/main/java/eu/kanade/presentation/tsuzuki/detail/$name"
        return sequenceOf(File(suffix), File("app/$suffix"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("Missing screen source")
    }
}
