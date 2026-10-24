package eu.kanade.presentation.tsuzuki

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinF6HContractTest {

    @Test
    fun `Search titles only occupy their actual line count before ratings`() {
        val compact = source("presentation/tsuzuki/search/TsuzukiSearchScreen.kt")
            .substringAfter("internal fun SearchCompactCard(").substringBefore("internal fun SearchCompactArtwork(")
        compact shouldContain "minLines = 1"
        compact shouldContain "maxLines = 2"
        compact shouldNotContain "minLines = 2"
    }

    @Test
    fun `collections avoid a duplicated heading and are translated in Brazilian Portuguese`() {
        val collections = source("presentation/tsuzuki/collections/CollectionsScreen.kt")
            .substringAfter("private fun CollectionsOverview(").substringBefore("private fun CollectionCard(")
        collections shouldNotContain "tsuzuki_collections_overview"
        val admin = resource("pt-rBR/strings_default_skin_collections.xml")
        val settings = resource("pt-rBR/strings_default_skin_settings.xml")
        admin shouldContain "<string name=\"tsuzuki_collections_admin_title\">Coleções</string>"
        settings shouldContain "<string name=\"tsuzuki_collections_settings_title\">Coleções</string>"
        admin shouldNotContain "dados das Collections"
        admin shouldNotContain "Erro em Collections"
    }

    @Test
    fun `discover providers have individual breathing-room cards and compact installation action`() {
        val discover = source("presentation/more/settings/screen/SettingsTsuzukiProvidersDefaultSkinScreen.kt")
            .substringAfter(
                "private fun DiscoverProviderCards(",
            ).substringBefore("private fun DefaultSkinRepositoriesDialog(")
        discover shouldContain "DefaultSkinCard("
        discover shouldContain "verticalArrangement = Arrangement.spacedBy(12.dp)"
        discover shouldContain "minimumHeight = 40.dp"
        discover shouldNotContain "SettingsGroupDivider()"
    }

    @Test
    fun `chapter sort sheet uses selected Default Skin rows instead of stock text buttons`() {
        val details = source("presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt")
        val sheet = details.substringAfter("sheet?.let { current ->").substringBefore("DetailsSheet.JUMP ->")
        sheet shouldContain "defaultSkinColors()"
        sheet shouldContain "selectable("
        sheet shouldNotContain "TextButton("
    }

    @Test
    fun `chapter actions overflow adopts the Default Skin palette instead of Material defaults`() {
        val details = source("presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt")
        val toolbar = details.substringAfter("private fun DetailsToolbar(")
            .substringBefore("private fun ChapterJumpSheet(")
        toolbar shouldContain "val colors = defaultSkinColors()"
        toolbar shouldContain "shape = DefaultSkinShape.Card"
        toolbar shouldContain "containerColor = colors.surface"
        toolbar shouldContain "colors.surface.copy(alpha = 0.94f)"
        toolbar shouldNotContain "MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)"
    }

    @Test
    fun `backup and export use branded surfaces without altering SAF and export operations`() {
        val backup = source("presentation/more/settings/screen/data/CreateBackupScreen.kt")
        backup shouldContain "SettingsCanvas"
        backup shouldContain "SettingsPageHeader"
        backup shouldContain "DefaultSkinPrimaryButton"
        backup shouldContain "BackupCreateJob.isManualJobRunning(context)"
        backup shouldContain "BackupCreator.getFilename()"
        val export = source("presentation/more/settings/screen/SettingsDataScreen.kt")
        val dialog = export.substringAfter("private fun ColumnSelectionDialog(")
        dialog shouldContain "shape = DefaultSkinShape.Card"
        dialog shouldContain "containerColor = colors.surface"
        dialog shouldContain "CheckboxDefaults.colors("
        dialog shouldContain "DefaultSkinTextAction("
    }

    @Test
    fun `collection and folder browsing use Default Skin surfaces and semantic navigation`() {
        val browse = source("presentation/tsuzuki/collections/CollectionBrowseScreens.kt")
        browse shouldContain "val colors = defaultSkinColors()"
        browse shouldContain "DefaultSkinPageTitle("
        browse shouldContain "DefaultSkinTextAction("
        browse shouldContain "colors.canvas"
        browse shouldContain "colors.secondary"
        browse shouldContain "colors.foreground"
        browse shouldContain "navigateUp()"
        browse shouldNotContain "MaterialTheme.colorScheme"
        browse shouldNotContain "AppBar("
        browse shouldNotContain "TextButton("
    }

    @Test
    fun `catalog collection shortcut respects the selected locale`() {
        val catalog = source("presentation/tsuzuki/catalog/CatalogScreen.kt")
        catalog shouldContain "stringResource(MR.strings.tsuzuki_collections_admin_title)"
        catalog shouldNotContain "Text(\"Collections\")"
    }

    @Test
    fun `library filters sheet renders a Default Skin surface and drag handle`() {
        val library = source("presentation/tsuzuki/library/CanonicalLibraryScreen.kt")
        val sheet = library.substringAfter("private fun CanonicalLibraryFilterSheet(")
            .substringBefore("private fun FilterSection(")
        sheet shouldContain "shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)"
        sheet shouldContain "containerColor = colors.surface"
        sheet shouldContain "scrimColor = colors.canvas.copy(alpha = 0.55f)"
        sheet shouldContain "tonalElevation = 0.dp"
        sheet shouldContain "dragHandle = {"
        sheet shouldContain "DefaultSkinShape.Pill"
    }

    @Test
    fun `detail tabs and failure state resolve Default Skin tokens`() {
        val detail = source("presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt")
        val modes = detail.substringAfter("private fun DetailsModes(")
            .substringBefore("private fun DetailsToolbar(")
        modes shouldContain "val colors = defaultSkinColors()"
        modes shouldContain "colors.foreground"
        modes shouldContain "colors.secondary"
        modes shouldContain "colors.outline"
        modes shouldNotContain "MaterialTheme.colorScheme"
        val states = detail.substringAfter("fun CanonicalTitleDetailScreen(")
            .substringBefore("private fun LoadedDetails(")
        states shouldContain "DefaultSkinTextAction("
        states shouldContain "color = colors.accent"
        states shouldNotContain "TextButton("
    }

    @Test
    fun `editorial facts follow Default Skin foreground and secondary roles`() {
        val hero = source("presentation/tsuzuki/detail/CanonicalDetailsHero.kt")
        val editorial = hero.substringAfter("internal fun DetailsEditorial(")
            .substringBefore("private fun editorialFormatLabel(")
        editorial shouldContain "val colors = defaultSkinColors()"
        editorial shouldContain "colors.secondary"
        editorial shouldContain "colors.foreground"
        editorial shouldNotContain "MaterialTheme.colorScheme"
    }

    private fun source(relative: String): String = Files.readString(
        listOf(
            Path.of("src/main/java/eu/kanade/$relative"),
            Path.of("app/src/main/java/eu/kanade/$relative"),
        ).first(Files::exists),
    )

    private fun resource(relative: String): String = Files.readString(
        listOf(
            Path.of("../i18n/src/commonMain/moko-resources/$relative"),
            Path.of("i18n/src/commonMain/moko-resources/$relative"),
        ).first(Files::exists),
    )
}
