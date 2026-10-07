package eu.kanade.tachiyomi.provider.torrent

import com.frostwire.jlibtorrent.TorrentInfo
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.core.provider.runtime.ProviderNetworkPolicyException
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderError
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateMetadataGateway
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProviderTorrentCandidateMetadataGateway(
    networkHelper: NetworkHelper,
    private val registry: ProviderRegistry,
    private val maxMetadataBytes: Long = DEFAULT_MAX_METADATA_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
) : TorrentCandidateMetadataGateway {

    private val permits = Semaphore(maxConcurrentRequests)
    private val baseClient = networkHelper.client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(METADATA_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    init {
        require(maxMetadataBytes > 0L) { "Torrent metadata limit must be positive" }
        require(maxRedirects >= 0) { "Torrent metadata redirect limit must not be negative" }
        require(maxConcurrentRequests > 0) { "Torrent metadata concurrency must be positive" }
    }

    override suspend fun hydrate(
        providerId: ProviderId,
        candidate: TorrentCandidate,
    ): ProviderCallResult<TorrentCandidate> {
        if (candidate.files.orEmpty().isNotEmpty()) {
            return ProviderCallResult.Success(candidate)
        }
        val torrentUrl = candidate.torrentUrl
            ?: return ProviderCallResult.Success(candidate)

        registry.awaitReady()
        val registration = registry.registration(providerId)
            ?: return unavailable()
        if (registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED) {
            return unavailable()
        }
        val network = registration.descriptor.permissions.network
            ?: return failure(ProviderErrorCode.NETWORK_POLICY, retryable = false)
        if (network.origins.isEmpty()) {
            return failure(ProviderErrorCode.NETWORK_POLICY, retryable = false)
        }

        return permits.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val policy = ProviderNetworkPolicy(
                        allowedOrigins = network.origins,
                        allowLocalNetwork = network.localNetwork,
                    )
                    val bytes = fetch(
                        url = torrentUrl,
                        policy = policy,
                    )
                    val torrent = TorrentInfo(bytes)
                    val actualHash = verifiedInfoHash(candidate, torrent)
                        ?: return@withContext malformed()
                    val files = torrent.files()
                    if (files.numFiles() > MAX_TORRENT_FILES) {
                        return@withContext failure(
                            ProviderErrorCode.RESOURCE_LIMIT,
                            retryable = false,
                        )
                    }
                    val hydratedFiles = (0 until files.numFiles()).map { index ->
                        TorrentCandidateFile(
                            index = index,
                            path = files.filePath(index).replace('\\', '/'),
                            sizeBytes = files.fileSize(index),
                            languages = candidate.languages,
                        )
                    }
                    ProviderCallResult.Success(
                        candidate.copy(
                            infoHash = candidate.infoHash ?: actualHash,
                            files = hydratedFiles,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: ProviderNetworkPolicyException) {
                    failure(ProviderErrorCode.NETWORK_POLICY, retryable = false)
                } catch (error: IOException) {
                    error.findNetworkPolicyException()?.let {
                        return@withContext failure(
                            ProviderErrorCode.NETWORK_POLICY,
                            retryable = false,
                        )
                    }
                    failure(ProviderErrorCode.NETWORK_ERROR, retryable = true)
                } catch (_: MetadataLimitExceeded) {
                    failure(ProviderErrorCode.RESOURCE_LIMIT, retryable = false)
                } catch (_: Throwable) {
                    malformed()
                }
            }
        }
    }

    private fun fetch(
        url: String,
        policy: ProviderNetworkPolicy,
    ): ByteArray {
        val client = baseClient.newBuilder()
            .dns { host -> policy.resolvePublicAddresses(host) }
            .build()
        var current = policy.validate(url, resolveAddress = false)
        var redirects = 0

        while (true) {
            policy.validate(current.toString(), resolveAddress = true)
            val response = client.newCall(
                Request.Builder()
                    .url(current)
                    .get()
                    .build(),
            ).execute()

            response.use {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) {
                        throw IOException("Torrent metadata redirect limit exceeded")
                    }
                    redirects += 1
                    val location = response.header("Location")
                        ?: throw IOException("Torrent metadata redirect is missing Location")
                    val redirected = current.resolve(location)
                        ?: throw IOException("Torrent metadata redirect is invalid")
                    current = policy.validate(
                        redirected.toString(),
                        resolveAddress = false,
                    )
                    continue
                }
                if (!response.isSuccessful) {
                    throw IOException("Torrent metadata HTTP ${response.code}")
                }
                return response.body.readBounded(maxMetadataBytes)
            }
        }
    }

    private fun verifiedInfoHash(
        candidate: TorrentCandidate,
        torrent: TorrentInfo,
    ): String? {
        val expected = candidate.infoHash
        val actual = when (expected?.length) {
            40 -> torrent.infoHashV1()?.toHex()
            64 -> torrent.infoHashV2()?.toHex()
            null -> torrent.infoHashV1()?.toHex() ?: torrent.infoHashV2()?.toHex()
            else -> null
        } ?: return null

        return if (expected == null || actual.equals(expected, ignoreCase = true)) {
            actual.lowercase()
        } else {
            null
        }
    }

    private fun ResponseBody.readBounded(limit: Long): ByteArray {
        val declared = contentLength()
        if (declared > limit) throw MetadataLimitExceeded()

        return byteStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > limit) throw MetadataLimitExceeded()
                    output.write(buffer, 0, read)
                }
                if (total <= 0L) throw IOException("Torrent metadata response is empty")
                output.toByteArray()
            }
        }
    }

    private fun Throwable.findNetworkPolicyException(): ProviderNetworkPolicyException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is ProviderNetworkPolicyException) return current
            current = current.cause
        }
        return null
    }

    private fun unavailable() = failure(
        ProviderErrorCode.UNAVAILABLE,
        retryable = false,
    )

    private fun malformed() = failure(
        ProviderErrorCode.MALFORMED_RESULT,
        retryable = false,
    )

    private fun failure(
        code: ProviderErrorCode,
        retryable: Boolean,
    ): ProviderCallResult.Failure = ProviderCallResult.Failure(
        ProviderError(
            code = code,
            retryable = retryable,
        ),
    )

    private class MetadataLimitExceeded : RuntimeException()

    private companion object {
        const val DEFAULT_MAX_METADATA_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_REDIRECTS = 5
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 8
        const val METADATA_REQUEST_TIMEOUT_MS = 5_000L
        const val MAX_TORRENT_FILES = 20_000
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
