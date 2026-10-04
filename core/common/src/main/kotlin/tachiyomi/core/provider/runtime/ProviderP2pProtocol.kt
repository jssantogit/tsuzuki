package tachiyomi.core.provider.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

data class ProviderP2pAcquireRequest(
    val operationId: String,
    val magnetUri: String? = null,
    val torrentUrl: String? = null,
    val infoHash: String? = null,
    val selectedFileIndex: Int,
    val selectedFilePath: String,
    val selectedFileSizeBytes: Long? = null,
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
        require(validRelativeArchivePath(selectedFilePath)) {
            "Provider P2P selected file path is invalid"
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
                )
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
        val OPERATION_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val INFO_HASH = Regex("(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})")

        fun validHttpUrl(value: String): Boolean {
            if (value.length > MAX_URI_CHARS) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return (uri.scheme == "http" || uri.scheme == "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }

        fun validRelativeArchivePath(value: String): Boolean {
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
            val extension = value.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return extension == "cbz" || extension == "zip"
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
