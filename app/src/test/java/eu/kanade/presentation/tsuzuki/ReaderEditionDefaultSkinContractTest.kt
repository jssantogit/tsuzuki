package eu.kanade.presentation.tsuzuki

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReaderEditionDefaultSkinContractTest {
    @Test
    fun `reader source dialog uses the active Default Skin palette`() {
        val context = source("presentation/reader/ReaderSessionCards.kt")
            .substringAfter("fun ReaderContextCard(")
            .substringBefore("fun ColumnScope.ReaderChaptersCard(")
        context shouldContain "val colors = defaultSkinColors()"
        context shouldContain "shape = DefaultSkinShape.Card"
        context shouldContain "color = colors.surface"
        context shouldContain "contentColor = colors.foreground"
        context shouldContain "border = BorderStroke(1.dp, colors.outline)"
        context shouldContain "DefaultSkinTextAction("
        context shouldNotContain "MaterialTheme.colorScheme.surface"
    }

    @Test
    fun `reader unavailable actions use Default Skin components`() {
        val actions = source("presentation/reader/ReaderSourceCard.kt")
            .substringAfter("private fun UnavailableActions(")
        actions shouldContain "DefaultSkinPrimaryButton("
        actions shouldContain "DefaultSkinSecondaryButton("
        actions shouldNotContain "OutlinedButton("
    }

    @Test
    fun `manual edition picker uses existing skin controls and distinct cards`() {
        val picker = source("presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt")
            .substringAfter("DetailsSheet.EDITION -> Column(")
            .substringBefore("DetailsSheet.INVENTORY -> Column(")
        picker shouldContain "OutlinedTextFieldDefaults.colors("
        picker shouldContain "DefaultSkinShape.Card"
        picker shouldContain "colors.outline"
        picker shouldContain "colors.accent"
        picker shouldContain "DefaultSkinPrimaryButton("
        picker shouldNotContain "MaterialTheme.colorScheme"
    }

    private fun source(relative: String): String = Files.readString(
        listOf(
            Path.of("src/main/java/eu/kanade/$relative"),
            Path.of("app/src/main/java/eu/kanade/$relative"),
        ).first(Files::exists),
    )
}
