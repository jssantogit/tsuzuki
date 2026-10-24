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
            .substringAfter("private fun DiscoverProviderCards(").substringBefore("private fun DefaultSkinRepositoriesDialog(")
        discover shouldContain "DefaultSkinCard("
        discover shouldContain "verticalArrangement = Arrangement.spacedBy(12.dp)"
        discover shouldContain "minimumHeight = 40.dp"
        discover shouldNotContain "SettingsGroupDivider()"
    }

    @Test
    fun `chapter sort sheet uses selected Default Skin rows instead of stock text buttons`() {
        val details = source("presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt")
        val sheet = details.substringAfter("DetailsSheet.SORT ->").substringBefore("DetailsSheet.JUMP ->")
        sheet shouldContain "defaultSkinColors()"
        sheet shouldContain "selectable("
        sheet shouldNotContain "TextButton("
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
