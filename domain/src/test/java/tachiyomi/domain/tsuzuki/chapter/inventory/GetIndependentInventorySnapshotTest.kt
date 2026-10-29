package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class GetIndependentInventorySnapshotTest {
    @Test
    fun `community and local index candidates never count as editorial chapters`() = runTest {
        val ledger = FakeLedger()
        val wikipedia = scope("wikimedia", "japanese-original")
        val local = scope("local-toc", "translated-edition")
        ledger.saveBatch(
            batch(
                wikipedia,
                "revision-verified",
                20L,
                InventoryFetchOutcome.EXHAUSTED,
                listOf(
                    claim("ch1", "1", InventoryDerivation.COMMUNITY_CURATED, 20L),
                    claim("ch2", "2", InventoryDerivation.COMMUNITY_CURATED, 20L),
                ),
            ),
        )
        ledger.saveBatch(
            batch(
                local,
                "file-selection",
                21L,
                InventoryFetchOutcome.EXHAUSTED,
                listOf(claim("toc-1", "0.01", InventoryDerivation.USER_SUPPLIED_EDITION_TOC, 21L)),
            ),
        )

        val snapshot = GetIndependentInventorySnapshot(ledger).execute("title")
        val community = snapshot.sources.single { it.scope.producerId == "wikimedia" }
        val imported = snapshot.sources.single { it.scope.producerId == "local-toc" }

        snapshot.knowledge shouldBe InventoryKnowledge.PARTIAL
        snapshot.editorialCompletenessConfirmed shouldBe false
        community.knownIndividualClaims shouldBe 0
        community.candidateObservations shouldBe 2
        community.candidateExampleLabels shouldBe listOf("1", "2")
        imported.knownIndividualClaims shouldBe 0
        imported.candidateObservations shouldBe 1
        imported.candidateExampleLabels shouldBe listOf("0.01")
    }

    @Test
    fun `offline candidate examples retain bounded provenance without editorial promotion`() = runTest {
        val ledger = FakeLedger()
        val source = scope("wikimedia", "selected-edition")
        val provenance = InventoryCandidateProvenance(
            sourceKind = InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION,
            originReference = "https://example.invalid/wiki/Selected_index",
            exactRevision = "synthetic-revision-7",
            attributionReference = "Synthetic fixture attribution",
            licenseReference = "Synthetic fixture license reference",
            snapshotDigest = sha256("synthetic revision 7: Chapter_12_05"),
            sourceUnitId = "Chapter_12_05",
            structuralCategory = "chapter-list-entry",
        )
        val candidate = claim(
            "wiki-unit-12.05",
            "12.05",
            InventoryDerivation.COMMUNITY_CURATED,
            42L,
        ).copy(provenance = provenance)
        ledger.saveBatch(
            batch(source, "selected-revision-7", 42L, InventoryFetchOutcome.PARTIAL, listOf(candidate)),
        )

        val snapshot = GetIndependentInventorySnapshot(ledger).execute("title")
        val sourceSnapshot = snapshot.sources.single()

        sourceSnapshot.knownIndividualClaims shouldBe 0
        sourceSnapshot.candidateObservations shouldBe 1
        sourceSnapshot.candidateExampleLabels shouldBe listOf("12.05")
        sourceSnapshot.candidateExamples shouldBe listOf(
            InventoryCandidateExample(
                sourceRecordId = candidate.sourceRecordId,
                label = "12.05",
                provenance = provenance,
                observedAt = 42L,
            ),
        )
        snapshot.editorialCompletenessConfirmed shouldBe false
    }

    @Test
    fun `different labels for the same selected-source unit remain a visible conflict`() = runTest {
        val ledger = FakeLedger()
        val source = scope("wikimedia", "selected-edition")
        fun candidate(id: String, label: String, revision: String, observedAt: Long) =
            claim(id, label, InventoryDerivation.COMMUNITY_CURATED, observedAt).copy(
                provenance = InventoryCandidateProvenance(
                    sourceKind = InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION,
                    originReference = "https://example.invalid/wiki/Selected_index",
                    exactRevision = revision,
                    snapshotDigest = sha256("synthetic revision $revision: Chapter_12_05 = $label"),
                    sourceUnitId = "Chapter_12_05",
                    structuralCategory = "chapter-list-entry",
                ),
            )
        val first = candidate("revision-1-Chapter_12_05", "12.05", "1", 10L)
        val second = candidate("revision-2-Chapter_12_05", "12.5", "2", 20L)
        ledger.saveBatch(
            batch(
                source,
                "revision-1",
                10L,
                InventoryFetchOutcome.PARTIAL,
                listOf(first),
            ),
        )
        ledger.saveBatch(
            batch(
                source,
                "revision-2",
                20L,
                InventoryFetchOutcome.PARTIAL,
                listOf(second),
            ),
        )

        val sourceSnapshot = GetIndependentInventorySnapshot(ledger).execute("title").sources.single()

        val conflict = sourceSnapshot.candidateConflicts.single()
        conflict.sourceKind shouldBe InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION
        conflict.sourceUnitId shouldBe "Chapter_12_05"
        conflict.candidates shouldBe listOf(
            InventoryCandidateExample(second.sourceRecordId, second.label!!, second.provenance!!, second.observedAt),
            InventoryCandidateExample(first.sourceRecordId, first.label!!, first.provenance!!, first.observedAt),
        )
        sourceSnapshot.knownIndividualClaims shouldBe 0
        sourceSnapshot.candidateObservations shouldBe 2
    }

    @Test
    fun `offline candidate examples omit labels beyond the bounded text limit`() = runTest {
        val ledger = FakeLedger()
        val source = scope("wikimedia", "selected-edition")
        val label = "x".repeat(513)
        val candidate = claim(
            "oversized-label",
            label,
            InventoryDerivation.COMMUNITY_CURATED,
            20L,
        ).copy(
            provenance = InventoryCandidateProvenance(
                sourceKind = InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION,
                originReference = "https://example.invalid/wiki/Selected_index",
                exactRevision = "1",
                snapshotDigest = sha256("== Chapters ==\n* [[Chapter_1|$label]]"),
                sourceUnitId = "Chapter_1",
                structuralCategory = "chapter-list-entry",
            ),
        )
        ledger.saveBatch(batch(source, "revision-1", 20L, InventoryFetchOutcome.PARTIAL, listOf(candidate)))

        val sourceSnapshot = GetIndependentInventorySnapshot(ledger).execute("title").sources.single()

        sourceSnapshot.candidateObservations shouldBe 1
        sourceSnapshot.candidateExampleLabels shouldBe emptyList()
        sourceSnapshot.candidateExamples shouldBe emptyList()
    }

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
        result.sources.single().knownEditorialLabels shouldBe (1..8).map(Int::toString)
        result.sources.single().releaseExampleLabels shouldBe emptyList()
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
        result.sources.single().knownEditorialLabels shouldBe emptyList()
        result.sources.single().releaseExampleLabels shouldBe listOf("246-251", "12.05")
        result.sources.single().numberedReleaseCandidateCount shouldBe 1
        result.sources.single().numberedReleaseCandidates shouldBe listOf(
            InventoryReleaseChapterCandidate(label = "12.05", language = null, releaseCount = 1),
        )
    }

    @Test
    fun `distinct release numbers and languages remain provisional groups without creating chapters`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangadex", null, editionVerified = false)
        ledger.saveBatch(
            batch(
                source,
                "start",
                30L,
                InventoryFetchOutcome.EXHAUSTED,
                listOf(
                    claim("a", "12.05", InventoryDerivation.RELEASE_RECORD, 30L).copy(language = "en"),
                    claim("b", "12.05", InventoryDerivation.RELEASE_RECORD, 30L).copy(language = "en"),
                    claim("c", "12.05", InventoryDerivation.RELEASE_RECORD, 30L).copy(language = "pt-BR"),
                    claim("d", "12.5", InventoryDerivation.RELEASE_RECORD, 30L).copy(language = "en"),
                    claim("e", "0.01", InventoryDerivation.RELEASE_RECORD, 30L),
                    claim("f", "0.1", InventoryDerivation.RELEASE_RECORD, 30L),
                    claim(
                        "range",
                        "246-251",
                        InventoryDerivation.RELEASE_RECORD,
                        30L,
                        InventoryClaimKind.RELEASE_RANGE,
                    ),
                    claim("untrusted", "01", InventoryDerivation.RELEASE_RECORD, 30L),
                ),
            ),
        )

        val snapshot = GetIndependentInventorySnapshot(ledger).execute("title").sources.single()
        snapshot.knownIndividualClaims shouldBe 0
        snapshot.candidateObservations shouldBe 0
        snapshot.releaseObservations shouldBe 8
        snapshot.numberedReleaseCandidateCount shouldBe 5
        snapshot.numberedReleaseCandidates shouldBe listOf(
            InventoryReleaseChapterCandidate("12.05", "en", 2),
            InventoryReleaseChapterCandidate("12.05", "pt-BR", 1),
            InventoryReleaseChapterCandidate("12.5", "en", 1),
            InventoryReleaseChapterCandidate("0.01", null, 1),
            InventoryReleaseChapterCandidate("0.1", null, 1),
        )
    }

    @Test
    fun `an exhausted empty release feed has no provisional candidates or invented chapters`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangaupdates", null, editionVerified = false)
        ledger.saveBatch(batch(source, "start", 50L, InventoryFetchOutcome.EXHAUSTED, emptyList()))
        val snapshot = GetIndependentInventorySnapshot(ledger).execute("title").sources.single()
        snapshot.releaseObservations shouldBe 0
        snapshot.numberedReleaseCandidateCount shouldBe 0
        snapshot.numberedReleaseCandidates shouldBe emptyList()
        snapshot.knownIndividualClaims shouldBe 0
        snapshot.latestSuccessfulFetchAt shouldBe 50L
    }

    @Test
    fun `numbered release examples are bounded while the total remains truthful`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangadex", null, editionVerified = false)
        ledger.saveBatch(
            batch(
                source,
                "start",
                50L,
                InventoryFetchOutcome.EXHAUSTED,
                (1..22).map { number ->
                    claim("release-$number", "$number", InventoryDerivation.RELEASE_RECORD, 50L)
                },
            ),
        )

        val snapshot = GetIndependentInventorySnapshot(ledger).execute("title").sources.single()
        snapshot.numberedReleaseCandidateCount shouldBe 22
        snapshot.numberedReleaseCandidates.map { it.label } shouldBe (1..16).map(Int::toString)
        snapshot.knownIndividualClaims shouldBe 0
        snapshot.candidateObservations shouldBe 0
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

    @Test
    fun `failed attempt remains latest without advancing claim observation time`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangadex", "original")
        ledger.saveBatch(
            batch(
                source,
                "page-1",
                10L,
                InventoryFetchOutcome.PARTIAL,
                listOf(claim("release", "12.05", InventoryDerivation.RELEASE_RECORD, 10L)),
            ),
        )
        ledger.saveBatch(batch(source, "page-2", 20L, InventoryFetchOutcome.FAILED, emptyList()))

        val result = GetIndependentInventorySnapshot(ledger).execute("title")
        val snapshot = result.sources.single()

        snapshot.latestObservedAt shouldBe 20L
        snapshot.latestAttemptAt shouldBe 20L
        snapshot.latestAttemptOutcomes shouldBe setOf(InventoryFetchOutcome.FAILED)
        snapshot.latestSuccessfulFetchAt shouldBe 10L
        snapshot.latestClaimObservedAt shouldBe 10L
    }

    @Test
    fun `first failed attempt has no successful fetch or claim observation time`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangadex", "original")
        ledger.saveBatch(batch(source, "page-1", 20L, InventoryFetchOutcome.FAILED, emptyList()))

        val result = GetIndependentInventorySnapshot(ledger).execute("title")
        val snapshot = result.sources.single()

        result.knowledge shouldBe InventoryKnowledge.UNKNOWN
        snapshot.latestAttemptAt shouldBe 20L
        snapshot.latestAttemptOutcomes shouldBe setOf(InventoryFetchOutcome.FAILED)
        snapshot.latestSuccessfulFetchAt shouldBe null
        snapshot.latestClaimObservedAt shouldBe null
    }

    @Test
    fun `empty partial fetch is visible without inventing a claim observation`() = runTest {
        val ledger = FakeLedger()
        val source = scope("mangadex", null, editionVerified = false)
        ledger.saveBatch(batch(source, "page-1", 20L, InventoryFetchOutcome.PARTIAL, emptyList()))

        val result = GetIndependentInventorySnapshot(ledger).execute("title")
        val snapshot = result.sources.single()

        result.knowledge shouldBe InventoryKnowledge.PARTIAL
        result.editorialCompletenessConfirmed shouldBe false
        snapshot.knownIndividualClaims shouldBe 0
        snapshot.releaseObservations shouldBe 0
        snapshot.latestAttemptAt shouldBe 20L
        snapshot.latestAttemptOutcomes shouldBe setOf(InventoryFetchOutcome.PARTIAL)
        snapshot.latestSuccessfulFetchAt shouldBe 20L
        snapshot.latestClaimObservedAt shouldBe null
    }

    @Test
    fun `attempt and success timestamps stay isolated in offline edition snapshots`() = runTest {
        val ledger = FakeLedger()
        val original = scope("mangadex", "original")
        val colored = scope("mangadex", "colored")
        ledger.saveBatch(
            batch(
                original,
                "page-1",
                10L,
                InventoryFetchOutcome.PARTIAL,
                listOf(claim("release", "12.05", InventoryDerivation.RELEASE_RECORD, 10L)),
            ),
        )
        ledger.saveBatch(batch(colored, "page-1", 20L, InventoryFetchOutcome.FAILED, emptyList()))

        val result = GetIndependentInventorySnapshot(ledger).execute("title")

        result.sources.map { it.scope.editionKey }.toSet() shouldBe setOf("original", "colored")
        val originalSnapshot = result.sources.single { it.scope.editionKey == "original" }
        originalSnapshot.latestAttemptAt shouldBe 10L
        originalSnapshot.latestSuccessfulFetchAt shouldBe 10L
        originalSnapshot.latestClaimObservedAt shouldBe 10L
        val coloredSnapshot = result.sources.single { it.scope.editionKey == "colored" }
        coloredSnapshot.latestAttemptAt shouldBe 20L
        coloredSnapshot.latestAttemptOutcomes shouldBe setOf(InventoryFetchOutcome.FAILED)
        coloredSnapshot.latestSuccessfulFetchAt shouldBe null
        coloredSnapshot.latestClaimObservedAt shouldBe null
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

    private fun sha256(content: String): String = "sha256:" +
        MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

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
