package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InventoryClaimPolicyTest {
    @Test
    fun `120 declared and 80 editorial identities remain 80 admissible with unknown completeness`() {
        val scope = verifiedScope()
        val records = (1..80).map { number ->
            InventoryStoredClaim(
                scope,
                claim(number.toString(), InventoryClaimKind.INDIVIDUAL, InventoryDerivation.PRIMARY_EDITORIAL),
            )
        }
        val summary = summarizeInventory(records)
        summary.individuallyAdmissibleClaims shouldBe 80
        summary.editorialCompletenessConfirmed shouldBe false
    }

    @Test
    fun `mangaupdates range and release revision cannot fabricate individual chapters`() {
        val scope = verifiedScope("mangaupdates")
        val batch = MangaUpdatesReleaseEvidenceAdapter.adapt(
            scope,
            pageKey = "p1",
            observedAt = 100L,
            releases = listOf(
                InventoryReleaseRecord("range", "246-251", "12", null, "mu", "work-id"),
                InventoryReleaseRecord("first", "1", null, null, "mu", "work-id"),
                InventoryReleaseRecord("reupload", "1v2", null, null, "mu", "work-id"),
            ),
            hasNextPage = false,
        )
        batch.outcome shouldBe InventoryFetchOutcome.EXHAUSTED
        batch.claims.map { it.kind }.shouldContainExactly(
            InventoryClaimKind.RELEASE_RANGE,
            InventoryClaimKind.INDIVIDUAL,
            InventoryClaimKind.AMBIGUOUS,
        )
        batch.claims.none { InventoryClaimPolicy.mayPromote(scope, it) } shouldBe true
    }

    @Test
    fun `mangadex exact decimal remains text and does not become editorial fact`() {
        val scope = verifiedScope("mangadex")
        val batch = MangaDexReleaseEvidenceAdapter.adapt(
            scope,
            "offset:0",
            200L,
            listOf(
                InventoryReleaseRecord("one", "0.01", "1", "pt-br", "md", "work-id"),
                InventoryReleaseRecord("two", "0.1", "1", "en", "md", "work-id"),
                InventoryReleaseRecord("three", null, null, "en", "md", "work-id"),
            ),
            hasNextPage = true,
        )
        batch.outcome shouldBe InventoryFetchOutcome.PARTIAL
        batch.claims.map { it.label }.shouldContainExactly("0.01", "0.1", null)
        batch.claims.map { it.kind }.shouldContainExactly(
            InventoryClaimKind.INDIVIDUAL,
            InventoryClaimKind.INDIVIDUAL,
            InventoryClaimKind.AMBIGUOUS,
        )
        batch.claims.none { InventoryClaimPolicy.mayPromote(scope, it) } shouldBe true
    }

    @Test
    fun `verified work is insufficient without verified edition and direct editorial provenance`() {
        val uncertain = verifiedScope().copy(editionKey = null, editionIdentityVerified = false)
        val editorial = claim("12.05", InventoryClaimKind.INDIVIDUAL, InventoryDerivation.PRIMARY_EDITORIAL)
        InventoryClaimPolicy.mayPromote(uncertain, editorial) shouldBe false
        InventoryClaimPolicy.mayPromote(verifiedScope(), editorial) shouldBe true
        InventoryClaimPolicy.mayPromote(
            verifiedScope(),
            editorial.copy(derivation = InventoryDerivation.COUNT_DERIVED),
        ) shouldBe false
        InventoryClaimPolicy.mayPromote(
            verifiedScope(),
            editorial.copy(upstreamSourceId = null),
        ) shouldBe false
    }

    @Test
    fun `unknown language is not a wildcard for a different observed language`() {
        val editorial = claim("12", InventoryClaimKind.INDIVIDUAL, InventoryDerivation.PRIMARY_EDITORIAL)
            .copy(language = "en")
        InventoryClaimPolicy.mayPromote(verifiedScope(), editorial) shouldBe false
        InventoryClaimPolicy.mayPromote(verifiedScope().copy(language = "en"), editorial) shouldBe true
        InventoryClaimPolicy.mayPromote(verifiedScope().copy(language = "pt-br"), editorial) shouldBe false
    }

    @Test
    fun `kitsu placeholder and declared total can never act as canonical proof`() {
        val scope = verifiedScope("kitsu")
        val placeholder = claim("5000", InventoryClaimKind.DERIVED_PLACEHOLDER, InventoryDerivation.COUNT_DERIVED)
        val declared = claim("120", InventoryClaimKind.DECLARED_TOTAL, InventoryDerivation.AGGREGATED)
        summarizeInventory(listOf(InventoryStoredClaim(scope, placeholder), InventoryStoredClaim(scope, declared)))
            .individuallyAdmissibleClaims shouldBe 0
    }

    @Test
    fun `cross-edition and language mismatches fail closed and duplicate page releases are de-duplicated`() {
        val scope = verifiedScope("mangadex").copy(language = "pt-br")
        val wrongLanguage = claim("12", InventoryClaimKind.INDIVIDUAL, InventoryDerivation.PRIMARY_EDITORIAL)
            .copy(language = "en")
        InventoryClaimPolicy.mayPromote(scope, wrongLanguage) shouldBe false
        val duplicates = listOf(
            InventoryReleaseRecord("same", "12", null, "pt-br", "md", "work-id"),
            InventoryReleaseRecord("same", "12", null, "pt-br", "md", "work-id"),
        )
        MangaDexReleaseEvidenceAdapter.adapt(scope, "p", 1L, duplicates, true).claims.size shouldBe 1
        shouldThrow<IllegalArgumentException> {
            MangaDexReleaseEvidenceAdapter.adapt(
                scope,
                "p",
                1L,
                duplicates + InventoryReleaseRecord("same", "13", null, "pt-br", "md", "work-id"),
                true,
            )
        }
    }

    @Test
    fun `technical exhaustion does not certify editorial completeness and failed page adds nothing`() {
        val scope = verifiedScope("mangadex")
        val failed = InventoryEvidenceBatch(scope, "p2", 300L, InventoryFetchOutcome.FAILED, emptyList())
        failed.outcome shouldBe InventoryFetchOutcome.FAILED
        shouldThrow<IllegalArgumentException> {
            failed.copy(claims = listOf(claim("2", InventoryClaimKind.INDIVIDUAL, InventoryDerivation.RELEASE_RECORD)))
        }
        summarizeInventory(emptyList()).editorialCompletenessConfirmed shouldBe false
    }

    @Test
    fun `wrong external work id on a successful page is rejected before persistence`() {
        val scope = verifiedScope("mangadex")
        shouldThrow<IllegalArgumentException> {
            MangaDexReleaseEvidenceAdapter.adapt(
                scope,
                "p1",
                10L,
                listOf(InventoryReleaseRecord("bad", "1", null, "en", "md", "other-work")),
                true,
            )
        }
    }

    @Test
    fun `release adapters carry bounded next cursors without claiming editorial completeness`() {
        val scope = verifiedScope("mangadex")
        val first = MangaDexReleaseEvidenceAdapter.adapt(
            scope = scope,
            pageKey = "start",
            observedAt = 30L,
            releases = listOf(InventoryReleaseRecord("release", "12", null, "en", "md", "work-id")),
            hasNextPage = true,
            nextCursor = "offset:100",
        )
        first.outcome shouldBe InventoryFetchOutcome.PARTIAL
        first.nextCursor shouldBe "offset:100"

        val last = MangaDexReleaseEvidenceAdapter.adapt(
            scope = scope,
            pageKey = "offset:100",
            observedAt = 31L,
            releases = emptyList(),
            hasNextPage = false,
            nextCursor = "invalid-stale-next",
        )
        last.outcome shouldBe InventoryFetchOutcome.EXHAUSTED
        last.nextCursor shouldBe null
    }

    private fun verifiedScope(producer: String = "editorial") = InventoryScope(
        canonicalTitleId = "canonical",
        producerId = producer,
        externalWorkId = "work-id",
        editionKey = "edition-original",
        language = null,
        workIdentityVerified = true,
        editionIdentityVerified = true,
    )

    private fun claim(id: String, kind: InventoryClaimKind, origin: InventoryDerivation) = InventoryClaim(
        sourceRecordId = id,
        label = id,
        volumeLabel = null,
        language = null,
        kind = kind,
        derivation = origin,
        upstreamSourceId = "editor",
        observedAt = 1L,
    )
}
