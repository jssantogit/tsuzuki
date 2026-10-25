package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

/** Synthetic authority and catalog fixtures only; no network or canonical writes. */
class PreviewEditorialEditionInventoryTest {
    @Test
    fun `the Host must provide independent exact edition authorization`() {
        val selected = scope()
        val observed = batch(selected, claim("1"))
        val noGrant = PreviewEditorialEditionInventory { _, _ -> null }
        val wrongEdition = preview(selected.copy(editionKey = "edition-other"))
        val wrongWork = preview(selected.copy(externalWorkId = "work-other"))
        val wrongLanguage = preview(selected.copy(language = "pt-br"))
        val wrongTitle = preview(selected.copy(canonicalTitleId = "other-title"))
        val wrongProducer = preview(selected.copy(producerId = "untrusted-producer"))

        noGrant.execute(observed, emptyList()) shouldBe EditorialEditionPreflight.Blocked(
            EditorialEditionBlockReason.HOST_AUTHORIZATION,
        )
        listOf(wrongEdition, wrongWork, wrongLanguage, wrongTitle, wrongProducer).forEach {
            it.execute(observed, emptyList()) shouldBe EditorialEditionPreflight.Blocked(
                EditorialEditionBlockReason.HOST_AUTHORIZATION,
            )
        }
    }

