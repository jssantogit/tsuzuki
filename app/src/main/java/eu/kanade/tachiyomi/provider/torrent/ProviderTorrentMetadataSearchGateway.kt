package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeLogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderPage
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
    private val logSink: ProviderRuntimeLogSink = ProviderRuntimeLogSink { _, _ -> },
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
    ): ProviderCallResult<ProviderPage<TorrentCandidate>> {
        return when (val result = delegate.search(providerId, request)) {
            is ProviderCallResult.Failure -> result
            is ProviderCallResult.Success -> {
                val descriptor = registry.registration(providerId)?.descriptor
                    ?: return result
                val sourceItems = result.value.items
                val attempted = sourceItems.count { it.files == null }
                val startedAtNanos = System.nanoTime()
                val items = coroutineScope {
                    sourceItems.map { candidate ->
                        async {
                            if (candidate.files != null) {
                                candidate
                            } else {
                                inspectFailClosed(descriptor, candidate)
                            }
                        }
                    }.awaitAll()
                }
                val hydrated = sourceItems.zip(items).count { (source, resolved) ->
                    source.files == null && resolved.files != null
                }
                val elapsedMillis = ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND)
                    .coerceAtLeast(0L)
                emitHydrationSummary(
                    providerId = providerId,
                    total = sourceItems.size,
                    attempted = attempted,
                    hydrated = hydrated,
                    elapsedMillis = elapsedMillis,
                )
                ProviderCallResult.Success(result.value.copy(items = items))
            }
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

    private fun emitHydrationSummary(
        providerId: ProviderId,
        total: Int,
        attempted: Int,
        hydrated: Int,
        elapsedMillis: Long,
    ) {
        runCatching {
            logSink.info(
                providerId.value,
                "host_torrent_metadata total=$total attempted=$attempted hydrated=$hydrated " +
                    "failed=${attempted - hydrated} elapsedMs=$elapsedMillis",
            )
        }
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_INSPECTIONS = 4
        const val MAX_CONCURRENT_INSPECTIONS = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
