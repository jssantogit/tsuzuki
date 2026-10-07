package eu.kanade.tachiyomi.provider.torrent

import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
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

    private fun inspectInternal(
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
            val response = client.newCall(
                Request.Builder()
                    .url(current)
                    .get()
                    .build(),
            ).execute()

            try {
                if (response.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) return null
                    redirects += 1
                    val location = response.header("Location") ?: return null
                    val redirected = current.resolve(location) ?: return null
                    current = policy.validate(
                        redirected.toString(),
                        resolveAddress = false,
                    )
                    continue
                }

                if (!response.isSuccessful) return null
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxMetadataBytes) return null
                val bytes = body.readBounded() ?: return null
                if (bytes.isEmpty()) return null
                return decoder(bytes, candidate)
            } finally {
                response.close()
            }
        }
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
                if (!actualInfoHash.equals(expectedInfoHash, ignoreCase = true)) {
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
