package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ListMangaAllSourcesTest {
    private val providerId = ProviderId("app.tsuzuki.mangaall")
    private val canonical = "title-1"
    private fun association(
        source: String,
        facet: String,
        id: String,
        verification: ProviderWorkVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
        edition: ProviderSourceEditionQualification = ProviderSourceEditionQualification.UNRESOLVED,
    ) = ProviderWorkSourceAssociation(
        canonicalTitleId = canonical,
        ref = ProviderBindingRef(providerId, facet, "$source:$id"),
        workVerification = verification,
        editionQualification = edition,
        observedAt = 30,
    )

    @Test
    fun `alternatives preserve source and requested facet without turning evidence into Reader authority`() = runBlocking {
        val associations = listOf(
            association("mangafire", "en", "fire-1"),
            association("mangafire", "en", "fire-2"),
            association("mangadex", "pt-br", "dex-1", ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE),
            association("mangadot", "en", "dot-1", ProviderWorkVerification.AUTHOR_CONFLICT),
            association("mangafire", "en", "colored", edition = ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT),
            association("mangafire", "fr", "wrong-language"),
            association("mangaball", "en", "unavailable"),
            ProviderWorkSourceAssociation(
                canonicalTitleId = canonical,
                ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangafire"), "en", "legacy"),
                workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
                editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
                observedAt = 31,
            ),
        )
        val repository = object : ProviderWorkSourceAssociationRepository {
            override suspend fun observe(association: ProviderWorkSourceAssociation) = error("read only")
            override suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation? = null
            override suspend fun getByTitle(canonicalTitleId: String) = associations.also { canonicalTitleId shouldBe canonical }
        }
        val result = ListMangaAllSources(repository).execute(canonical)
        result.map { it.ref.externalWorkId } shouldBe listOf(
            "mangafire:fire-1", "mangafire:fire-2", "mangadex:dex-1",
            "mangadot:dot-1", "mangafire:colored",
        )
        result.map { it.sourceId } shouldBe listOf(
            "mangafire", "mangafire", "mangadex", "mangadot", "mangafire",
        )
        result.map { it.requestedLanguage } shouldBe listOf("en", "en", "pt-br", "en", "en")
        result.map { it.workVerification } shouldBe listOf(
            ProviderWorkVerification.AUTHOR_CORROBORATED,
            ProviderWorkVerification.AUTHOR_CORROBORATED,
            ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE,
            ProviderWorkVerification.AUTHOR_CONFLICT,
            ProviderWorkVerification.AUTHOR_CORROBORATED,
        )
        result.all { !it.isReaderVerified } shouldBe true
    }

    @Test
    fun `no alternatives and empty title are nonmutating`() = runBlocking {
        val repository = object : ProviderWorkSourceAssociationRepository {
            override suspend fun observe(association: ProviderWorkSourceAssociation) = error("read only")
            override suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation? = null
            override suspend fun getByTitle(canonicalTitleId: String) = emptyList<ProviderWorkSourceAssociation>()
        }
        ListMangaAllSources(repository).execute(canonical) shouldBe emptyList()
        ListMangaAllSources(repository).execute("") shouldBe emptyList()
    }
}
