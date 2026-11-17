package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId

/**
 * These rows are only CONTRACT fixtures. LIVE evidence must come from the
 * independent catalog's real search + getDetails in ResolveMangaAllIndependentCatalogIdentity.
 */
class MangaAllCatalogCanonicalMetadataTest {
    @Test
    fun `independently verified detail identity creates provenance needed by the real collector`() {
        val item = CatalogItem(
            provider = "mangaupdates",
            providerId = "41782",
            title = "Catalog Work",
            authors = listOf("Published Author"),
            artists = listOf("Published Artist"),
            externalIds = mapOf("mal" to "1337"),
        )
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = item,
            candidateCount = 1,
            catalogsQueried = 1,
            creatorsAttested = true,
        )
        val projected = result.toCanonicalReadingMetadata()
        val provider = IntegrationId("mangaupdates")
        projected?.title?.value shouldBe "Catalog Work"
        projected?.authors?.value shouldBe listOf("Published Author")
        projected?.artists?.value shouldBe listOf("Published Artist")
        projected?.authors?.providerId shouldBe provider
        projected?.authors?.externalId shouldBe "41782"
        projected?.artists?.externalId shouldBe "41782"
        projected?.externalIds shouldBe mapOf(
            provider to "41782",
            IntegrationId("mal") to "1337",
        )
    }

    @Test
    fun `catalog ambiguous incomplete and unavailable are never converted into creator proof`() {
        val states = MangaAllCatalogIdentityStatus.entries.filterNot { it == MangaAllCatalogIdentityStatus.RESOLVED }
        states.forEach { status ->
            MangaAllCatalogIdentityResult(status).toCanonicalReadingMetadata() shouldBe null
        }
    }

    @Test
    fun `identity with no attested creators provides no fake author or artist`() {
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = CatalogItem("mal", "42", "Work"),
            creatorsAttested = false,
        )
        val projected = result.toCanonicalReadingMetadata()
        projected?.authors shouldBe null
        projected?.artists shouldBe null
        projected?.externalIds shouldBe mapOf(IntegrationId("mal") to "42")
    }

    @Test
    fun `reading provider can never certify its own canonical authority`() {
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = CatalogItem(
                provider = "app.tsuzuki.mangaall",
                providerId = "mangafire:5",
                title = "Work",
                authors = listOf("Unverified"),
            ),
            creatorsAttested = true,
        )
        result.toCanonicalReadingMetadata() shouldBe null
    }

    @Test
    fun `catalog detail cannot contradict its own external identity`() {
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = CatalogItem(
                provider = "mal",
                providerId = "42",
                title = "Work",
                externalIds = mapOf("mal" to "99"),
            ),
        )
        result.toCanonicalReadingMetadata() shouldBe null
    }

    @Test
    fun `creators attestation discrepancy is not silently promoted to evidence`() {
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = CatalogItem("mal", "42", "Work", authors = listOf("Author")),
            creatorsAttested = false,
        )
        result.toCanonicalReadingMetadata() shouldBe null
    }

    @Test
    fun `external IDs from reading products cannot be added as canonical catalog identities`() {
        val result = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = CatalogItem(
                provider = "mal",
                providerId = "42",
                title = "Work",
                externalIds = mapOf("app.tsuzuki.mangaall" to "mangafire:5"),
            ),
        )
        result.toCanonicalReadingMetadata() shouldBe null
    }
}
