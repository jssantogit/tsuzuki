package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GetIndependentInventorySnapshotTest {
    @Test
    fun `no evidence stays unknown without inventing a count or complete inventory`() = runTest {
        val result = GetIndependentInventorySnapshot(FakeLedger()).execute("title")

        result.knowledge shouldBe InventoryKnowledge.UNKNOWN
        result.sources shouldBe emptyList()
        result.editorialCompletenessConfirmed shouldBe false
    }

    @Test
    fun `120 reported with 80 editorial observations remains partial and never becomes complete`() = runTest {
        val ledger = FakeLedger()
        val scope = scope("editorial", "original")
        ledger.saveBatch(
            batch(
                scope,
                "page-1",
                50L,
                InventoryFetchOutcome.EXHAUSTED,
                (1..80).map {
                    claim("$it", "$it", InventoryDerivation.PRIMARY_EDITORIAL, 50L)
                },
                declaredTotal = 120,
            ),
        )

        val result = GetIndependentInventorySnapshot(ledger).execute("title")

        result.knowledge shouldBe InventoryKnowledge.PARTIAL
        result.editorialCompletenessConfirmed shouldBe false
        result.sources.single().knownIndividualClaims shouldBe 80
        result.sources.single().declaredTotals shouldBe setOf(120)
        result.sources.single().outcomes shouldBe setOf(InventoryFetchOutcome.EXHAUSTED)
    }

    @Test
    fun `releases and failed refresh do not create editorial identities`() = runTest {
        val ledger = FakeLedger()
        val scope = scope("mangadex", null, false)
        ledger.saveBatch(
            batch(
                scope,
                "first",
                10L,
                InventoryFetchOutcome.PARTIAL,
                listOf(
                    claim(
                        "release-a",
                        "246-251",
                        InventoryDerivation.RELEASE_RECORD,
                        10L,
                        InventoryClaimKind.RELEASE_RANGE,
                    ),
                    claim("release-b", "12.05", InventoryDerivation.RELEASE_RECORD, 10L),
                ),
                declaredTotal = 1000,
            ),
        )
        ledger.saveBatch(batch(scope, "next", 20L, InventoryFetchOutcome.FAILED, emptyList()))

        val result = GetIndependentInventorySnapshot(ledger).execute("title")

        result.knowledge shouldBe InventoryKnowledge.PARTIAL
        result.sources.single().knownIndividualClaims shouldBe 0
        result.sources.single().releaseObservations shouldBe 2
        result.sources.single().declaredTotals shouldBe setOf(1000)
        result.sources.single().outcomes shouldBe setOf(InventoryFetchOutcome.PARTIAL, InventoryFetchOutcome.FAILED)
        result.sources.single().latestObservedAt shouldBe 20L
    }

    @Test
    fun `two editions are different sources even for same external work and ordinal`() = runTest {
        val ledger = FakeLedger()
        val first = scope("editorial", "original")
        val second = scope("editorial", "colored")
        ledger.saveBatch(
            batch(
                first,
                "one",
                10L,
                InventoryFetchOutcome.EXHAUSTED,
                listOf(claim("unit", "0.01", InventoryDerivation.PRIMARY_EDITORIAL, 10L)),
            ),
        )
        ledger.saveBatch(
            batch(
                second,
                "one",
                11L,
                InventoryFetchOutcome.EXHAUSTED,
                listOf(claim("unit", "0.1", InventoryDerivation.PRIMARY_EDITORIAL, 11L)),
            ),
        )

        val result = GetIndependentInventorySnapshot(ledger).execute("title")

        result.sources.size shouldBe 2
        result.sources.map { it.scope.editionKey }.toSet() shouldBe setOf("original", "colored")
        result.sources.map { it.knownIndividualClaims } shouldBe listOf(1, 1)
    }

    private fun scope(producer: String, edition: String?, editionVerified: Boolean = edition != null) = InventoryScope(
        canonicalTitleId = "title",
        producerId = producer,
        externalWorkId = "known-work",
        editionKey = edition,
        language = null,
        workIdentityVerified = true,
        editionIdentityVerified = editionVerified,
    )

    private fun claim(
        id: String,
        label: String,
        derivation: InventoryDerivation,
        observedAt: Long,
        kind: InventoryClaimKind = InventoryClaimKind.INDIVIDUAL,
    ) = InventoryClaim(
        sourceRecordId = id,
        label = label,
        volumeLabel = null,
        language = null,
        kind = kind,
        derivation = derivation,
        upstreamSourceId = "primary",
        observedAt = observedAt,
    )

    private fun batch(
        scope: InventoryScope,
        key: String,
        at: Long,
        outcome: InventoryFetchOutcome,
        claims: List<InventoryClaim>,
        declaredTotal: Int? = null,
    ) = InventoryEvidenceBatch(scope, key, at, outcome, claims, declaredTotal)

    private class FakeLedger : InventoryEvidenceRepository {
        private val pages = mutableListOf<InventoryEvidenceBatch>()

        override suspend fun saveBatch(batch: InventoryEvidenceBatch) {
            pages += batch
        }

        override suspend fun byTitle(canonicalTitleId: String): List<InventoryStoredClaim> =
            pages.filter { it.scope.canonicalTitleId == canonicalTitleId }.flatMap { batch ->
                batch.claims.map { InventoryStoredClaim(batch.scope, it) }
            }

        override suspend fun batchesByTitle(canonicalTitleId: String): List<InventoryStoredBatch> =
            pages.filter { it.scope.canonicalTitleId == canonicalTitleId }.map { batch ->
                InventoryStoredBatch(
                    scope = batch.scope,
                    pageKey = batch.pageKey,
                    observedAt = batch.observedAt,
                    outcome = batch.outcome,
                    itemCount = batch.claims.size,
                    declaredTotal = batch.declaredTotal,
                    nextCursor = batch.nextCursor,
                )
            }
    }
}
