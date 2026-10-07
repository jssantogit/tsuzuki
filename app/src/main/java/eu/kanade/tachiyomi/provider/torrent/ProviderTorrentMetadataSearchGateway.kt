package eu.kanade.tachiyomi.provider.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

fun interface ProviderTorrentMetadataInspector {
    suspend fun inspect(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate?
}

class ProviderTorrentMetadataSearchGateway internal constructor(
    private val delegate: TorrentSearchGateway,
    private val registry: ProviderRegistry,
    private val inspector: ProviderTorrentMetadataInspector,
    maxConcurrentInspections: Int = DEFAULT_MAX_CONCURRENT_INSPECTIONS,
) : TorrentSearchGateway {

    private val inspectionPermits = Semaphore(maxConcurrentInspections)

    init {
        require(maxConcurrentInspections in 1..MAX_CONCURRENT_INSPECTIONS) {
            "Provider torrent metadata concurrency is outside supported bounds"
        }
    }

    override suspend fun search(
        providerId: ProviderId,
        request: TorrentSearchRequest,
    ) = when (val result = delegate.search(providerId, request)) {
        is ProviderCallResult.Failure -> result
        is ProviderCallResult.Success -> {
            val descriptor = registry.registration(providerId)?.descriptor
                ?: return result
            val items = coroutineScope {
                result.value.items.map { candidate ->
                    async {
                        if (candidate.files != null) {
                            candidate
                        } else {
                            inspectFailClosed(descriptor, candidate)
                        }
                    }
                }.awaitAll()
            }
            ProviderCallResult.Success(result.value.copy(items = items))
        }
    }

    private suspend fun inspectFailClosed(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate = try {
        inspectionPermits.withPermit {
            inspector.inspect(descriptor, candidate)
        } ?: candidate
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        candidate
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_INSPECTIONS = 4
        const val MAX_CONCURRENT_INSPECTIONS = 8
    }
}
