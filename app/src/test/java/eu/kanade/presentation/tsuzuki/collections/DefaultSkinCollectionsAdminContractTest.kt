package eu.kanade.presentation.tsuzuki.collections

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinCollectionsAdminContractTest {

    @Test
    fun `root composition owns Collections chrome and keeps open edit and reorder owners`() {
        val source = readAppSource("collections/CollectionsScreen.kt")

        source shouldContain "CollectionsAdminRootPage("
        source shouldContain "tsuzuki_collections_admin_title"
        source shouldContain "tsuzuki_collections_overview"
        source shouldContain "DefaultSkinPrimaryButton("
        source shouldContain "CollectionsAdminSurface("
        source shouldContain "CollectionCardAction.OPEN"
        source shouldContain "CollectionCardAction.EDIT"
        source shouldContain "CollectionsAction.ReorderCollections(displayedOrder)"
        source shouldContain "CollectionsAction.DuplicateCollection(graph.collection.id)"
        source shouldContain "onImportRequest"
        source shouldContain "onExportRequest"
        source shouldNotContain "Scaffold("
        source shouldNotContain "AlertDialog("
        source shouldNotContain "AppBar("
        source shouldNotContain "title = \"\""
    }

    @Test
    fun `editors use the Collections family and preserve persisted and staged action owners`() {
        val source = readAppSource("collections/CollectionEditorScreens.kt")

        source shouldContain "CollectionsAdminEditorPage("
        source shouldContain "CollectionsAdminNameField("
        source shouldContain "MaterialTheme.typography.titleLarge"
        source shouldContain "tsuzuki_collections_admin_basic"
        source shouldContain "tsuzuki_collections_admin_folders"
        source shouldContain "tsuzuki_collections_admin_lists"
        source shouldContain "CollectionsAction.CreateCollection("
        source shouldContain "folders = draftFolders.map(DraftFolderEntry::draft)"
        source shouldContain "CollectionsAction.RenameCollection("
        source shouldContain "CollectionsAction.CreateFolder("
        source shouldContain "CollectionsAction.RenameFolder("
        source shouldContain "CollectionsAction.CreateList("
        source shouldContain "CollectionsAction.ReorderFolders("
        source shouldContain "CollectionsAction.ReorderLists("
        source shouldContain "draftLists = draftLists + DraftListEntry("
        source shouldContain "onSubmitDraft?.invoke("
        source shouldContain "title = if (target is FolderListEditorTarget.New) \"New List\" else \"Edit List\""
        source shouldContain ".weight(1f)"
        val folderCard = source.substringAfter("private fun EditorFolderCard")
            .substringBefore("@Composable\nprivate fun EditorListCard")
        folderCard shouldContain "MaterialTheme.typography.titleLarge"
        folderCard shouldContain "pluralStringResource("
        folderCard shouldContain "EditorDragHandle("
        folderCard shouldContain "IconButton(onClick = onEdit)"
        folderCard shouldContain "CollectionsAdminOverflowMenu("
        val listCard = source.substringAfter("private fun EditorListCard")
            .substringBefore("@Composable\nprivate fun EditorDragHandle")
        listCard shouldContain "MaterialTheme.typography.titleLarge"
        listCard shouldContain "EditorDragHandle("
        listCard shouldContain "IconButton(onClick = onEdit)"
        listCard shouldContain "CollectionsAdminOverflowMenu("
        source shouldNotContain "Scaffold("
        source shouldNotContain "AppBar("
        source shouldNotContain "OutlinedTextField("
        source shouldNotContain "Define the folder identity and add catalog Lists to organize what it contains."
    }

    @Test
    fun `destructive sheet confirms once and preserves built in hide ownership`() {
        val source = readAppSource("collections/CollectionsScreen.kt")

        source shouldContain "CollectionsAction.DeleteCollection"
        source shouldContain "CollectionsAction.DeleteFolder"
        source shouldContain "CollectionsAction.DeleteList"
        source shouldContain "CollectionOrigin.SYSTEM"
        source shouldContain "onAction(target.action)"
        source shouldContain "dismissLabel = stringResource(MR.strings.action_cancel)"
        source shouldContain "onDismiss = { deleteTarget = null }"
        source.countOccurrences("onAction(target.action)") shouldBe 1
    }

    @Test
    fun `root and editor no longer contain legacy hard coded admin copy`() {
        val root = readAppSource("collections/CollectionsScreen.kt")
        val editor = readAppSource("collections/CollectionEditorScreens.kt")
        val oldAdminCopy = listOf(
            "Collections",
            "New Collection",
            "Edit Collection",
            "Collection name",
            "Save changes",
            "Create collection",
            "BASIC",
            "FOLDERS",
            "New Folder",
            "New Subfolder",
            "Edit Folder",
            "Folder name",
            "Create folder",
            "LISTS",
            "Add folder",
            "Add list",
            "No folders yet",
            "Add one now or organize this Collection later.",
            "No Lists yet",
            "Add a catalog query now or configure this folder later.",
            "Define the folder identity and add catalog Lists to organize what it contains.",
            "Edit folder",
            "Folder actions",
            "Edit list",
            "List actions",
            "Drag to reorder",
            "Duplicate",
            "Delete",
            "OK",
            "Import complete",
            "Collections error",
            "Working…",
            "Imported \${transfer.result.collectionIds.size} collection(s), ",
            "Hide built-in item?",
            "Delete item?",
            "This built-in definition will stay hidden on this device.",
            "This uses a tombstone so future sync can preserve the deletion.",
        )

        oldAdminCopy.forEach { copy ->
            val kotlinLiteral = "\"$copy\""
            root shouldNotContain kotlinLiteral
            editor shouldNotContain kotlinLiteral
        }
        // The ListBuilder titles are routing sentinels and intentionally stay unchanged.
        editor shouldContain "title = if (target is FolderListEditorTarget.New) \"New List\" else \"Edit List\""
    }

    @Test
    fun `Collections surfaces use Default Skin primitives and localized resources`() {
        val presentation = readAppSource("collections/CollectionsAdminPresentation.kt")
        val base = readResource("base/strings_default_skin_collections.xml")
        val ptBr = readResource("pt-rBR/strings_default_skin_collections.xml")

        presentation shouldContain "defaultSkinColors()"
        presentation shouldContain "DefaultSkinPageTitle("
        presentation shouldContain "DefaultSkinSectionLabel("
        presentation shouldContain "DefaultSkinTextField("
        presentation shouldContain "ModalBottomSheet("
        presentation shouldContain ".background(colors.canvas)"
        presentation shouldContain "containerColor = colors.surface"
        presentation shouldContain "scrimColor = colors.foreground.copy"
        presentation shouldNotContain "AlertDialog("
        presentation shouldNotContain "MaterialTheme.colorScheme"
        presentation shouldContain "MaterialSymbols.AutoMirroredRounded.ArrowBack"
        presentation.substringAfter("internal fun CollectionsAdminSectionHeader")
            .substringBefore("internal fun CollectionsAdminSurface") shouldContain "heightIn(min = 48.dp)"
        presentation shouldContain "widthIn(min = 176.dp, max = 280.dp)"
        val sheet = presentation.substringAfter("internal fun CollectionsAdminSheet")
        val dismissableActions = sheet.substringAfter("if (dismissLabel != null) {")
            .substringBefore("} else {")
        dismissableActions shouldContain "DefaultSkinTextAction("
        dismissableActions shouldContain "onClick = onDismiss"
        dismissableActions shouldContain "heightIn(min = 48.dp)"
        dismissableActions shouldContain "DefaultSkinPrimaryButton("
        dismissableActions shouldContain "onClick = onConfirm"
        val editorPage = presentation.substringAfter("internal fun CollectionsAdminEditorPage")
            .substringBefore("internal fun CollectionsAdminEditorFooter")
        editorPage shouldContain "content()"
        editorPage shouldContain "CollectionsAdminEditorFooter("
        val footer = presentation.substringAfter("internal fun CollectionsAdminEditorFooter")
            .substringBefore("internal fun CollectionsAdminNameField")
        footer shouldContain "HorizontalDivider(color = colors.outline)"
        footer shouldContain "DefaultSkinPrimaryButton("
        val surface = presentation.substringAfter("internal fun CollectionsAdminSurface")
            .substringBefore("internal fun CollectionsAdminOverflowMenu")
        surface shouldContain "color = colors.surface"
        surface shouldContain "contentColor = colors.foreground"

        listOf(
            "tsuzuki_collections_admin_title",
            "tsuzuki_collections_admin_basic",
            "tsuzuki_collections_admin_collection_name",
            "tsuzuki_collections_admin_folders",
            "tsuzuki_collections_admin_add_folder",
            "tsuzuki_collections_admin_create_collection",
            "tsuzuki_collections_admin_save_changes",
            "tsuzuki_collections_admin_new_subfolder",
            "tsuzuki_collections_admin_add_list",
            "tsuzuki_collections_admin_import_success_message",
        ).forEach { key ->
            base shouldContain "name=\"$key\""
            ptBr shouldContain "name=\"$key\""
        }

        ptBr shouldContain ">Collections</string>"
        ptBr shouldContain ">BÁSICO</string>"
        ptBr shouldContain ">Nome da coleção</string>"
        ptBr shouldContain ">PASTAS</string>"
        ptBr shouldContain ">Adicionar pasta</string>"
        ptBr shouldContain ">Criar coleção</string>"
        ptBr shouldContain ">Salvar alterações</string>"
        ptBr shouldContain ">Nova subpasta</string>"
        ptBr shouldContain ">LISTAS</string>"
        ptBr shouldContain ">Adicionar lista</string>"
        base shouldContain "<plurals name=\"tsuzuki_collections_admin_folder_count\">"
        ptBr shouldContain "<plurals name=\"tsuzuki_collections_admin_list_count\">"
    }

    private fun readAppSource(relativePath: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
    )

    private fun readResource(relativePath: String): String = readFirst(
        Path.of("i18n/src/commonMain/moko-resources/$relativePath"),
        Path.of("../i18n/src/commonMain/moko-resources/$relativePath"),
    )

    private fun readFirst(vararg candidates: Path): String {
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Source file was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(path)
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1
}
