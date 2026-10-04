package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.core.provider.runtime.ProviderNetworkPolicyException
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderError
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentHttpFileMaterializer
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ProviderTorrentHttpFileMaterializer internal constructor(
    baseClient: OkHttpClient,
    private val managedFiles: ProviderManagedFileStore,
    private val tempRoot: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
) : TorrentHttpFileMaterializer {

    private val baseClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(maxFileBytes > 0L) {
            "Provider torrent HTTP materialization limit must be positive"
        }
        require(maxRedirects >= 0) {
            "Provider torrent HTTP redirect limit must not be negative"
        }
        ensureDirectory(tempRoot)
    }

    override suspend fun materialize(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
        resource: TorrentReadableResource.HttpFile,
    ): ProviderCallResult<PreparedChapterContent.CanonicalDownload> =
        withContext(Dispatchers.IO) {
            val format = request.selectedFile.archiveFormat()
                ?: return@withContext malformed()
            val temp = File(
                tempRoot,
                UUID.randomUUID().toString() + "." + format.name.lowercase(),
            )

            try {
                materializeInternal(
                    providerId = providerId,
                    request = request,
                    resource = resource,
                    format = format,
                    temp = temp,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: ProviderNetworkPolicyException) {
                failure(
                    ProviderErrorCode.NETWORK_POLICY,
                    retryable = false,
                )
            } catch (_: ProviderHttpArchiveNetworkException) {
                failure(
                    ProviderErrorCode.NETWORK_ERROR,
                    retryable = true,
                )
            } catch (_: Throwable) {
                failure(
                    ProviderErrorCode.ACQUISITION_FAILED,
                    retryable = false,
                )
            } finally {
                temp.delete()
            }
        }

    private fun materializeInternal(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
        resource: TorrentReadableResource.HttpFile,
        format: TorrentArchiveFormat,
        temp: File,
    ): ProviderCallResult<PreparedChapterContent.CanonicalDownload> {
        val policy = ProviderNetworkPolicy(
            allowedOrigins = resource.allowedOrigins,
            allowLocalNetwork = resource.allowLocalNetwork,
        )
        val client = baseClient.newBuilder()
            .dns { host -> policy.resolvePublicAddresses(host) }
            .build()

        var current = policy.validate(resource.url, resolveAddress = false)
        var headers = resource.headers
        var redirects = 0

        while (true) {
            // Resolve immediately before the request and keep the same resolver on OkHttp so
            // redirects/DNS rebinding cannot silently escape the Provider network authority.
            policy.validate(current.toString(), resolveAddress = true)

            val response = try {
                client.newCall(
                    Request.Builder()
                        .url(current)
                        .apply {
                            headers.forEach { (name, value) ->
                                header(name, value)
                            }
                        }
                        .get()
                        .build(),
                ).execute()
            } catch (error: Throwable) {
                error.findNetworkPolicyException()?.let { throw it }
                throw ProviderHttpArchiveNetworkException(error)
            }

            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) {
                        return failure(
                            ProviderErrorCode.NETWORK_ERROR,
                            retryable = false,
                        )
                    }
                    redirects += 1

                    val location = response.header("Location")
                        ?: return malformed()
                    val redirected = current.resolve(location)
                        ?: return malformed()
                    val validated = policy.validate(
                        redirected.toString(),
                        resolveAddress = false,
                    )
                    if (!sameOrigin(current, validated)) {
                        // Never forward Provider secrets/custom headers across origins. A Provider
                        // that needs the target origin can resolve a final URL itself.
                        headers = emptyMap()
                    }
                    current = validated
                    continue
                }

                if (response.code == 401 || response.code == 403) {
                    return failure(
                        ProviderErrorCode.AUTH_REQUIRED,
                        retryable = false,
                    )
                }
                if (!response.isSuccessful) {
                    return failure(
                        ProviderErrorCode.NETWORK_ERROR,
                        retryable = response.code >= 500,
                    )
                }

                val body = response.body
                val declared = body.contentLength()
                if (declared > maxFileBytes) {
                    return failure(
                        ProviderErrorCode.RESOURCE_LIMIT,
                        retryable = false,
                    )
                }

                val copied = try {
                    body.streamTo(temp)
                } catch (_: MaterializationLimitExceeded) {
                    return failure(
                        ProviderErrorCode.RESOURCE_LIMIT,
                        retryable = false,
                    )
                } catch (error: Throwable) {
                    error.findNetworkPolicyException()?.let { throw it }
                    throw ProviderHttpArchiveNetworkException(error)
                }

                if (
                    copied <= 0L ||
                    (
                        request.selectedFile.sizeBytes != null &&
                            copied != request.selectedFile.sizeBytes
                        )
                ) {
                    return malformed()
                }

                val managedFormat = when (format) {
                    TorrentArchiveFormat.CBZ -> ProviderManagedResourceFormat.CBZ
                    TorrentArchiveFormat.ZIP -> ProviderManagedResourceFormat.ZIP
                }
                val token = try {
                    managedFiles.adoptFile(
                        providerId = providerId.value,
                        source = temp,
                        format = managedFormat,
                    )
                } catch (_: Throwable) {
                    return failure(
                        ProviderErrorCode.ACQUISITION_FAILED,
                        retryable = false,
                    )
                }

                val domainFormat = when (format) {
                    TorrentArchiveFormat.CBZ -> ProviderManagedFileFormat.CBZ
                    TorrentArchiveFormat.ZIP -> ProviderManagedFileFormat.ZIP
                }
                val uri = managedFiles.resolve(
                    providerId = providerId,
                    resource = ProviderManagedResourceRef(token),
                    format = domainFormat,
                ) ?: return malformed()

                return ProviderCallResult.Success(
                    PreparedChapterContent.CanonicalDownload(
                        uri = uri,
                        format = format.name,
                    ),
                )
            } finally {
                response.close()
            }
        }
    }

    private fun ResponseBody.streamTo(target: File): Long {
        return byteStream().use { input ->
            FileOutputStream(target).use { output ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue

                    total += read
                    if (total > maxFileBytes) {
                        throw MaterializationLimitExceeded()
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
                total
            }
        }
    }

    private fun TorrentCandidateFile.archiveFormat(): TorrentArchiveFormat? =
        when {
            path.endsWith(".cbz", ignoreCase = true) -> TorrentArchiveFormat.CBZ
            path.endsWith(".zip", ignoreCase = true) -> TorrentArchiveFormat.ZIP
            else -> null
        }

    private fun sameOrigin(
        first: HttpUrl,
        second: HttpUrl,
    ): Boolean =
        first.scheme == second.scheme &&
            first.host == second.host &&
            first.port == second.port

    private fun Throwable.findNetworkPolicyException(): ProviderNetworkPolicyException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is ProviderNetworkPolicyException) return current
            current = current.cause
        }
        return null
    }

    private fun malformed() =
        failure(
            ProviderErrorCode.MALFORMED_RESULT,
            retryable = false,
        )

    private fun failure(
        code: ProviderErrorCode,
        retryable: Boolean,
    ): ProviderCallResult.Failure =
        ProviderCallResult.Failure(
            ProviderError(
                code = code,
                retryable = retryable,
            ),
        )

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException(
                "Provider torrent HTTP materialization directory could not be created",
            )
        }
    }

    private class MaterializationLimitExceeded : RuntimeException()

    private class ProviderHttpArchiveNetworkException(
        cause: Throwable,
    ) : RuntimeException(cause)

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
