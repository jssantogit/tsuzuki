package tachiyomi.domain.tsuzuki.chapter.inventory

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import kotlin.time.Clock

/**
 * Explicitly enabled torrent.search providers can contribute provisional,
 * Host-observed release records to ICE. This path never creates editorial
 * chapters, provider reading options, or synced reading progress.
 *
 * The caller is responsible for passing the current inventory permission
 * through [authorized]. The active Provider registration is revalidated after
 * every remote request, before persisting any observation.
 */
@Inject
class RefreshProviderTorrentInventory(
    private val canonicalTitles: CanonicalTitleRepository,
    private val registry: ProviderRegistry,
    private val searchGateway: TorrentSearchGateway,
    private val ledger: InventoryEvidenceRepository,
) {
    private val projector = TorrentInventoryEvidenceProjector()

    fun hasEnabledSources(): Boolean = enabledSources().isNotEmpty()

    suspend fun execute(
        canonicalTitleId: String,
        authorized: suspend () -> Boolean = { true },
        observedAt: Long = Clock.System.now().toEpochMilliseconds(),
        maxPages: Int = 3,
    ): InventoryRefreshResult {
        require(canonicalTitleId.isNotBlank())
        require(observedAt >= 0L)
        require(maxPages in 1..5)

        if (!authorized()) return result(InventoryRefreshStop.DISABLED, 0, 0)
        val sources = enabledSources()
        if (sources.isEmpty()) return result(InventoryRefreshStop.DISABLED, 0, 0)
        val title = canonicalTitles.getById(canonicalTitleId)
            ?: return result(InventoryRefreshStop.NO_VERIFIED_WORK, 0, 0)

        var saved = 0
        var observations = 0
        var incomplete = false
        for (source in sources.take(MAX_PROVIDERS_PER_REFRESH)) {
            val initialRegistration = registry.registration(source.id)
                ?: return result(InventoryRefreshStop.DISABLED, saved, observations)
            var cursor: tachiyomi.domain.tsuzuki.provider.ProviderCursor? = null
            val visited = mutableSetOf<String>()
            var exhausted = false
            for (pageIndex in 0 until maxPages) {
                if (!authorized() || !isStillEnabled(initialRegistration)) {
                    return result(InventoryRefreshStop.DISABLED, saved, observations)
                }
                val response = try {
                    searchGateway.search(
                        source.id,
                        TorrentSearchRequest(
                            titles = listOf(title.displayTitle),
                            chapterNumber = null,
                            cursor = cursor,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return result(InventoryRefreshStop.FAILED, saved, observations)
                }
                currentCoroutineContext().ensureActive()
                if (!authorized() || !isStillEnabled(initialRegistration)) {
                    return result(InventoryRefreshStop.DISABLED, saved, observations)
                }

                val page = when (response) {
                    is ProviderCallResult.Success -> response.value
                    is ProviderCallResult.Failure ->
                        return result(InventoryRefreshStop.FAILED, saved, observations)
                }
                val batches = projector.project(
                    canonicalTitleId = canonicalTitleId,
                    displayTitle = title.displayTitle,
                    providerId = source.id,
                    candidates = page.items,
                    language = null,
                    observedAt = observedAt,
                )
                for (batch in batches) {
                    currentCoroutineContext().ensureActive()
                    if (!authorized() || !isStillEnabled(initialRegistration)) {
                        return result(InventoryRefreshStop.DISABLED, saved, observations)
                    }
                    ledger.saveBatch(batch)
                    saved++
                    observations += batch.claims.size
                }

                val next = page.nextCursor
                if (next == null && page.parallelCursors.isEmpty()) {
                    exhausted = true
                    break
                }
                if (next == null || !visited.add(next.value)) {
                    incomplete = true
                    break
                }
                cursor = next
            }
            if (!exhausted) incomplete = true
        }
        return result(
            if (incomplete) InventoryRefreshStop.PARTIAL else InventoryRefreshStop.EXHAUSTED,
            saved,
            observations,
        )
    }

    private fun enabledSources(): List<ProviderDescriptor> =
        registry.enabled(ProviderCapabilities.TorrentSearchV1)
            .filter { it.runtime == ProviderRuntimeKind.SCRIPT }

    private fun isStillEnabled(original: ProviderRegistration): Boolean =
        registry.registration(original.descriptor.id) == original &&
            enabledSources().any { it.id == original.descriptor.id }

    private fun result(stop: InventoryRefreshStop, saved: Int, observations: Int) =
        InventoryRefreshResult(stop, saved, observations)

    private companion object {
        const val MAX_PROVIDERS_PER_REFRESH = 4
    }
}
