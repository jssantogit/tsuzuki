package tachiyomi.domain.tsuzuki.provider.reading

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

/**
 * Explicitly constructed by the unsigned research harness only.
 * A bibliographic work match is not creator or editorial equivalence.
 * This policy allows consideration of exact source-scoped chapter options,
 * never an automatic binding, inventory promotion, or silent source fallback.
 */
class MangaAllCatalogReaderResearch(
    private val titles: CanonicalTitleRepository,
    private val integrations: IntegrationRegistry,
    private val registry: ProviderRegistry,
    private val associations: ProviderWorkSourceAssociationRepository,
    private val selections: MangaAllSourceSelectionRepository,
) {
    suspend fun proof(association: ProviderWorkSourceAssociation): String? {
        if (association.workVerification != ProviderWorkVerification.CATALOG_CORROBORATED ||
            association.editionQualification != ProviderSourceEditionQualification.UNRESOLVED
        ) {
            return null
        }
        val saved = association.catalogIdentityFingerprint ?: return null
        val ref = association.ref
        return try {
            val owner = IntegrationId("mangaupdates")
            if (!integrations.isGlobalCapabilityActive(owner, IntegrationCapability.METADATA_BASIC)) return null
            registry.awaitReady()
            val registration = registry.registration(ref.providerId) ?: return null
            if (registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED ||
                registration.descriptor.runtime != ProviderRuntimeKind.SCRIPT ||
                !registration.enabledCapabilities.containsAll(REQUIRED_CAPABILITIES) ||
                registration.facets.none { it.facetId == ref.facetId }
            ) {
                return null
            }
            val record = titles.getExternalIdentities(association.canonicalTitleId)
                .filter { it.provider == owner.value }.singleOrNull() ?: return null
            if (!record.verified || record.canonicalTitleId != association.canonicalTitleId) return null
            val current = MangaAllCatalogWorkIdentityProof.fingerprint(
                association.canonicalTitleId,
                ref,
                record.externalId,
                registration.descriptor.version.code,
                registration.configurationFingerprint,
            ) ?: return null
            if (current != saved || associations.get(ref) != association) return null
            // Do not select one of multiple unresolved, corroborated source HIDs.
            val rival = associations.getByTitle(association.canonicalTitleId).any {
                it.ref != ref && it.ref.providerId == ref.providerId && it.ref.facetId == ref.facetId &&
                    MangaAllReadingIdentity.sourceId(it.ref) == MangaAllReadingIdentity.sourceId(ref) &&
                    it.editionQualification == ProviderSourceEditionQualification.UNRESOLVED &&
                    it.workVerification in CORROBORATED
            }
            current.takeUnless { rival }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    suspend fun permitsDelivery(canonicalTitleId: String, option: ProviderChapterReadingOption): Boolean {
        val expected = option.catalogWorkFingerprint ?: return false
        val ref = option.bindingRef()
        val association = associations.get(ref) ?: return false
        if (association.canonicalTitleId != canonicalTitleId || proof(association) != expected) return false
        val saved = selections.get(canonicalTitleId, ref.facetId ?: return false) ?: return false
        return saved.ref == ref && saved.catalogIdentityFingerprint == expected &&
            selections.getRelease(canonicalTitleId, option.canonicalChapterId, ref) == option.providerChapterId
    }

    private companion object {
        val CORROBORATED = setOf(
            ProviderWorkVerification.AUTHOR_CORROBORATED,
            ProviderWorkVerification.CATALOG_CORROBORATED,
        )
        val REQUIRED_CAPABILITIES = setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        )
    }
}
