package eu.kanade.presentation.tsuzuki.collections

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinCollectionsAdminContractTest {

    @Test
    fun `list builder uses explicit localized modes and transactional Default Skin controls`() {
        val builder = readAppSource("collections/ListBuilderScreen.kt")
        val advanced = builder.substringAfter("private fun AdvancedListFilters(")
            .substringBefore("private fun AdvancedSection(")
        val lookupControl = builder.substringAfter("private fun RemoteLookupFilterControl(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)")
        val lookupApply = lookupControl.substringAfter("onApply = {")
            .substringBefore("            },")
        val editorTests = readAppTest("CollectionListEditorTest.kt")

        builder shouldContain "internal enum class ListBuilderMode"
        builder shouldContain "ListBuilderMode.CREATE -> MR.strings.tsuzuki_list_builder_create_title"
        builder shouldContain "ListBuilderMode.EDIT -> MR.strings.tsuzuki_list_builder_edit_title"
        builder shouldContain "descriptor?.filters?.filter { it.placement == FilterPlacement.QUICK }"
        builder shouldContain "if (quickFilters.isNotEmpty() && editor.filtersEditable)"
        builder shouldContain "it.placement == FilterPlacement.ADVANCED"
        builder shouldContain "CollectionProviderSwitchPlanner.plan("
        builder shouldContain "if (plan.requiresConfirmation)"
        builder shouldContain "is CollectionProviderSwitchPlan.Blocked"
        builder shouldContain "RemoteLookupSheetState.open(editor, capability)"
        lookupApply shouldContain "val current = transaction?.takeIf(RemoteLookupSheetState::isOpen)"
        lookupApply shouldContain "current.apply(editor, capability, onEditorChange)"
        (
            lookupApply.indexOf("transaction = null") <
                lookupApply.indexOf("current.apply(editor, capability, onEditorChange)")
            ) shouldBe true
        builder shouldContain "onDismiss = { transaction = null }"
        builder shouldContain ".imePadding()"
        builder shouldContain "is QueryField.Custom -> id"
        builder shouldContain "is QueryField.Custom -> field.identifier"
        builder shouldContain "if (providerId.isNotBlank())"
        builder shouldContain ".verticalScroll(rememberScrollState())"
        builder shouldContain "previewState.items.take(PREVIEW_TITLE_LIMIT)"
        builder shouldContain "MangaCover.Book("
        builder shouldContain "data = item.coverUrl"
        builder shouldNotContain "mockup"
        builder shouldNotContain "DropdownMenu("
        advanced shouldContain "text = it.toCanonicalString()"
        editorTests shouldContain "advanced any query is preserved exactly when visual editor cannot represent it"
        editorTests shouldContain "draft.query shouldBe query"
        builder shouldNotContain "New List"
        builder shouldNotContain "Edit List"
        builder shouldNotContain "Scaffold("
        builder shouldNotContain "AlertDialog("
        builder shouldNotContain "OutlinedTextField("
    }

    @Test
    fun `display and remote tri-state selectors use a control segment while real chips remain`() {
        val builder = readAppSource("collections/ListBuilderScreen.kt")
        val basic = builder.substringAfter("item(key = \"basic\") {")
            .substringBefore("item(key = \"source\")")
        val segment = builder.substringAfter("private fun ListBuilderSegmentControl(")
            .substringBefore("@Composable\nprivate fun BuilderGroup(")
        val lookupSheet = builder.substringAfter("private fun RemoteLookupFilterSheet(")
            .substringBefore("private fun <T> BuilderDropdown(")
        val selectionTransition = builder.substringAfter("fun selectValue(")
            .substringBefore("fun clear(): RemoteLookupDraft")
        val booleanFacet = builder.substringAfter("FilterValueSource.BooleanToggle ->")
            .substringBefore("is FilterValueSource.RemoteLookup ->")

        basic shouldContain "ListBuilderSegmentControl("
        basic shouldNotContain "DefaultSkinChoiceChip("
        segment shouldContain "selectable("
        segment shouldContain "Role.RadioButton"
        segment shouldContain "heightIn(min = 48.dp)"
        segment shouldContain "DefaultSkinShape.Control"
        segment shouldContain "if (!isSelected) onSelected(value)"
        lookupSheet shouldContain "ListBuilderSegmentControl("
        lookupSheet shouldNotContain "DefaultSkinChoiceChip("
        lookupSheet shouldContain "val canExclude = capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE"
        lookupSheet shouldContain "if (canExclude)"
        lookupSheet shouldContain "included -> \"include\""
        lookupSheet shouldContain "excluded -> \"exclude\""
        lookupSheet shouldContain "else -> \"neutral\""
        lookupSheet shouldContain "state.selectValue(option.value, selection, capability)"
        lookupSheet shouldNotContain "state.include(option.value, capability)"
        lookupSheet shouldNotContain "state.exclude(option.value, capability)"
        selectionTransition shouldContain "if (selection == currentSelection) return this"
        selectionTransition shouldContain "val cleared = neutral(value)"
        selectionTransition shouldContain "cleared.include(value, capability)"
        selectionTransition shouldContain "cleared.exclude(value, capability)"
        selectionTransition shouldContain "includes = cleared.includes + selected.includes.filter { it.value == value }"
        selectionTransition shouldContain "excludes = cleared.excludes + selected.excludes.filter { it.value == value }"
        builder shouldContain "DefaultSkinChoiceChip(" // Active filters and boolean facets remain chips.
        booleanFacet shouldContain "DefaultSkinChoiceChip("
        builder shouldContain "DefaultSkinTextField("
        val sharedTextField = readAppSource("components/DefaultSkinPrimitives.kt")
            .substringAfter("fun DefaultSkinTextField(")
            .substringBefore("@Composable\nfun DefaultSkinFormField(")
        sharedTextField shouldContain "color = colors.surfaceMuted"
        sharedTextField shouldNotContain "containerColor"
        builder shouldContain "onValueChange = { onStateChange(state.search(it)) }"
        builder shouldContain "current.apply(editor, capability, onEditorChange)"
    }

    @Test
    fun `root composition owns Collections chrome and keeps open edit and reorder owners`() {
        val source = readAppSource("collections/CollectionsScreen.kt")
        val rootUi = source.substringAfter("if (!fullScreenEditorRendered) {")
            .substringBefore("deleteTarget?.let { target ->")

        source shouldContain "CollectionsAdminRootPage("
        source shouldContain "tsuzuki_collections_admin_title"
        source shouldNotContain "DefaultSkinSectionLabel(text = stringResource(MR.strings.tsuzuki_collections_overview))"
        source shouldContain "DefaultSkinPrimaryButton("
        source shouldContain "CollectionsAdminSurface("
        source shouldContain "CollectionCardAction.OPEN"
        source shouldContain "CollectionCardAction.EDIT"
        source shouldContain "CollectionsAction.ReorderCollections(displayedOrder)"
        source shouldContain "CollectionsAction.DuplicateCollection(graph.collection.id)"
        source shouldContain "onImportRequest"
        source shouldContain "onExportRequest"
        rootUi shouldNotContain "Scaffold("
        rootUi shouldNotContain "AlertDialog("
        rootUi shouldNotContain "AppBar("
        rootUi shouldNotContain "title = \"\""
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
        source shouldContain
            "mode = if (target is FolderListEditorTarget.New) ListBuilderMode.CREATE else ListBuilderMode.EDIT"
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
        val root = readAppSource("collections/CollectionsScreen.kt")
        val editor = readAppSource("collections/CollectionEditorScreens.kt")
        val confirmation = root.substringAfter("deleteTarget?.let { target ->")

        root shouldContain "CollectionsAction.DeleteCollection(collection.id)"
        editor shouldContain "action = CollectionsAction.DeleteFolder(model.folder.id)"
        editor shouldContain "systemOwned = model.folder.origin == CollectionOrigin.SYSTEM"
        editor shouldContain "action = CollectionsAction.DeleteList(list.id)"
        editor shouldContain "systemOwned = list.origin == CollectionOrigin.SYSTEM"
        root shouldContain "onDelete = { deleteTarget = it }"
        confirmation shouldContain "CollectionsAdminSheet("
        confirmation shouldNotContain "AlertDialog("
        confirmation shouldContain "onAction(target.action)"
        confirmation shouldContain "dismissLabel = stringResource(MR.strings.action_cancel)"
        confirmation shouldContain "onDismiss = { deleteTarget = null }"
        confirmation.countOccurrences("onAction(target.action)") shouldBe 1
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
        // Create/edit mode owns localized presentation and remains independent of display text.
        editor shouldContain
            "mode = if (target is FolderListEditorTarget.New) ListBuilderMode.CREATE else ListBuilderMode.EDIT"
    }

    @Test
    fun `Collections surfaces use Default Skin primitives and localized resources`() {
        val presentation = readAppSource("collections/CollectionsAdminPresentation.kt")
        val base = readResource("base/strings_default_skin_collections.xml")
        val ptBr = readResource("pt-rBR/strings_default_skin_collections.xml")
        val basePlurals = readResource("base/plurals.xml")
        val ptBrPlurals = readResource("pt-rBR/plurals.xml")

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

        ptBr shouldContain ">Coleções</string>"
        ptBr shouldContain ">BÁSICO</string>"
        ptBr shouldContain ">Nome da coleção</string>"
        ptBr shouldContain ">PASTAS</string>"
        ptBr shouldContain ">Adicionar pasta</string>"
        ptBr shouldContain ">Criar coleção</string>"
        ptBr shouldContain ">Salvar alterações</string>"
        ptBr shouldContain ">Nova subpasta</string>"
        ptBr shouldContain ">LISTAS</string>"
        ptBr shouldContain ">Adicionar lista</string>"
        listOf(basePlurals, ptBrPlurals).forEach { plurals ->
            plurals shouldContain "<plurals name=\"tsuzuki_collections_admin_folder_count\">"
            plurals shouldContain "<plurals name=\"tsuzuki_collections_admin_list_count\">"
        }
        basePlurals shouldContain "<item quantity=\"one\">%1\$d folder</item>"
        basePlurals shouldContain "<item quantity=\"other\">%1\$d folders</item>"
        basePlurals shouldContain "<item quantity=\"one\">%1\$d list</item>"
        basePlurals shouldContain "<item quantity=\"other\">%1\$d lists</item>"
        ptBrPlurals shouldContain "<item quantity=\"one\">%1\$d pasta</item>"
        ptBrPlurals shouldContain "<item quantity=\"other\">%1\$d pastas</item>"
        ptBrPlurals shouldContain "<item quantity=\"one\">%1\$d lista</item>"
        ptBrPlurals shouldContain "<item quantity=\"other\">%1\$d listas</item>"
    }

    @Test
    fun `Collections admin name field is single line`() {
        val presentation = readAppSource("collections/CollectionsAdminPresentation.kt")
        val nameField = presentation.substringAfter("internal fun CollectionsAdminNameField")
            .substringBefore("internal fun CollectionsAdminSectionHeader")

        nameField shouldContain "singleLine = true"
        nameField shouldNotContain "singleLine = false"
    }

    private fun readAppSource(relativePath: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
    )

    private fun readAppTest(relativePath: String): String = readFirst(
        Path.of("src/test/java/eu/kanade/presentation/tsuzuki/collections/$relativePath"),
        Path.of("app/src/test/java/eu/kanade/presentation/tsuzuki/collections/$relativePath"),
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
