package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventoryProducer
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimPolicy
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.RefreshIndependentInventory
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MangaDexWorkLinkInventoryIntegrationTest {
    private val mangaDexId = "12345678-1234-1234-1234-123456789abc"

    @Test
    fun `manual verified work link permits release inventory without reading providers or chapter promotion`() =
        runTest {
        val identities = mutableListOf(ExternalIdentity("title", "mangaupdates", "8675309", true, 1L))
        val titles = mockk<CanonicalTitleRepository>()
        coEvery { titles.getExternalIdentities("title") } coAnswers { identities.toList() }
        coEvery { titles.getByExternalIdentity("mangadex", mangaDexId) } returns null
        coEvery { titles.addExternalIdentity(any()) } coAnswers {
            identities.add(firstArg())
            Unit
        }
        val transport = object : InventoryReleaseTransport {
            val json = """
                {"result":"ok","data":{"id":"$mangaDexId","type":"manga","attributes":
                {"title":{"en":"Verified work"},"links":{"mu":"8675309"}}}}
            """.trimIndent()
            override suspend fun fetch(url: String, postBody: String?) = InventoryHttpPayload(json, 10L)
            override suspend fun fetchFresh(url: String, postBody: String?) = InventoryHttpPayload(json, 11L)
        }
        val linker = MangaDexVerifiedWorkLinker(titles, transport)
        val preview = linker.preview("title", mangaDexId).getOrThrow()

        identities.map { it.provider } shouldBe listOf("mangaupdates")
        linker.confirm("title", preview).isSuccess shouldBe true
        identities.map { it.provider } shouldBe listOf("mangaupdates", "mangadex")

        val saved = mutableListOf<InventoryEvidenceBatch>()
        val ledger = mockk<InventoryEvidenceRepository>()
        coEvery { ledger.batchesByTitle("title") } returns emptyList()
        coEvery { ledger.saveBatch(any()) } coAnswers {
            saved.add(firstArg())
            Unit
        }
        val producer = object : IndependentInventoryProducer {
            override val producerId = "mangadex"
            override suspend fun fetchPage(scope: InventoryScope, pageKey: String): Result<InventoryEvidenceBatch> {
                val release = InventoryClaim(
                    sourceRecordId = "release-a",
                    label = "10-12",
                    volumeLabel = null,
                    language = "en",
                    kind = InventoryClaimKind.RELEASE_RANGE,
                    derivation = InventoryDerivation.RELEASE_RECORD,
                    upstreamSourceId = null,
                    observedAt = 20L,
                )
                return Result.success(
                    InventoryEvidenceBatch(scope, pageKey, 20L, InventoryFetchOutcome.EXHAUSTED, listOf(release)),
                )
            }
        }

        val result = RefreshIndependentInventory(titles, ledger).execute(
            canonicalTitleId = "title",
            producer = producer,
            isEnabled = { true },
        )

        result.stop shouldBe InventoryRefreshStop.EXHAUSTED
        result.pagesSaved shouldBe 1
        saved.single().scope.externalWorkId shouldBe mangaDexId
        saved.single().scope.workIdentityVerified shouldBe true
        saved.single().scope.editionIdentityVerified shouldBe false
        saved.single().claims.single().derivation shouldBe InventoryDerivation.RELEASE_RECORD
        InventoryClaimPolicy.mayPromote(saved.single().scope, saved.single().claims.single()) shouldBe false
    }
}
