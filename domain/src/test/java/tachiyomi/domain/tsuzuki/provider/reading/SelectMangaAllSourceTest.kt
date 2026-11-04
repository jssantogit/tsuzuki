package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class SelectMangaAllSourceTest {
    private val mangaAll = ProviderId("app.tsuzuki.mangaall")
    private val title = "title-1"
    private val english = ProviderBindingRef(mangaAll, "en", "mangafire:verified")
    private val portuguese = ProviderBindingRef(mangaAll, "pt-br", "mangadex:verified")
    private val foreign = ProviderBindingRef(mangaAll, "en", "mangadot:foreign")

    @Test
    fun `explicit choice is stored per language without overwriting the preferred reading edition`() = runBlocking {
        val associations = fakeAssociations(
            listOf(
                associated(english),
                associated(portuguese),
                associated(foreign, title = "title-2"),
            ),
        )
        val selections = MemorySelections()
        val picker = SelectMangaAllSource(associations, selections)
        picker.select(title, english, at = 40) shouldBe MangaAllSourceSelectionResult.SELECTED
        picker.select(title, portuguese, at = 41) shouldBe MangaAllSourceSelectionResult.SELECTED
        selections.get(title, "en")?.ref shouldBe english
        selections.get(title, "pt-br")?.ref shouldBe portuguese
        selections.writes shouldBe 2
        picker.select(title, english, at = 42) shouldBe MangaAllSourceSelectionResult.ALREADY_SELECTED
        selections.writes shouldBe 2
        picker.select(title, foreign, at = 43) shouldBe MangaAllSourceSelectionResult.NOT_ASSOCIATED
        val alternate = ProviderBindingRef(mangaAll, "en", "mangafire:other-edition")
        val updatedPicker = SelectMangaAllSource(
            fakeAssociations(listOf(associated(english), associated(portuguese), associated(alternate))),
            selections,
        )
        updatedPicker.select(title, alternate, at = 44) shouldBe MangaAllSourceSelectionResult.SELECTED
        selections.get(title, "en")?.ref shouldBe alternate
        selections.get(title, "pt-br")?.ref shouldBe portuguese
        selections.writes shouldBe 3
    }

    @Test
    fun `forged cross-source and unsupported language choices never persist`() = runBlocking {
        val associations = fakeAssociations(listOf(associated(english)))
        val selections = MemorySelections()
        val picker = SelectMangaAllSource(associations, selections)
        val unknown = english.copy(externalWorkId = "mangaball:123")
        picker.select(title, unknown, 50) shouldBe MangaAllSourceSelectionResult.UNSUPPORTED
        picker.select(title, english.copy(facetId = "fr"), 50) shouldBe MangaAllSourceSelectionResult.UNSUPPORTED
        picker.select(title, english.copy(providerId = ProviderId("app.tsuzuki.mangafire")), 50) shouldBe
            MangaAllSourceSelectionResult.UNSUPPORTED
        picker.select("", english, 50) shouldBe MangaAllSourceSelectionResult.UNSUPPORTED
        picker.select(title, english, -1) shouldBe MangaAllSourceSelectionResult.UNSUPPORTED
        picker.select(title, english.copy(externalWorkId = "mangafire:unobserved"), 50) shouldBe
            MangaAllSourceSelectionResult.NOT_ASSOCIATED
        selections.writes shouldBe 0
    }

    @Test
    fun `author conflict and unverified title cannot be saved as preferred source`() = runBlocking {
        val associations = fakeAssociations(
            listOf(
                associated(english).copy(workVerification = ProviderWorkVerification.AUTHOR_CONFLICT),
                associated(portuguese).copy(workVerification = ProviderWorkVerification.TITLE_UNVERIFIED),
            ),
        )
        val selections = MemorySelections()
        val picker = SelectMangaAllSource(associations, selections)
        picker.select(title, english, 60) shouldBe MangaAllSourceSelectionResult.BLOCKED
        picker.select(title, portuguese, 60) shouldBe MangaAllSourceSelectionResult.BLOCKED
        selections.writes shouldBe 0
    }

    private fun associated(
        ref: ProviderBindingRef,
        title: String = this.title,
    ) = ProviderWorkSourceAssociation(
        canonicalTitleId = title,
        ref = ref,
        workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
        editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
        observedAt = 30,
    )

    private fun fakeAssociations(
        entries: List<ProviderWorkSourceAssociation>,
    ) = object : ProviderWorkSourceAssociationRepository {
        override suspend fun observe(association: ProviderWorkSourceAssociation) = error("read-only")
        override suspend fun get(ref: ProviderBindingRef) = entries.firstOrNull { it.ref == ref }
        override suspend fun getByTitle(canonicalTitleId: String) =
            entries.filter { it.canonicalTitleId == canonicalTitleId }
    }

    private class MemorySelections : MangaAllSourceSelectionRepository {
        private val choices = mutableMapOf<Pair<String, String>, MangaAllSourceSelection>()
        var writes = 0

        override suspend fun get(canonicalTitleId: String, language: String) =
            choices[canonicalTitleId to language]

        override suspend fun select(choice: MangaAllSourceSelection): Boolean {
            writes++
            choices[choice.canonicalTitleId to choice.ref.facetId.orEmpty()] = choice
            return true
        }
    }
}
