package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderP2pDiagnosticEvent
import eu.kanade.tachiyomi.provider.runtime.ProviderP2pDiagnostics
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderCapabilityExecutor
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPrivilegedHostGrants
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveGateway
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveState
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireGateway
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireState
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

class ScriptProviderTorrentGateway(
    private val executor: ScriptProviderCapabilityExecutor,
    private val managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
) : TorrentSearchGateway, DebridResolveGateway, P2pAcquireGateway {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    override suspend fun search(
        providerId: ProviderId,
        request: TorrentSearchRequest,
    ): ProviderCallResult<ProviderPage<TorrentCandidate>> =
        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.TorrentSearchV1,
            inputJson = json.encodeToString(
                SearchRequestDto(
                    titles = request.titles,
                    preferredLanguages = request.preferredLanguages,
                    chapterNumber = request.chapterNumber,
                    volume = request.volume,
                    cursor = request.cursor?.value,
                    supportsParallelCursors = true,
                    supportsTorrentMetadataHydration = true,
                ),
            ),
        ) { value, active ->
            val decoded = json.decodeFromString<SearchPageDto>(value)
            ProviderPage(
                items = decoded.items.map { candidate ->
                    candidate.toDomain(
                        active.parsed.manifest.permissions.network?.let { network ->
                            ProviderNetworkPolicy(
                                allowedOrigins = network.origins,
                                allowLocalNetwork = network.localNetwork,
                            )
                        },
                    )
                },
                nextCursor = decoded.nextCursor?.let(::ProviderCursor),
                parallelCursors = decoded.parallelCursors.map(::ProviderCursor),
            )
        }

    override suspend fun resolve(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
    ): ProviderCallResult<DebridResolveState> =
        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.DebridResolveV1,
            inputJson = json.encodeToString(request.toDto()),
        ) { value, active ->
            val decoded = json.decodeFromString<DebridResultDto>(value)
            when (decoded.status) {
                STATUS_READY -> {
                    val network = active.parsed.manifest.permissions.network
                        ?: error("Debrid Provider returned HTTP resource without network authority")
                    if (network.origins.isEmpty()) {
                        error("Debrid Provider returned HTTP resource without allowed origins")
                    }
                    val url = decoded.url
                        ?: error("Ready Debrid result is missing URL")
                    ProviderNetworkPolicy(
                        allowedOrigins = network.origins,
                        allowLocalNetwork = network.localNetwork,
                    ).validate(url, resolveAddress = false)

                    DebridResolveState.Ready(
                        TorrentReadableResource.HttpFile(
                            url = url,
                            headers = decoded.headers,
                            allowedOrigins = network.origins,
                            allowLocalNetwork = network.localNetwork,
                        ),
                    )
                }
                STATUS_PENDING ->
                    DebridResolveState.Pending(
                        decoded.jobId ?: error("Pending Debrid result is missing job ID"),
                    )
                else -> error("Unknown Debrid result status")
            }
        }

    override suspend fun acquire(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
    ): ProviderCallResult<P2pAcquireState> {
        val startedAtNanos = System.nanoTime()
        val result = executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.AcquisitionP2pV1,
            inputJson = json.encodeToString(request.toDto()),
            privilegedHostGrants = ScriptProviderPrivilegedHostGrants(
                directP2p = true,
            ),
        ) { value, _ ->
            val decoded = json.decodeFromString<P2pResultDto>(value)
            when (decoded.status) {
                STATUS_READY -> {
                    val resource = ProviderManagedResourceRef(
                        decoded.resource ?: error("Ready P2P result is missing managed resource"),
                    )
                    val managedFormat = ProviderManagedFileFormat.valueOf(
                        decoded.format?.uppercase()
                            ?: error("Ready P2P result is missing archive format"),
                    )
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_STATE,
                        operationId = request.operationId,
                        providerId = providerId.value,
                        codes = mapOf(
                            "state" to "MANAGED_RESOLVE_STARTED",
                            "format" to managedFormat.name,
                        ),
                    )
                    val uri = managedResources.resolve(
                        providerId = providerId,
                        resource = resource,
                        format = managedFormat,
                    )
                    if (uri == null) {
                        ProviderP2pDiagnostics.record(
                            event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                            operationId = request.operationId,
                            providerId = providerId.value,
                            codes = mapOf(
                                "reason" to "MANAGED_RESOURCE_RESOLVE_FAILED",
                                "format" to managedFormat.name,
                            ),
                            priority = LogPriority.ERROR,
                        )
                        error("P2P result is not a host-owned managed resource")
                    }
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_STATE,
                        operationId = request.operationId,
                        providerId = providerId.value,
                        codes = mapOf(
                            "state" to "MANAGED_RESOLVE_READY",
                            "format" to managedFormat.name,
                        ),
                    )

                    P2pAcquireState.Ready(
                        TorrentReadableResource.LocalArchive(
                            uri = uri,
                            format = when (managedFormat) {
                                ProviderManagedFileFormat.CBZ -> TorrentArchiveFormat.CBZ
                                ProviderManagedFileFormat.ZIP -> TorrentArchiveFormat.ZIP
                            },
                        ),
                    )
                }
                STATUS_PENDING ->
                    P2pAcquireState.Pending(
                        decoded.jobId ?: error("Pending P2P result is missing job ID"),
                    )
                else -> error("Unknown P2P result status")
            }
        }

        when (result) {
            is ProviderCallResult.Success -> {
                if (result.value is P2pAcquireState.Ready) {
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_STATE,
                        operationId = request.operationId,
                        providerId = providerId.value,
                        codes = mapOf("state" to "PROVIDER_CALL_READY"),
                        numbers = mapOf("elapsedMs" to elapsedMillis(startedAtNanos)),
                    )
                }
            }
            is ProviderCallResult.Failure -> {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                    operationId = request.operationId,
                    providerId = providerId.value,
                    codes = mapOf(
                        "reason" to "PROVIDER_CALL_FAILED",
                        "providerError" to result.error.code.name,
                    ),
                    numbers = mapOf("elapsedMs" to elapsedMillis(startedAtNanos)),
                    flags = mapOf("retryable" to result.error.retryable),
                    priority = LogPriority.WARN,
                )
            }
        }
        return result
    }

    private fun TorrentCandidateDto.toDomain(
        networkPolicy: ProviderNetworkPolicy?,
    ): TorrentCandidate {
        torrentUrl?.let { url ->
            val policy = networkPolicy
                ?: error("Torrent Provider returned torrent URL without network authority")
            policy.validate(url, resolveAddress = false)
        }
        return TorrentCandidate(
            infoHash = infoHash,
            magnetUri = magnetUri,
            torrentUrl = torrentUrl,
            displayName = displayName,
            sizeBytes = sizeBytes,
            seeders = seeders,
            peers = peers,
            languages = languages,
            files = files?.map { file -> file.toDomain() },
        )
    }

    private fun TorrentCandidateFileDto.toDomain() = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = sizeBytes,
        languages = languages,
    )

    private fun TorrentAcquisitionRequest.toDto() = AcquisitionRequestDto(
        operationId = operationId,
        torrent = TorrentIdentityDto(
            infoHash = candidate.infoHash,
            magnetUri = candidate.magnetUri,
            torrentUrl = candidate.torrentUrl,
        ),
        file = TorrentCandidateFileDto(
            index = selectedFile.index,
            path = selectedFile.path,
            sizeBytes = selectedFile.sizeBytes,
            languages = selectedFile.languages,
        ),
    )

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    @Serializable
    private data class SearchRequestDto(
        val titles: List<String>,
        val preferredLanguages: Set<String> = emptySet(),
        val chapterNumber: String? = null,
        val volume: Int? = null,
        val cursor: String? = null,
        val supportsParallelCursors: Boolean = true,
        val supportsTorrentMetadataHydration: Boolean = true,
    )

    @Serializable
    private data class SearchPageDto(
        val items: List<TorrentCandidateDto>,
        val nextCursor: String? = null,
        val parallelCursors: List<String> = emptyList(),
    )

    @Serializable
    private data class TorrentCandidateDto(
        val infoHash: String? = null,
        val magnetUri: String? = null,
        val torrentUrl: String? = null,
        val displayName: String,
        val sizeBytes: Long? = null,
        val seeders: Int? = null,
        val peers: Int? = null,
        val languages: Set<String> = emptySet(),
        val files: List<TorrentCandidateFileDto>? = null,
    )

    @Serializable
    private data class TorrentCandidateFileDto(
        val index: Int,
        val path: String,
        val sizeBytes: Long? = null,
        val languages: Set<String> = emptySet(),
    )

    @Serializable
    private data class AcquisitionRequestDto(
        val operationId: String,
        val torrent: TorrentIdentityDto,
        val file: TorrentCandidateFileDto,
    )

    @Serializable
    private data class TorrentIdentityDto(
        val infoHash: String? = null,
        val magnetUri: String? = null,
        val torrentUrl: String? = null,
    )

    @Serializable
    private data class DebridResultDto(
        val status: String,
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val jobId: String? = null,
    )

    @Serializable
    private data class P2pResultDto(
        val status: String,
        val resource: String? = null,
        val format: String? = null,
        val jobId: String? = null,
    )

    private companion object {
        const val STATUS_READY = "ready"
        const val STATUS_PENDING = "pending"
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
