package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ScriptProviderCapabilityExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
                    cursor = request.cursor?.value,
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
    ): ProviderCallResult<P2pAcquireState> =
        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.AcquisitionP2pV1,
            inputJson = json.encodeToString(request.toDto()),
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
                    val uri = managedResources.resolve(
                        providerId = providerId,
                        resource = resource,
                        format = managedFormat,
                    ) ?: error("P2P result is not a host-owned managed resource")

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

    @Serializable
    private data class SearchRequestDto(
        val titles: List<String>,
        val preferredLanguages: Set<String> = emptySet(),
        val cursor: String? = null,
    )

    @Serializable
    private data class SearchPageDto(
        val items: List<TorrentCandidateDto>,
        val nextCursor: String? = null,
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
    }
}
