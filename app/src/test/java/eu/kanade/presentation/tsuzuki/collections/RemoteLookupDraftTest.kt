package eu.kanade.presentation.tsuzuki.collections

import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class RemoteLookupDraftTest {

    @Test
    fun `opening copies applied include and exclude values into the local draft`() {
        val editor = editorWithLookupValues()

        val state = RemoteLookupSheetState.open(editor, includeExcludeCapability())

        state.draft.includeValues shouldContainExactly listOf(QueryValue.of("Adventure"))
        state.draft.excludeValues shouldContainExactly listOf(QueryValue.of("Horror"))
        editor.currentQuery() shouldBe editorWithLookupValues().currentQuery()
    }

    @Test
    fun `applying an untouched draft preserves every value and its operator`() {
        val query = QueryExpression.All(
            QueryExpression.Predicate(QueryField.GENRE, QueryOperator.CONTAINS, QueryValue.of("Adventure")),
            QueryExpression.Predicate(QueryField.GENRE, QueryOperator.EQUALS, QueryValue.of("Drama")),
            QueryExpression.Not(
                QueryExpression.Predicate(QueryField.GENRE, QueryOperator.EQUALS, QueryValue.of("Horror")),
            ),
            QueryExpression.Predicate(QueryField.TAG, QueryOperator.EQUALS, QueryValue.of("Action")),
        )
        val editor = ListEditorState.fromDraft(
            CollectionListDraft(
                title = "Multiple values",
                query = query,
                sort = CollectionSortSelection.DEFAULT,
                layoutType = null,
                providerId = "kitsu",
            ),
        )
        val capability = includeExcludeCapability()
        val state = RemoteLookupSheetState.open(editor, capability)
        var updated = editor

        val closed = state.apply(editor, capability) { updated = it }

        state.draft.includeValues shouldContainExactly listOf(QueryValue.of("Adventure"), QueryValue.of("Drama"))
        state.draft.excludeValues shouldContainExactly listOf(QueryValue.of("Horror"))
        updated.currentQuery()?.normalize() shouldBe editor.currentQuery()?.normalize()
        closed.isOpen shouldBe false
    }

    @Test
    fun `neutral include and exclude update only the draft`() {
        val editor = editorWithLookupValues()
        val capability = includeExcludeCapability()
        val adventure = QueryValue.of("Adventure")
        val horror = QueryValue.of("Horror")
        val opened = RemoteLookupSheetState.open(editor, capability)

        val neutral = opened.neutral(adventure)
        neutral.draft.includeValues shouldBe emptyList()
        neutral.draft.excludeValues shouldContainExactly listOf(horror)

        val included = neutral.include(adventure, capability)
        included.draft.includeValues shouldContainExactly listOf(adventure)
        included.draft.excludeValues shouldContainExactly listOf(horror)

        val excluded = included.exclude(adventure, capability)
        excluded.draft.includeValues shouldContainExactly listOf(adventure)
        excluded.draft.excludeValues shouldContainExactly listOf(adventure)
        editor.currentQuery() shouldBe editorWithLookupValues().currentQuery()
    }

    @Test
    fun `include preserves all semantics while single-value modes replace the include`() {
        val editor = ListEditorState.empty()
        val adventure = QueryValue.of("Adventure")
        val drama = QueryValue.of("Drama")

        val allCapability = includeExcludeCapability(MultiValueMode.ALL)
        val all = RemoteLookupSheetState.open(editor, allCapability)
            .include(adventure, allCapability)
            .include(drama, allCapability)
        all.draft.includeValues shouldContainExactly listOf(adventure, drama)
        all.exclude(adventure, allCapability).draft.excludeValues shouldBe emptyList()

        val singleCapability = includeExcludeCapability(MultiValueMode.SINGLE)
        val single = RemoteLookupSheetState.open(editor, singleCapability)
            .include(adventure, singleCapability)
            .include(drama, singleCapability)
        single.draft.includeValues shouldContainExactly listOf(drama)
        single.exclude(adventure, singleCapability).draft.excludeValues shouldBe emptyList()
    }

    @Test
    fun `search result changes retain selections that are not currently visible`() {
        val editor = editorWithLookupValues()
        val state = RemoteLookupSheetState.open(editor, includeExcludeCapability())

        val filtered = state.search("horror")

        filtered.query shouldBe "horror"
        filtered.draft.includeValues shouldContainExactly listOf(QueryValue.of("Adventure"))
        filtered.draft.excludeValues shouldContainExactly listOf(QueryValue.of("Horror"))
    }

    @Test
    fun `clear resets only the local draft`() {
        val editor = editorWithLookupValues()
        val state = RemoteLookupSheetState.open(editor, includeExcludeCapability())
            .clearDraft()

        state.draft.includeValues shouldBe emptyList()
        state.draft.excludeValues shouldBe emptyList()
        editor.currentQuery() shouldBe editorWithLookupValues().currentQuery()
    }

    @Test
    fun `apply commits the full draft once and keeps other query terms`() {
        val original = editorWithLookupValues()
        val capability = includeExcludeCapability()
        val romance = QueryValue.of("Romance")
        val mystery = QueryValue.of("Mystery")
        var applied = original
        var commitCount = 0
        val ready = RemoteLookupSheetState.open(original, capability)
            .clearDraft()
            .include(romance, capability)
            .exclude(mystery, capability)

        val closed = ready.apply(original, capability) {
            commitCount += 1
            applied = it
        }
        closed.apply(applied, capability) { commitCount += 1 } shouldBe closed

        commitCount shouldBe 1
        applied.currentQuery()?.normalize() shouldBe QueryExpression.All(
            QueryExpression.Predicate(QueryField.GENRE, QueryOperator.EQUALS, romance),
            QueryExpression.Not(QueryExpression.Predicate(QueryField.GENRE, QueryOperator.EQUALS, mystery)),
            QueryExpression.Predicate(QueryField.TAG, QueryOperator.EQUALS, QueryValue.of("Action")),
        ).normalize()
        applied.title shouldBe original.title
        applied.sort shouldBe original.sort
        applied.layoutType shouldBe original.layoutType
        applied.providerId shouldBe original.providerId
    }

    @Test
    fun `cancel or dismiss closes without committing and reopening uses applied values`() {
        val original = editorWithLookupValues()
        val capability = includeExcludeCapability()
        var applied = original
        var commitCount = 0
        val cancelled = RemoteLookupSheetState.open(original, capability)
            .clearDraft()
            .cancel()

        cancelled.apply(original, capability) {
            commitCount += 1
            applied = it
        } shouldBe cancelled
        commitCount shouldBe 0
        applied.currentQuery() shouldBe original.currentQuery()

        val reopenedAfterCancel = RemoteLookupSheetState.open(applied, capability)
        reopenedAfterCancel.draft.includeValues shouldContainExactly listOf(QueryValue.of("Adventure"))
        reopenedAfterCancel.draft.excludeValues shouldContainExactly listOf(QueryValue.of("Horror"))

        val committed = reopenedAfterCancel
            .include(QueryValue.of("Romance"), capability)
            .apply(applied, capability) {
                commitCount += 1
                applied = it
            }
        val reopenedAfterApply = RemoteLookupSheetState.open(applied, capability)

        commitCount shouldBe 1
        committed.isOpen shouldBe false
        reopenedAfterApply.draft.includeValues shouldContainExactly listOf(QueryValue.of("Romance"))
        reopenedAfterApply.draft.excludeValues shouldContainExactly listOf(QueryValue.of("Horror"))
    }

    @Test
    fun `exclude is a no-op when the provider capability does not expose it`() {
        val capability = includeExcludeCapability(MultiValueMode.ALL)
        val value = QueryValue.of("Adventure")

        val state = RemoteLookupSheetState.open(ListEditorState.empty(), capability)
            .exclude(value, capability)

        state.draft.excludeValues shouldBe emptyList()
    }

    @Test
    fun `UI transitions move one value between include exclude and neutral without losing others`() {
        val adventure = QueryValue.of("Adventure")
        val drama = QueryValue.of("Drama")
        val horror = QueryValue.of("Horror")
        val mystery = QueryValue.of("Mystery")
        val capability = includeExcludeCapability(
            operators = setOf(QueryOperator.EQUALS, QueryOperator.CONTAINS),
        )
        val original = RemoteLookupDraft(
            includes = listOf(
                RemoteLookupSelection(adventure, QueryOperator.EQUALS),
                RemoteLookupSelection(drama, QueryOperator.CONTAINS),
            ),
            excludes = listOf(
                RemoteLookupSelection(horror, QueryOperator.EQUALS),
                RemoteLookupSelection(mystery, QueryOperator.CONTAINS),
            ),
        )

        val excluded = original.selectValue(adventure, RemoteLookupValueSelection.EXCLUDE, capability)
        excluded.includes shouldContainExactly listOf(RemoteLookupSelection(drama, QueryOperator.CONTAINS))
        excluded.excludes shouldContainExactly listOf(
            RemoteLookupSelection(horror, QueryOperator.EQUALS),
            RemoteLookupSelection(mystery, QueryOperator.CONTAINS),
            RemoteLookupSelection(adventure, QueryOperator.EQUALS),
        )
        excluded.selectValue(adventure, RemoteLookupValueSelection.EXCLUDE, capability) shouldBe excluded

        val movedBackToInclude = excluded.selectValue(adventure, RemoteLookupValueSelection.INCLUDE, capability)
        movedBackToInclude.includes shouldContainExactly listOf(
            RemoteLookupSelection(drama, QueryOperator.CONTAINS),
            RemoteLookupSelection(adventure, QueryOperator.EQUALS),
        )
        movedBackToInclude.excludes shouldContainExactly listOf(
            RemoteLookupSelection(horror, QueryOperator.EQUALS),
            RemoteLookupSelection(mystery, QueryOperator.CONTAINS),
        )

        val neutral = excluded.selectValue(adventure, RemoteLookupValueSelection.NEUTRAL, capability)
        neutral.includes shouldContainExactly listOf(RemoteLookupSelection(drama, QueryOperator.CONTAINS))
        neutral.excludes shouldContainExactly listOf(
            RemoteLookupSelection(horror, QueryOperator.EQUALS),
            RemoteLookupSelection(mystery, QueryOperator.CONTAINS),
        )

        val included = neutral.selectValue(adventure, RemoteLookupValueSelection.INCLUDE, capability)
        included.includes shouldContainExactly listOf(
            RemoteLookupSelection(drama, QueryOperator.CONTAINS),
            RemoteLookupSelection(adventure, QueryOperator.EQUALS),
        )
        included.excludes shouldContainExactly neutral.excludes
    }

    @Test
    fun `UI selection is a no-op for an already selected value and an unavailable exclude`() {
        val adventure = QueryValue.of("Adventure")
        val capability = includeExcludeCapability()
        val selected = RemoteLookupDraft().selectValue(
            adventure,
            RemoteLookupValueSelection.INCLUDE,
            capability,
        )

        selected.selectValue(adventure, RemoteLookupValueSelection.INCLUDE, capability) shouldBe selected

        val conflicted = RemoteLookupDraft(
            includes = listOf(RemoteLookupSelection(adventure, QueryOperator.EQUALS)),
            excludes = listOf(RemoteLookupSelection(adventure, QueryOperator.CONTAINS)),
        )
        conflicted.selectValue(adventure, RemoteLookupValueSelection.INCLUDE, capability) shouldBe conflicted

        val includeOnly = includeExcludeCapability(MultiValueMode.ALL)
        RemoteLookupDraft().selectValue(
            adventure,
            RemoteLookupValueSelection.EXCLUDE,
            includeOnly,
        ) shouldBe RemoteLookupDraft()
    }

    private fun editorWithLookupValues(): ListEditorState {
        val query = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.EQUALS,
                QueryValue.of("Adventure"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.GENRE,
                    QueryOperator.EQUALS,
                    QueryValue.of("Horror"),
                ),
            ),
            QueryExpression.Predicate(
                QueryField.TAG,
                QueryOperator.EQUALS,
                QueryValue.of("Action"),
            ),
        )
        return ListEditorState.fromDraft(
            CollectionListDraft(
                title = "Fantasy",
                query = query,
                sort = CollectionSortSelection.DEFAULT,
                layoutType = "grid",
                providerId = "kitsu",
            ),
        )
    }

    private fun includeExcludeCapability(
        mode: MultiValueMode = MultiValueMode.INCLUDE_EXCLUDE,
        operators: Set<QueryOperator> = setOf(QueryOperator.EQUALS),
    ) =
        CollectionFilterCapability(
            id = "genre",
            field = QueryField.GENRE,
            placement = FilterPlacement.QUICK,
            operators = operators,
            execution = setOf(FilterExecutionMode.RESIDUAL_EXACT),
            valueSource = FilterValueSource.RemoteLookup("genres"),
            multiValueMode = mode,
        )
}
