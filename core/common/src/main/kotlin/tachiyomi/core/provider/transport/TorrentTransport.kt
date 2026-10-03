package tachiyomi.core.provider.transport

data class TorrentCandidate(
    val infoHash: String,
    val magnetUri: String,
    val files: List<TorrentCandidateFile>,
)

data class TorrentCandidateFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

sealed interface ProviderReadableResource {
    data class HttpFile(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) : ProviderReadableResource

    data class LocalFile(
        val absolutePath: String,
    ) : ProviderReadableResource
}

enum class TorrentAcquisitionRoute {
    DEBRID,
    DIRECT_P2P,
}

fun interface TorrentAcquisitionBackend {
    suspend fun acquire(
        candidate: TorrentCandidate,
        file: TorrentCandidateFile,
    ): ProviderReadableResource
}

data class TorrentAcquisitionResult(
    val file: TorrentCandidateFile,
    val resource: ProviderReadableResource,
)

class TorrentAcquisitionRouter(
    private val debrid: TorrentAcquisitionBackend,
    private val directP2p: TorrentAcquisitionBackend,
) {
    suspend fun resolve(
        candidate: TorrentCandidate,
        fileIndex: Int,
        route: TorrentAcquisitionRoute,
    ): TorrentAcquisitionResult {
        val file = candidate.files.singleOrNull { it.index == fileIndex }
            ?: throw IllegalArgumentException("Torrent file index is unavailable or ambiguous: $fileIndex")
        val backend = when (route) {
            TorrentAcquisitionRoute.DEBRID -> debrid
            TorrentAcquisitionRoute.DIRECT_P2P -> directP2p
        }
        return TorrentAcquisitionResult(
            file = file,
            resource = backend.acquire(candidate, file),
        )
    }
}