    @Test
    fun `a self-declared verified scope cannot override the Host grant`() {
        val authorized = scope()
        val planner = preview(authorized)

        listOf(
            authorized.copy(workIdentityVerified = false),
            authorized.copy(editionIdentityVerified = false),
            authorized.copy(language = null),
            authorized.copy(editionKey = null, editionIdentityVerified = false),
        ).forEach { unverified ->
            planner.execute(batch(unverified, claim("1")), emptyList()) shouldBe
                EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.HOST_AUTHORIZATION)
        }
    }

    @Test
    fun `exact decimal units remain distinct without floating point rounding`() {
        val edition = scope()
        val result = preview(edition).execute(
            batch(edition, claim("0.01"), claim("0.1"), claim("12.05"), claim("12.5")),
            emptyList(),
        ) as EditorialEditionPreflight.Ready

        result.scope shouldBe edition
        result.units.map { it.label }.shouldContainExactly("0.01", "0.1", "12.05", "12.5")
        result.units.map { it.identity }.distinct().size shouldBe 4
        result.units.map { it.identity.sortKey }.distinct().size shouldBe 4
        result.units.all { it.scope == edition } shouldBe true
    }

    @Test
    fun `host preview fails closed for releases community candidates counts and mixed authority`() {
        val edition = scope()
        val wrongEvidence = listOf(
            claim("1").copy(derivation = InventoryDerivation.RELEASE_RECORD),
            claim("1").copy(derivation = InventoryDerivation.COMMUNITY_CURATED),
            claim("1").copy(derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC),
            claim("1").copy(derivation = InventoryDerivation.COUNT_DERIVED),
            claim("1").copy(kind = InventoryClaimKind.DECLARED_TOTAL),
            claim("1").copy(language = "pt-br"),
            claim("1").copy(upstreamSourceId = null),
        )
        wrongEvidence.forEach { bad ->
            preview(edition).execute(batch(edition, claim("2"), bad), emptyList()) shouldBe
                EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.NON_EDITORIAL_EVIDENCE)
        }
    }

    @Test
    fun `ranges vague chapter names and unrepresentable exact fractions never receive identities`() {
        val edition = scope()
        listOf("1-5", "Special Chapter 1", "Chapter 1v2", "12.0", "12.0000000001", "Tower of God Ep. 235").forEach { label ->
            preview(edition).execute(batch(edition, claim(label)), emptyList()) shouldBe
                EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.UNSUPPORTED_STRUCTURE)
        }
    }

    @Test
    fun `different upstream units cannot silently share one numbered identity`() {
        val edition = scope()
        val duplicated = batch(
            edition,
            claim("12").copy(sourceRecordId = "publisher-unit-1"),
            claim("12").copy(sourceRecordId = "publisher-unit-2"),
        )
        val replay = batch(edition, claim("12"), claim("12"))

        preview(edition).execute(duplicated, emptyList()) shouldBe
            EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.DUPLICATE_OR_COLLIDING_UNIT)
        preview(edition).execute(replay, emptyList()) shouldBe
            EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.DUPLICATE_OR_COLLIDING_UNIT)
    }

    @Test
    fun `existing unscoped chapters and historical count IDs block potentially overlapping editions`() {
        val edition = scope()
        val historical = CanonicalChapter(
            id = "tsuzuki:count:old-title:12",
            canonicalTitleId = edition.canonicalTitleId,
            displayNumber = "12",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        )
        val legacyDecimal = historical.copy(
            id = "legacy-decimal",
            baseNumber = 12,
            part = 5,
            displayNumber = "12.5",
        )
        val planner = preview(edition)

        listOf(historical, legacyDecimal).forEach { current ->
            planner.execute(batch(edition, claim("12.05")), listOf(current)) shouldBe
                EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.UNSCOPED_CANONICAL_COLLISION)
        }
        // Another chapter number is not proof that the selected edition is complete.
        val distinct = planner.execute(batch(edition, claim("13")), listOf(historical))
        (distinct is EditorialEditionPreflight.Ready) shouldBe true
    }

    @Test
    fun `two explicit editions remain separately scoped in dry-run output`() {
        val original = scope().copy(editionKey = "original-ja")
        val revised = scope().copy(editionKey = "revised-ja")
        val originalPlan = preview(original).execute(batch(original, claim("1")), emptyList())
        val revisedPlan = preview(revised).execute(batch(revised, claim("1")), emptyList())

        (originalPlan is EditorialEditionPreflight.Ready) shouldBe true
        (revisedPlan is EditorialEditionPreflight.Ready) shouldBe true
        (originalPlan as EditorialEditionPreflight.Ready).units.single().scope.editionKey shouldBe "original-ja"
        (revisedPlan as EditorialEditionPreflight.Ready).units.single().scope.editionKey shouldBe "revised-ja"
        originalPlan.units.single().identity shouldBe revisedPlan.units.single().identity
    }

    @Test
    fun `a preview cannot claim completeness or Reader availability`() {
        val edition = scope()
        val partial = batch(edition, claim("1"))
        val endOfPages = partial.copy(outcome = InventoryFetchOutcome.EXHAUSTED)
        val planner = preview(edition)

        val first = planner.execute(partial, emptyList()) as EditorialEditionPreflight.Ready
        val last = planner.execute(endOfPages, emptyList()) as EditorialEditionPreflight.Ready

        first.units shouldBe last.units
        first.editorialCompletenessConfirmed shouldBe false
        last.editorialCompletenessConfirmed shouldBe false
    }

    private fun preview(authorized: InventoryScope) =
        PreviewEditorialEditionInventory { producer, title ->
            authorized.takeIf { it.producerId == producer && it.canonicalTitleId == title }
        }

    private fun scope() = InventoryScope(
        canonicalTitleId = "title",
        producerId = "synthetic-editorial",
        externalWorkId = "work-original",
        editionKey = "edition-1-ja",
        language = "ja",
        workIdentityVerified = true,
        editionIdentityVerified = true,
    )

    private fun claim(label: String) = InventoryClaim(
        sourceRecordId = "unit-$label",
        label = label,
        volumeLabel = "1",
        language = "ja",
        kind = InventoryClaimKind.INDIVIDUAL,
        derivation = InventoryDerivation.PRIMARY_EDITORIAL,
        upstreamSourceId = "authorized-edition-feed",
        observedAt = 42L,
    )

    private fun batch(scope: InventoryScope, vararg claims: InventoryClaim) = InventoryEvidenceBatch(
        scope = scope,
        pageKey = "selected-page",
        observedAt = 42L,
        outcome = InventoryFetchOutcome.PARTIAL,
        claims = claims.toList(),
    )
}
