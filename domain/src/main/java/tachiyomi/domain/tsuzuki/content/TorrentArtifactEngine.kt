package tachiyomi.domain.tsuzuki.content

fun interface TorrentArtifactEngine {
    suspend fun acquire(request: TorrentArtifactRequest): Result<PreparedTorrentArtifact>
}

data class TorrentArtifactRequest(
    val infoHash: String,
    val magnetUri: String?,
    val fileIndex: Int?,
    val filePath: String?,
    val torrentUrl: String? = null,
    val fileSizeBytes: Long? = null,
    val discoveryOperationId: String? = null,
)

data class PreparedTorrentArtifact(
    val localUri: String,
    val format: String,
)
