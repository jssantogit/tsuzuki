package eu.kanade.tachiyomi.provider.torrent

import com.frostwire.jlibtorrent.TorrentInfo
import eu.kanade.tachiyomi.provider.runtime.HostVerifiedTorrentMetadataCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import java.io.ByteArrayOutputStream

class HttpProviderTorrentMetadataInspector internal constructor(
    baseClient: OkHttpClient,
    private val maxMetadataBytes: Long = DEFAULT_MAX_METADATA_BYTES,
    private val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    private val decoder: (ByteArray, TorrentCandidate) -> TorrentCandidate? = ::decodeTorrentMetadata,
    private val verifiedMetadata: HostVerifiedTorrentMetadataCache? = null,
) : ProviderTorrentMetadataInspector {

    private val baseClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(maxMetadataBytes > 0L) {
            "Provider torrent metadata limit must be positive"
        }
        require(maxRedirects >= 0) {
            "Provider torrent metadata redirect limit must not be negative"
        }
    }

    override suspend fun inspect(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate? = withContext(Dispatchers.IO) {
        try {
            inspectInternal(descriptor, candidate)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun inspectInternal(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate? {
        val torrentUrl = candidate.torrentUrl ?: return null
        val permission = descriptor.permissions.network ?: return null
        val policy = ProviderNetworkPolicy(
            allowedOrigins = permission.origins,
            allowLocalNetwork = permission.localNetwork,
        )
        val client = baseClient.newBuilder()
            .dns { host -> policy.resolvePublicAddresses(host) }
            .build()

        var current = policy.validate(torrentUrl, resolveAddress = false)
        var redirects = 0

        while (true) {
            policy.validate(current.toString(), resolveAddress = true)
            val result = requestMetadata(client, current.toString())

            if (result.code in REDIRECT_CODES) {
                if (redirects >= maxRedirects) return null
                redirects += 1
                val location = result.location ?: return null
                val redirected = current.resolve(location) ?: return null
                current = policy.validate(
                    redirected.toString(),
                    resolveAddress = false,
                )
                continue
            }

            if (result.code !in 200..299) return null
            val bytes = result.bytes ?: return null
            if (bytes.isEmpty()) return null
            val inspected = decoder(bytes, candidate) ?: return null
            // Only a Host-validated file table can populate the privileged cache.
            // The cache independently revalidates the native info hash and every file.
            runCatching { verifiedMetadata?.offer(descriptor.id.value, inspected, bytes) }
            return inspected
        }
    }

    private data class MetadataResponse(
        val code: Int,
        val location: String? = null,
        val bytes: ByteArray? = null,
    )

    // The Reader owns a strict fallback deadline. A blocking OkHttp execute()
    // inside Dispatchers.IO does not cooperate with coroutine cancellation:
    // withTimeoutOrNull then waits for its children, sometimes for a minute.
    // Keep the entire response-body read on OkHttp's callback thread so that
    // cancelling the coroutine cancels the HTTP call, including a stalled body.
    // Only fully materialized, bounded bytes cross the continuation; Response
    // is closed even when cancellation races with onResponse.
    private suspend fun requestMetadata(
        client: OkHttpClient,
        url: String,
    ): MetadataResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build(),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { reply ->
                            if (reply.code in REDIRECT_CODES) {
                                MetadataResponse(code = reply.code, location = reply.header("Location"))
                            } else if (!reply.isSuccessful || reply.body.contentLength() > maxMetadataBytes) {
                                MetadataResponse(code = reply.code)
                            } else {
                                MetadataResponse(code = reply.code, bytes = reply.body.readBounded())
                            }
                        }
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(result))
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }
                }
            },
        )
    }

    private fun ResponseBody.readBounded(): ByteArray? =
        byteStream().use { input ->
            ByteArrayOutputStream().use { output ->
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxMetadataBytes) return null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

    private companion object {
        const val DEFAULT_MAX_METADATA_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_MAX_REDIRECTS = 5
        const val MAX_FILES = 20_000
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

        fun decodeTorrentMetadata(
            bytes: ByteArray,
            candidate: TorrentCandidate,
        ): TorrentCandidate? {
            val torrent = TorrentInfo(bytes)
            val expectedInfoHash = candidate.infoHash
            if (expectedInfoHash != null) {
                val actualInfoHash = when (expectedInfoHash.length) {
                    40 -> torrent.infoHashV1()?.toHex()
                    64 -> torrent.infoHashV2()?.toHex()
                    else -> null
                }
                if (actualInfoHash?.equals(expectedInfoHash, ignoreCase = true) != true) {
                    return null
                }
            }

            val storage = torrent.files()
            val count = storage.numFiles()
            if (count <= 0 || count > MAX_FILES) return null

            val files = buildList(count) {
                repeat(count) { index ->
                    add(
                        TorrentCandidateFile(
                            index = index,
                            path = storage.filePath(index).replace('\\', '/'),
                            sizeBytes = storage.fileSize(index),
                            languages = candidate.languages,
                        ),
                    )
                }
            }
            return candidate.copy(files = files)
        }
    }
}
