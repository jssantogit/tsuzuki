package eu.kanade.tachiyomi.provider.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentHttpFileMaterializer
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
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
                    } catch (error: ProviderNetworkPolicyException) {
                        return@withContext failure(
                            ProviderErrorCode.NETWORK_POLICY,
                            retryable = false,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        return@withContext failure(
                            ProviderErrorCode.NETWORK_ERROR,
                            retryable = true,
                        )
                    }

                    response.use { value ->
                        if (value.code in REDIRECT_CODES) {
                            if (redirects >= maxRedirects) {
                                return@withContext failure(
                                    ProviderErrorCode.NETWORK_ERROR,
                                    retryable = false,
                                )
                            }
                            redirects += 1

                            val location = value.header("Location")
                                ?: return@withContext malformed()
                            val redirected = current.resolve(location)
                                ?: return@withContext malformed()
                            val validated = try {
                                policy.validate(
                                    redirected.toString(),
                                    resolveAddress = false,
                                )
                            } catch (_: ProviderNetworkPolicyException) {
                                return@withContext failure(
                                    ProviderErrorCode.NETWORK_POLICY,
                                    retryable = false,
                                )
                            }
                            if (!sameOrigin(current, validated)) {
                                headers = emptyMap()
                            }
                            current = validated
                            continue
                        }

                        if (value.code == 401 || value.code == 403) {
                            return@withContext failure(
                                ProviderErrorCode.AUTH_REQUIRED,
                                retryable = false,
                            )
                        }
                        if (!value.isSuccessful) {
                            return@withContext failure(
                                ProviderErrorCode.NETWORK_ERROR,
                                retryable = value.code >= 500,
                            )
                        }

                        val body = value.body
                            ?: return@withContext malformed()
                        val declared = body.contentLength()
                        if (declared > maxFileBytes) {
                            return@withContext failure(
                                ProviderErrorCode.RESOURCE_LIMIT,
                                retryable = false,
                            )
                        }

                        val copied = try {
                            body.byteStream().use { input ->
                                FileOutputStream(temp).use { output ->
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
                        } catch (_: MaterializationLimitExceeded) {
                            return@withContext failure(
                                ProviderErrorCode.RESOURCE_LIMIT,
                                retryable = false,
                            )
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            return@withContext failure(
                                ProviderErrorCode.NETWORK_ERROR,
                                retryable = true,
                            )
                        }

                        if (
                            copied <= 0L ||
                            (
                                request.selectedFile.sizeBytes != null &&
                                    copied != request.selectedFile.sizeBytes
                                )
                        ) {
                            return@withContext malformed()
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
                            return@withContext failure(
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
                        ) ?: return@withContext malformed()

                        return@withContext ProviderCallResult.Success(
                            PreparedChapterContent.CanonicalDownload(
                                uri = uri,
                                format = format.name,
                            ),
                        )
                    }
                }

                @Suppress("UNREACHABLE_CODE")
                malformed()
            } catch (error: ProviderNetworkPolicyException) {
                failure(
                    ProviderErrorCode.NETWORK_POLICY,
                    retryable = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                failure(
                    ProviderErrorCode.ACQUISITION_FAILED,
                    retryable = false,
                )
            } finally {
                temp.delete()
            }
        }

    private fun tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile.archiveFormat():
        TorrentArchiveFormat? =
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

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
