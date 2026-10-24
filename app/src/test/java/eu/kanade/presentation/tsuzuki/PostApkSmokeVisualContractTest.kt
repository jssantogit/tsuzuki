package eu.kanade.presentation.tsuzuki

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PostApkSmokeVisualContractTest {

    @Test
    fun `about screen uses Default Skin canvas and surfaces`() {
        val screen = source("presentation/more/settings/screen/about/AboutScreen.kt")
        screen shouldContain "val colors = defaultSkinColors()"
        screen shouldContain "containerColor = colors.canvas"
        screen shouldContain "contentColor = colors.foreground"
        screen shouldContain "DefaultSkinCard("
        screen shouldNotContain "tonalElevation = 1.dp"
    }

    @Test
    fun `reader source recovery dialog uses neutral Default Skin palette and actions`() {
        val session = source("presentation/reader/ReaderSessionCards.kt")
        val context = session.substringAfter("fun ReaderContextCard(")
            .substringBefore("fun ColumnScope.ReaderChaptersCard(")
        context shouldContain "val colors = defaultSkinColors()"
        context shouldContain "shape = DefaultSkinShape.Card"
        context shouldContain "color = colors.surface"
        context shouldContain "contentColor = colors.foreground"
        context shouldContain "border = BorderStroke(1.dp, colors.outline)"
        context shouldContain "tonalElevation = 0.dp"
        context shouldContain "shadowElevation = 0.dp"
        context shouldContain "DefaultSkinTextAction("

        val source = source("presentation/reader/ReaderSourceCard.kt")
        val unavailable = source.substringAfter("private fun UnavailableActions(")
        unavailable shouldContain "DefaultSkinPrimaryButton("
        unavailable shouldContain "DefaultSkinSecondaryButton("
        unavailable shouldNotContain "OutlinedButton("
    }

    @Test
    fun `installed Providers share Discover card proportions and independent items`() {
        val source = source("presentation/more/settings/screen/SettingsTsuzukiProvidersDefaultSkinScreen.kt")
        val installed = source.substringAfter("private fun InstalledProviderCards(")
            .substringBefore("private fun InstalledProviderRow(")
        installed shouldContain "items("
        installed shouldContain "DefaultSkinCard("
        installed shouldContain "Arrangement.spacedBy(12.dp)"
        installed shouldContain "DefaultSkinSpacing.PageHorizontal"
        installed shouldNotContain "SettingsGroup {"
        installed shouldNotContain "SettingsGroupDivider()"
    }

    private fun source(relative: String): String = Files.readString(
        listOf(
            Path.of("src/main/java/eu/kanade/$relative"),
            Path.of("app/src/main/java/eu/kanade/$relative"),
        ).first(Files::exists),
    )
}
