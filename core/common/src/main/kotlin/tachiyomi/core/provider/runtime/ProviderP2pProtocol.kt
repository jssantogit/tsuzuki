package tachiyomi.core.provider.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Real torrent file identity for one page, never a synthetic directory index.
 * Only Host-verified chapter selections may authorize multiple images.
 */
data class ProviderP2pChapterImage(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

/**
 * Selected-file acquisition intent crossing the isolated Provider boundary.
 * The host remains the sole owner of BitTorrent/native authority.
 */
data class ProviderP2pAcquireRequest(
    val operationId: String,
    val magnetUri: String? = null,
    val torrentUrl: String? = null,
    val infoHash: String? = null,
    val selectedFileIndex: Int,
    val selectedFilePath: String,
    val selectedFileSizeBytes: Long? = null,
    val selectedChapterImages: List<ProviderP2pChapterImage> = emptyList(),
) {
    init {
        require(OPERATION_ID.matches(operationId)) {
            "Provider P2P operation ID is invalid"
        }
        require(selectedFileIndex >= 0) {
            "Provider P2P selected file index must not be negative"
        }
        require(
            selectedFileSizeBytes == null || selectedFileSizeBytes >= 0L,
        ) {
            "Provider P2P selected file size must not be negative"
        }
        // Legacy isolated Provider calls identify the first selected page only.
        // The Host manager rejects that bare page request unless the invocation
        // carries an identical, privately approved complete image selection.
        require(
            if (selectedChapterImages.isEmpty()) {
                validRelativeArchivePath(selectedFilePath) || validRelativeImagePath(selectedFilePath)
            } else {
                validRelativeImagePath(selectedFilePath)
            },
        ) {
            "Provider P2P selected file path is invalid"
        }
        if (selectedChapterImages.isNotEmpty()) {
            require(selectedChapterImages.size <= MAX_CHAPTER_PAGES) {
                "Provider P2P chapter page count exceeds the limit"
            }
            val chapterDirectory = selectedChapterImages.first().path.substringBeforeLast('/', "")
            require(chapterDirectory.isNotBlank()) {
                "Provider P2P images require an actual chapter directory"
            }
            require(
                selectedChapterImages.first().index == selectedFileIndex &&
                    selectedChapterImages.first().path == selectedFilePath &&
                    selectedChapterImages.first().sizeBytes == selectedFileSizeBytes,
            ) {
                "Provider P2P chapter first page must equal the selected file"
            }
            require(selectedChapterImages.map { it.index }.distinct().size == selectedChapterImages.size) {
                "Provider P2P chapter file indexes must be unique"
            }
            require(selectedChapterImages.map { it.path.lowercase() }.distinct().size == selectedChapterImages.size) {
                "Provider P2P chapter image paths must be unique"
            }
            var totalBytes = 0L
            for (page in selectedChapterImages) {
                require(page.index >= 0 && validRelativeImagePath(page.path) &&
                    page.path.substringBeforeLast('/', "") == chapterDirectory
                ) {
                    "Provider P2P chapter image path is invalid"
                }
                require(page.sizeBytes in 1L..MAX_IMAGE_BYTES &&
                    totalBytes <= MAX_CHAPTER_BYTES - page.sizeBytes
                ) {
                    "Provider P2P chapter images exceed verified size bounds"
                }
                totalBytes += page.sizeBytes
            }
        }
        require(
            infoHash == null || INFO_HASH.matches(infoHash),
        ) {
            "Provider P2P info hash is invalid"
        }
        require(
            magnetUri == null || (
                magnetUri.length <= MAX_URI_CHARS &&
                    magnetUri.startsWith("magnet:?", ignoreCase = true)
                ),
        ) {
            "Provider P2P magnet URI is invalid"
        }
        require(
            torrentUrl == null || validHttpUrl(torrentUrl),
        ) {
            "Provider P2P torrent URL is invalid"
        }
        require(
            !infoHash.isNullOrBlank() ||
                !magnetUri.isNullOrBlank() ||
                !torrentUrl.isNullOrBlank(),
        ) {
            "Provider P2P request requires a resolvable torrent identity"
        }
    }

    private companion object {
        const val MAX_URI_CHARS = 8 * 1024
        const val MAX_CHAPTER_PAGES = 400
        const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
        const val MAX_CHAPTER_BYTES = 256L * 1024L * 1024L
        val OPERATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val INFO_HASH = Regex("(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})")

        fun validHttpUrl(value: String): Boolean {
            if (value.length > MAX_URI_CHARS) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }

        fun validRelativeImagePath(value: String): Boolean =
            validRelativePath(value) &&
                value.substringAfterLast('.', "").lowercase() in
                setOf("jpg", "jpeg", "png", "webp", "gif")

        fun validRelativeArchivePath(value: String): Boolean =
            validRelativePath(value) &&
                value.substringAfterLast('.', "").lowercase() in setOf("cbz", "zip")

        private fun validRelativePath(value: String): Boolean {
            if (
                value.isBlank() ||
                value.length > 4 * 1024 ||
                value.startsWith("/") ||
                value.startsWith("\\") ||
                '\\' in value ||
                ':' in value ||
                '\u0000' in value
            ) {
                return false
            }
            if (value.split('/').any { it.isBlank() || it == "." || it == ".." }) {
                return false
            }
            return true
        }
    }
}

enum class ProviderP2pFailureCode {
    UNAVAILABLE,
    METADATA_UNAVAILABLE,
    FILE_MISMATCH,
    NETWORK_ERROR,
    STORAGE_ERROR,
    CANCELLED,
}

sealed interface ProviderP2pAcquireResponse {
    data class Pending(
        val jobId: String,
    ) : ProviderP2pAcquireResponse {
        init {
            require(JOB_ID.matches(jobId)) { "Provider P2P job ID is invalid" }
        }
    }

    data class Ready(
        val resource: String,
        val format: ProviderManagedResourceFormat,
    ) : ProviderP2pAcquireResponse {
        init {
            require(MANAGED_RESOURCE.matches(resource)) {
                "Provider P2P resource reference is invalid"
            }
        }
    }

    data class Failure(
        val reason: ProviderP2pFailureCode,
    ) : ProviderP2pAcquireResponse

    private companion object {
        val JOB_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")
        val MANAGED_RESOURCE = Regex("managed:[A-Za-z0-9_-]{1,256}")
    }
}

fun interface ProviderP2pHostService {
    suspend fun acquire(request: ProviderP2pAcquireRequest): ProviderP2pAcquireResponse
}

object ProviderP2pProtocol {
    const val MAX_REQUEST_JSON_CHARS = 24 * 1024
    const val MAX_RESPONSE_JSON_CHARS = 8 * 1024

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun encodeRequest(request: ProviderP2pAcquireRequest): String =
        json.encodeToString(
            RequestDto(
                operationId = request.operationId,
                magnetUri = request.magnetUri,
                torrentUrl = request.torrentUrl,
                infoHash = request.infoHash,
                selectedFileIndex = request.selectedFileIndex,
                selectedFilePath = request.selectedFilePath,
                selectedFileSizeBytes = request.selectedFileSizeBytes,
                selectedChapterImages = request.selectedChapterImages.map {
                    ChapterImageDto(it.index, it.path, it.sizeBytes)
                },
            ),
        ).also {
            requireBound(it, MAX_REQUEST_JSON_CHARS, "request")
        }

    fun decodeRequest(value: String): ProviderP2pAcquireRequest {
        requireBound(value, MAX_REQUEST_JSON_CHARS, "request")
        return try {
            val dto = json.decodeFromString<RequestDto>(value)
            ProviderP2pAcquireRequest(
                operationId = dto.operationId,
                magnetUri = dto.magnetUri,
                torrentUrl = dto.torrentUrl,
                infoHash = dto.infoHash,
                selectedFileIndex = dto.selectedFileIndex,
                selectedFilePath = dto.selectedFilePath,
                selectedFileSizeBytes = dto.selectedFileSizeBytes,
                selectedChapterImages = dto.selectedChapterImages.map {
                    ProviderP2pChapterImage(it.index, it.path, it.sizeBytes)
                },
            )
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider P2P request is malformed", error)
        }
    }

    fun encodeResponse(response: ProviderP2pAcquireResponse): String {
        val dto = when (response) {
            is ProviderP2pAcquireResponse.Pending ->
                ResponseDto(
                    status = "pending",
                    jobId = response.jobId,
                )
            is ProviderP2pAcquireResponse.Ready ->
                ResponseDto(
                    status = "ready",
                    resource = response.resource,
                    format = response.format.name,
                )
            is ProviderP2pAcquireResponse.Failure ->
                ResponseDto(
                    status = "failed",
                    failure = response.reason.name,
                )
        }
        return json.encodeToString(dto).also {
            requireBound(it, MAX_RESPONSE_JSON_CHARS, "response")
        }
    }

    fun decodeResponse(value: String): ProviderP2pAcquireResponse {
        requireBound(value, MAX_RESPONSE_JSON_CHARS, "response")
        return try {
            val dto = json.decodeFromString<ResponseDto>(value)
            when (dto.status) {
                "pending" -> ProviderP2pAcquireResponse.Pending(
                    dto.jobId ?: error("Pending P2P response is missing job ID"),
                )
                "ready" -> ProviderP2pAcquireResponse.Ready(
                    resource = dto.resource ?: error("Ready P2P response is missing resource"),
                    format = ProviderManagedResourceFormat.valueOf(
                        dto.format ?: error("Ready P2P response is missing format"),
                    ),
                )
                "failed" -> ProviderP2pAcquireResponse.Failure(
                    ProviderP2pFailureCode.valueOf(
                        dto.failure ?: error("Failed P2P response is missing failure reason"),
                    ),
                )
                else -> error("Unknown Provider P2P response status")
            }
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider P2P response is malformed", error)
        }
    }

    private fun requireBound(
        value: String,
        maxChars: Int,
        kind: String,
    ) {
        if (value.length > maxChars) {
            throw ProviderHostServiceException("Provider P2P $kind exceeds the IPC size limit")
        }
    }

    @Serializable
    private data class RequestDto(
        val operationId: String,
        val magnetUri: String? = null,
        val torrentUrl: String? = null,
        val infoHash: String? = null,
        val selectedFileIndex: Int,
        val selectedFilePath: String,
        val selectedFileSizeBytes: Long? = null,
        val selectedChapterImages: List<ChapterImageDto> = emptyList(),
    )

    @Serializable
    private data class ChapterImageDto(
        val index: Int,
        val path: String,
        val sizeBytes: Long,
    )

    @Serializable
    private data class ResponseDto(
        val status: String,
        val jobId: String? = null,
        val resource: String? = null,
        val format: String? = null,
        val failure: String? = null,
    )
}
