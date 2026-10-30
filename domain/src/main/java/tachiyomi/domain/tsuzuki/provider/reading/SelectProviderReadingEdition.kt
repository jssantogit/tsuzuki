package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.util.UUID

/** A source-provided work, not a canonical chapter or editorial certification. */
data class ProviderEditionCandidate(
    val ref: ProviderBindingRef,
    val providerName: String,
    val title: String,
    val url: String?,
    val query: String,
    val providerVersionCode: Long,
    val configurationFingerprint: String,
)

data class ProviderEditionSearchResult(
    val candidates: List<ProviderEditionCandidate> = emptyList(),
    val hadFailures: Boolean = false,
)

enum class ProviderEditionConfirmation {
    BOUND,
    ALREADY_BOUND,
    BLOCKED,
    STALE,
    UNAVAILABLE,
}

/**
 * Explicit Provider work/edition selection for the existing Details selection-sheet family.
 *
 * The Host checks that an enabled, permissioned SCRIPT Provider still advertises the selected
 * work before writing. A different existing binding is never overwritten: old chapter support,
 * progress and Reader state need a separate, transactional rebind design.
 */
@Inject
class SelectProviderReadingEdition(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val providerRegistry: ProviderRegistry,
    private val gateway: ProviderReadingGateway,
    private val bindingRepository: ProviderReadingBindingRepository,
) {
    suspend fun search(canonicalTitleId: String, query: String): ProviderEditionSearchResult {
        if (canonicalTitleId.isBlank() || query.isBlank() || query.length > MAX_QUERY_CHARS) {
            return ProviderEditionSearchResult()
        }
        if (canonicalTitleRepository.getById(canonicalTitleId) == null) return ProviderEditionSearchResult()
        providerRegistry.awaitReady()
        val candidates = mutableListOf<ProviderEditionCandidate>()
        var failed = false

        providerRegistry.providers()
            .filter { it.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
            .filter { it.descriptor.runtime == ProviderRuntimeKind.SCRIPT }
            .filter {
                ProviderCapabilities.ReadingLookupV1 in it.enabledCapabilities &&
                    ProviderCapabilities.ReadingChaptersV1 in it.enabledCapabilities
            }
            .forEach { registration ->
                val facets = registration.facets.map { it.facetId }.ifEmpty { listOf(null) }
                facets.forEach { facetId ->
                    val response = try {
                        withTimeoutOrNull(LOOKUP_BUDGET_MILLIS) {
                            gateway.lookup(
                                registration.descriptor.id,
                                ProviderReadingLookupRequest(listOf(query.trim())),
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    when (response) {
                        is ProviderCallResult.Success -> {
                            response.value.items.take(MAX_CANDIDATES_PER_SOURCE).forEach { item ->
                                if (
                                    facetId == null ||
                                    item.language == null ||
                                    item.language.equals(facetId, ignoreCase = true)
                                ) {
                                    candidates += ProviderEditionCandidate(
                                        ref = ProviderBindingRef(
                                            providerId = registration.descriptor.id,
                                            facetId = facetId,
                                            externalWorkId = item.externalWorkId,
                                        ),
                                        providerName = registration.descriptor.name,
                                        title = item.title,
                                        url = item.url,
                                        query = query.trim(),
                                        providerVersionCode = registration.descriptor.version.code,
                                        configurationFingerprint = registration.configurationFingerprint,
                                    )
                                }
                            }
                            if (response.value.nextCursor != null) failed = true
                        }
                        else -> failed = true
                    }
                }
            }

        return ProviderEditionSearchResult(
            candidates = candidates.distinctBy { it.ref },
            hadFailures = failed,
        )
    }

    suspend fun confirm(canonicalTitleId: String, candidate: ProviderEditionCandidate): ProviderEditionConfirmation {
        if (canonicalTitleRepository.getById(canonicalTitleId) == null) {
            return ProviderEditionConfirmation.UNAVAILABLE
        }
        providerRegistry.awaitReady()
        val registration = providerRegistry.registration(candidate.ref.providerId)
            ?: return ProviderEditionConfirmation.UNAVAILABLE
        if (
            registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED ||
            registration.descriptor.runtime != ProviderRuntimeKind.SCRIPT ||
            ProviderCapabilities.ReadingLookupV1 !in registration.enabledCapabilities ||
            ProviderCapabilities.ReadingChaptersV1 !in registration.enabledCapabilities ||
            registration.descriptor.version.code != candidate.providerVersionCode ||
            registration.configurationFingerprint != candidate.configurationFingerprint ||
            (
                registration.facets.isNotEmpty() &&
                    registration.facets.none { it.facetId == candidate.ref.facetId }
                )
        ) {
            return ProviderEditionConfirmation.UNAVAILABLE
        }

        // Re-query under the current grants/version, never trust a stale selection.
        val refreshed = search(canonicalTitleId, candidate.query)
        if (refreshed.candidates.none { it == candidate }) {
            return if (refreshed.hadFailures) {
                ProviderEditionConfirmation.UNAVAILABLE
            } else {
                ProviderEditionConfirmation.STALE
            }
        }
        // A grant, registration or package version revoked during the lookup cannot authorize a write.
        if (providerRegistry.registration(candidate.ref.providerId) != registration) {
            return ProviderEditionConfirmation.UNAVAILABLE
        }
        val current = bindingRepository.get(
            canonicalTitleId,
            candidate.ref.providerId,
            candidate.ref.facetId,
        )
        if (current != null) {
            return if (current.ref == candidate.ref && current.availability == ProviderBindingAvailability.AVAILABLE) {
                ProviderEditionConfirmation.ALREADY_BOUND
            } else {
                ProviderEditionConfirmation.BLOCKED
            }
        }
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        return try {
            bindingRepository.upsert(
                ProviderReadingBinding(
                    id = "confirmed:" + UUID.randomUUID(),
                    canonicalTitleId = canonicalTitleId,
                    ref = candidate.ref,
                    verification = ProviderBindingVerification.USER_CONFIRMED,
                    availability = ProviderBindingAvailability.AVAILABLE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            ProviderEditionConfirmation.BOUND
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Database uniqueness also protects against another canonical title owning this work.
            ProviderEditionConfirmation.BLOCKED
        }
    }

    private companion object {
        const val MAX_QUERY_CHARS = 1024
        const val MAX_CANDIDATES_PER_SOURCE = 20
        const val LOOKUP_BUDGET_MILLIS = 15_000L
    }
}
