package tachiyomi.domain.tsuzuki.integration

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

class CreatorAliasProviderTest {
    private val owner = IntegrationId("mangaupdates")
    private val metadata = ResolvedMetadata(
        authors = ProvenancedMetadata(listOf("Son Jeho"), owner, "42"),
        externalIds = mapOf(owner to "42"),
    )
    private val author = CatalogCreatorAliasEvidence(
        workExternalId = "42",
        creatorExternalId = "91",
        role = CreatorCreditRole.AUTHOR,
        creditedName = "Son Jeho",
        primaryName = "Son Jae-Ho",
        aliases = listOf("SON Jeho", "SON Je-Ho"),
    )

    @Test
    fun `published author aliases require work owner and role credit provenance`() = runTest {
        val provider = source(author)
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(provider)) shouldBe
            setOf("Son Jae-Ho", "SON Jeho", "SON Je-Ho")
        resolveVerifiedCreatorAliases(metadata, emptyMap(), listOf(provider)) shouldBe emptySet()
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "43"), listOf(provider)) shouldBe emptySet()
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(provider, provider)) shouldBe emptySet()
    }

    @Test
    fun `creator alias evidence cannot bridge a different work role person or credit name`() = runTest {
        val cases = listOf(
            author.copy(workExternalId = "43"),
            author.copy(creatorExternalId = ""),
            author.copy(role = CreatorCreditRole.ARTIST),
            author.copy(creditedName = "Different Creator"),
            author.copy(aliases = listOf("Unrelated Person")),
            author.copy(aliases = List(33) { "Alias $it" }),
        )
        for (entry in cases) {
            resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(source(entry))) shouldBe emptySet()
        }
    }

    @Test
    fun `competing people cannot donate aliases through a shared credit spelling`() = runTest {
        val rival = author.copy(creatorExternalId = "92", primaryName = "Other Person")
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(source(author, rival))) shouldBe
            emptySet()
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(source(rival, author))) shouldBe
            emptySet()
    }

    @Test
    fun `one person retains its roles but cannot bridge disjoint credit identities`() {
        val credits = listOf(
            CreatorCreditRole.AUTHOR to "Son Jeho",
            CreatorCreditRole.ARTIST to "Artist Name",
        )
        val artist = author.copy(
            creatorExternalId = "92",
            role = CreatorCreditRole.ARTIST,
            creditedName = "Artist Name",
            primaryName = "Artist Name",
            aliases = listOf("Artist Alias"),
        )
        val separate = verifiedCatalogCreatorAliasGroups("42", credits, listOf(author, artist))
        separate.map { it.creatorExternalId }.toSet() shouldBe setOf("91", "92")
        verifiedCatalogCreatorAliasGroups(
            "42",
            credits,
            listOf(author, artist.copy(creatorExternalId = "91")),
        ) shouldBe emptyList()
        val samePerson = artist.copy(
            creatorExternalId = "91",
            aliases = listOf("Son Jeho"),
        )
        val grouped = verifiedCatalogCreatorAliasGroups("42", credits, listOf(author, samePerson)).single()
        grouped.creatorExternalId shouldBe "91"
        grouped.credits.size shouldBe 2
    }

    @Test
    fun `a shared alias cannot connect two separately anchored people`() {
        val artist = author.copy(
            creatorExternalId = "92",
            role = CreatorCreditRole.ARTIST,
            creditedName = "Artist Name",
            primaryName = "Artist Name",
            aliases = listOf("SON Je-Ho"),
        )
        val credits = listOf(
            CreatorCreditRole.AUTHOR to "Son Jeho",
            CreatorCreditRole.ARTIST to "Artist Name",
        )
        verifiedCatalogCreatorAliasGroups("42", credits, listOf(author, artist)) shouldBe emptyList()
    }

    @Test
    fun `catalog failure supplies no fabricated aliases`() = runTest {
        val provider = object : CreatorAliasProvider {
            override val integrationId = owner
            override suspend fun getDetails(externalId: String): Result<CatalogItem> = error("Unused")
            override suspend fun creatorAliases(externalId: String): Result<List<CatalogCreatorAliasEvidence>> =
                Result.failure(IllegalStateException("Unavailable"))
        }
        resolveVerifiedCreatorAliases(metadata, mapOf(owner to "42"), listOf(provider)) shouldBe emptySet()
    }

    @Test
    fun `reviewed bibliographic alias requires exact independent catalog identity and role`() {
        val reference = ReviewedCreatorAliasReference(
            catalogOwner = owner,
            catalogWorkId = "42",
            role = CreatorCreditRole.AUTHOR,
            creditedName = "Son Jeho",
            creditUrl = "https://publisher.example/work/42",
            aliases = mapOf("Published Alias" to "https://library.example/book/123"),
        )
        val verified = mapOf(owner to "42")
        resolveReviewedCreatorAliases(metadata, verified, listOf(reference)) shouldBe setOf("Published Alias")
        resolveReviewedCreatorAliases(metadata, emptyMap(), listOf(reference)) shouldBe emptySet()
        for (invalid in listOf(
            reference.copy(catalogWorkId = "43"),
            reference.copy(role = CreatorCreditRole.ARTIST),
            reference.copy(creditedName = "Another Person"),
            reference.copy(aliases = mapOf("Fake" to "http://library.example/book/123")),
            reference.copy(aliases = mapOf("Fake" to "https://publisher.example/other")),
        )) {
            resolveReviewedCreatorAliases(metadata, verified, listOf(invalid)) shouldBe emptySet()
        }
    }

    private fun source(vararg entries: CatalogCreatorAliasEvidence) = object : CreatorAliasProvider {
        override val integrationId = owner
        override suspend fun getDetails(externalId: String): Result<CatalogItem> = error("Unused")
        override suspend fun creatorAliases(externalId: String): Result<List<CatalogCreatorAliasEvidence>> {
            externalId shouldBe "42"
            return Result.success(entries.toList())
        }
    }
}
