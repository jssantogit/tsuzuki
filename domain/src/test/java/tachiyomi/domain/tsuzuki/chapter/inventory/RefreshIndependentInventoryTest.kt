package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class RefreshIndependentInventoryTest {
    @Test
    fun `independent producer can persist partial releases without any reading provider`() = runTest {
        val titles = titleRepository()
        val ledger = FakeLedger()
        val calls = mutableListOf<String>()
        val producer = producer { scope, page ->
            calls += page
            when (page) {
                "start" -> Result.success(
                    batch(scope, page, "r1", "246-251", InventoryFetchOutcome.PARTIAL, "next"),
                )
                "next" -> Result.success(
                    batch(scope, page, "r2", "12.05", InventoryFetchOutcome.EXHAUSTED),
                )
                else -> error("Unexpected cursor")
            }
        }

        val result = RefreshIndependentInventory(titles, ledger).execute(
            "canonical",
            producer,
            isEnabled = { true },
        )
        result shouldBe InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 2, 2)
        calls shouldBe listOf("start", "next")
        ledger.claims.map { it.label } shouldBe listOf("246-251", "12.05")
        ledger.saved.all { it.scope.editionIdentityVerified == false } shouldBe true
    }

    @Test
    fun `manual subsequent refresh resumes after its last persisted partial cursor`() = runTest {
        val ledger = FakeLedger()
        val calls = mutableListOf<String>()
        val producer = producer { scope, page ->
            calls += page
            when (page) {
                "start" -> Result.success(
                    batch(scope, page, "first", "1", InventoryFetchOutcome.PARTIAL, "page:2"),
                )
                "page:2" -> Result.success(
                    batch(scope, page, "second", "2", InventoryFetchOutcome.EXHAUSTED),
                )
                else -> error("Unexpected cursor")
            }
        }
        val refresh = RefreshIndependentInventory(titleRepository(), ledger)
        refresh.execute("canonical", producer, { true }, maxPages = 1).stop shouldBe InventoryRefreshStop.PARTIAL
        refresh.execute("canonical", producer, { true }, maxPages = 1).stop shouldBe InventoryRefreshStop.EXHAUSTED

        calls shouldBe listOf("start", "page:2")
        ledger.claims.map { it.sourceRecordId } shouldBe listOf("first", "second")
    }

    @Test
    fun `a stored cursor from another external work is never reused`() = runTest {
        val ledger = FakeLedger()
        val unrelated = InventoryScope("canonical", "mangadex", "different-work", null, null, true, false)
        ledger.saveBatch(
            batch(unrelated, "start", "old", "1", InventoryFetchOutcome.PARTIAL, "page:2"),
        )
        val calls = mutableListOf<String>()
        RefreshIndependentInventory(titleRepository(), ledger).execute(
            "canonical",
            producer { scope, page ->
                calls += page
                Result.success(batch(scope, page, "correct", "1", InventoryFetchOutcome.EXHAUSTED))
            },
            { true },
            maxPages = 1,
        ).stop shouldBe InventoryRefreshStop.EXHAUSTED

        calls shouldBe listOf("start")
        ledger.claims.map { it.sourceRecordId } shouldBe listOf("old", "correct")
    }

    @Test
    fun `failed continuation is retried on the next manual action without deleting earlier claims`() = runTest {
        val ledger = FakeLedger()
        val calls = mutableListOf<String>()
        var failedOnce = false
        val producer = producer { scope, page ->
            calls += page
            when (page) {
                "start" -> Result.success(
                    batch(scope, page, "first", "1", InventoryFetchOutcome.PARTIAL, "page:2"),
                )
                "page:2" -> {
                    if (!failedOnce) {
                        failedOnce = true
                        Result.failure(IllegalStateException("temporary outage"))
                    } else {
                        Result.success(batch(scope, page, "second", "2", InventoryFetchOutcome.EXHAUSTED))
                    }
                }
                else -> error("Unexpected cursor")
            }
        }
        val refresh = RefreshIndependentInventory(titleRepository(), ledger)
        refresh.execute("canonical", producer, { true }, maxPages = 2).stop shouldBe InventoryRefreshStop.FAILED
        refresh.execute("canonical", producer, { true }, maxPages = 1).stop shouldBe InventoryRefreshStop.EXHAUSTED

        calls shouldBe listOf("start", "page:2", "page:2")
        ledger.claims.map { it.sourceRecordId } shouldBe listOf("first", "second")
    }

    @Test
    fun `unverified crosswalk cannot cause source network access`() = runTest {
        val titles = titleRepository(verified = false)
        val ledger = FakeLedger()
        var requested = 0
        val result = RefreshIndependentInventory(titles, ledger).execute(
            "canonical",
            producer { _, _ ->
                requested++
                error("Must not call unverified provider")
            },
            isEnabled = { true },
        )
        result.stop shouldBe InventoryRefreshStop.NO_VERIFIED_WORK
        requested shouldBe 0
        ledger.saved.size shouldBe 0
    }

    @Test
    fun `deactivated source during in-flight page is discarded without overwriting offline claims`() = runTest {
        val ledger = FakeLedger()
        var enabled = true
        val producer = producer { scope, page ->
            enabled = false
            Result.success(batch(scope, page, "late", "1", InventoryFetchOutcome.EXHAUSTED))
        }
        val result = RefreshIndependentInventory(titleRepository(), ledger)
            .execute("canonical", producer, { enabled })
        result.stop shouldBe InventoryRefreshStop.DISABLED
        ledger.saved.size shouldBe 0
    }

    @Test
    fun `remapped work while request is running never leaks cross-work releases`() = runTest {
        val ledger = FakeLedger()
        var externalId = "123"
        val titles = titleRepository(external = { externalId })
        val producer = producer { scope, page ->
            externalId = "456"
            Result.success(batch(scope, page, "late", "1", InventoryFetchOutcome.EXHAUSTED))
        }
        RefreshIndependentInventory(titles, ledger).execute(
            "canonical",
            producer,
            { true },
        ).stop shouldBe InventoryRefreshStop.NO_VERIFIED_WORK
        ledger.saved.size shouldBe 0
    }

    @Test
    fun `producer wrong-work response fails closed before any storage`() = runTest {
        val ledger = FakeLedger()
        shouldThrow<IllegalArgumentException> {
            RefreshIndependentInventory(titleRepository(), ledger).execute(
                "canonical",
                producer { scope, page ->
                    Result.success(
                        batch(
                            scope.copy(externalWorkId = "other"),
                            page,
                            "wrong",
                            "1",
                            InventoryFetchOutcome.EXHAUSTED,
                        ),
                    )
                },
                { true },
            )
        }
        ledger.saved.size shouldBe 0
    }

    @Test
    fun `bounded page budget and replayed cursor cannot start an unbounded sweep`() = runTest {
        val ledger = FakeLedger()
        var calls = 0
        val result = RefreshIndependentInventory(titleRepository(), ledger).execute(
            "canonical",
            producer { scope, page ->
                calls++
                Result.success(batch(scope, page, "r$calls", "$calls", InventoryFetchOutcome.PARTIAL, "page:$calls"))
            },
            { true },
            maxPages = 2,
        )
        result.stop shouldBe InventoryRefreshStop.PARTIAL
        calls shouldBe 2
        shouldThrow<IllegalStateException> {
            RefreshIndependentInventory(titleRepository(), FakeLedger()).execute(
                "canonical",
                producer { scope, page ->
                    Result.success(batch(scope, page, "1", "1", InventoryFetchOutcome.PARTIAL, page))
                },
                { true },
            )
        }
    }

    @Test
    fun `failed second page records technical failure but never deletes first page or cancels coroutine`() = runTest {
        val ledger = FakeLedger()
        val result = RefreshIndependentInventory(titleRepository(), ledger).execute(
            "canonical",
            producer { scope, page ->
                when (page) {
                    "start" -> Result.success(batch(scope, page, "1", "1", InventoryFetchOutcome.PARTIAL, "next"))
                    else -> Result.failure(IllegalStateException("429 / retry later"))
                }
            },
            { true },
        )
        result.stop shouldBe InventoryRefreshStop.FAILED
        ledger.saved.map { it.pageKey } shouldBe listOf("start", "next")
        ledger.claims.map { it.sourceRecordId } shouldBe listOf("1")
        ledger.saved.last().outcome shouldBe InventoryFetchOutcome.FAILED
        currentCoroutineContext()[Job]?.isActive shouldBe true

        shouldThrow<CancellationException> {
            RefreshIndependentInventory(titleRepository(), FakeLedger()).execute(
                "canonical",
                producer { _, _ -> throw CancellationException("cancel") },
                { true },
            )
        }
    }

    @Test
    fun `producer cancellation result propagates without saving a failed page`() = runTest {
        val ledger = FakeLedger()

        shouldThrow<CancellationException> {
            RefreshIndependentInventory(titleRepository(), ledger).execute(
                "canonical",
                producer { _, _ -> Result.failure(CancellationException("cancelled")) },
                { true },
            )
        }

        ledger.saved.size shouldBe 0
    }

    @Test
    fun `cancellation during noncooperative successful fetch prevents saving its batch`() = runTest {
        val ledger = FakeLedger()
        val child = launch {
            RefreshIndependentInventory(titleRepository(), ledger).execute(
                "canonical",
                producer { scope, page ->
                    currentCoroutineContext()[Job]!!.cancel(CancellationException("cancelled"))
                    Result.success(batch(scope, page, "late", "1", InventoryFetchOutcome.EXHAUSTED))
                },
                { true },
            )
        }

        child.join()

        child.isCancelled shouldBe true
        ledger.saved.size shouldBe 0
    }

    private fun titleRepository(
        verified: Boolean = true,
        external: () -> String = { "123" },
    ): CanonicalTitleRepository = mockk {
        coEvery { getExternalIdentities("canonical") } answers {
            listOf(
                ExternalIdentity("canonical", "mangadex", external(), verified, 1L),
            )
        }
    }

    private fun producer(
        block: suspend (InventoryScope, String) -> Result<InventoryEvidenceBatch>,
    ): IndependentInventoryProducer = object : IndependentInventoryProducer {
        override val producerId = "mangadex"
        override suspend fun fetchPage(scope: InventoryScope, pageKey: String) = block(scope, pageKey)
    }

    private fun batch(
        scope: InventoryScope,
        page: String,
        id: String,
        label: String,
        outcome: InventoryFetchOutcome,
        next: String? = null,
    ) = InventoryEvidenceBatch(
        scope = scope,
        pageKey = page,
        observedAt = 10L,
        outcome = outcome,
        claims = listOf(
            InventoryClaim(
                sourceRecordId = id,
                label = label,
                volumeLabel = null,
                language = null,
                kind = InventoryClaimPolicy.classifyReleaseLabel(label),
                derivation = InventoryDerivation.RELEASE_RECORD,
                upstreamSourceId = "mangadex",
                observedAt = 10L,
            ),
        ),
        nextCursor = next,
    )

    private class FakeLedger : InventoryEvidenceRepository {
        val saved = mutableListOf<InventoryEvidenceBatch>()
        val claims: List<InventoryClaim>
            get() = saved.flatMap { it.claims }

        override suspend fun byTitle(canonicalTitleId: String): List<InventoryStoredClaim> =
            saved.filter { it.scope.canonicalTitleId == canonicalTitleId }.flatMap { batch ->
                batch.claims.map { InventoryStoredClaim(batch.scope, it) }
            }

        override suspend fun batchesByTitle(canonicalTitleId: String): List<InventoryStoredBatch> =
            saved.filter { it.scope.canonicalTitleId == canonicalTitleId }.map { batch ->
                InventoryStoredBatch(
                    batch.scope,
                    batch.pageKey,
                    batch.observedAt,
                    batch.outcome,
                    batch.claims.size,
                    batch.declaredTotal,
                    batch.nextCursor,
                )
            }

        override suspend fun saveBatch(batch: InventoryEvidenceBatch) {
            saved += batch
        }
    }
}
