package eu.kanade.tachiyomi.provider.runtime

import com.frostwire.jlibtorrent.TorrentInfo
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate

/** Native-derived identity and file table; never supplied by Provider JS. */
internal data class VerifiedTorrentDescription(
    val infoHash: String,
    val files: List<VerifiedTorrentFile>,
)

internal data class VerifiedTorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
)

/**
 * Host-private, bounded and short-lived bridge between policy-checked HTTP .torrent
 * inspection and the post-consent native P2P worker. Never exported to JS or IPC.
 * A cache miss preserves the original magnet resolver. No disk persistence.
 */
internal class HostVerifiedTorrentMetadataCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxMetadataBytes: Int = 4 * 1024 * 1024,
    private val maxEntries: Int = 8,
    private val ttlMillis: Long = 5 * 60_000L,
    private val describe: (ByteArray) -> VerifiedTorrentDescription? = ::describeTorrent,
) {
    init {
        require(maxMetadataBytes in 1..4 * 1024 * 1024)
        require(maxEntries in 1..8)
        require(ttlMillis in 1..5 * 60_000L)
    }

    private data class Key(val providerId: String, val url: String, val infoHash: String)
    private data class Entry(
        val bytes: ByteArray,
        val description: VerifiedTorrentDescription,
        val createdAtMillis: Long,
    )

    private val entries = LinkedHashMap<Key, Entry>()

    @Synchronized
    fun offer(providerId: String, candidate: TorrentCandidate, bytes: ByteArray): Boolean {
        val hash = candidate.infoHash?.takeIf { V1_HASH.matches(it) }?.lowercase() ?: return false
        val url = candidate.torrentUrl ?: return false
        val files = candidate.files ?: return false
        if (providerId.isBlank() || bytes.isEmpty() || bytes.size > maxMetadataBytes) return false
        val description = runCatching { describe(bytes) }.getOrNull() ?: return false
        if (!hash.equals(description.infoHash, ignoreCase = true)) return false
        if (files.size != description.files.size || files.isEmpty()) return false
        if (files.any { file ->
                description.files.none {
                    it.index == file.index && it.path == file.path && it.sizeBytes == file.sizeBytes
                }
            }
        ) return false

        val now = clock()
        prune(now)
        val key = Key(providerId, url, hash)
        entries.remove(key)
        entries[key] = Entry(bytes.copyOf(), description, now)
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        return true
    }

    /**
     * Executed only after Host consent/Provider policy routes to an authenticated P2P
     * service. Revalidates the selected file and torrent identity before handing a
     * defensive copy to the privileged engine, which rechecks native identity/path.
     */
    @Synchronized
    fun find(providerId: String, request: ProviderP2pAcquireRequest): ByteArray? {
        val hash = request.infoHash?.takeIf { V1_HASH.matches(it) }?.lowercase() ?: return null
        val url = request.torrentUrl ?: return null
        val size = request.selectedFileSizeBytes ?: return null
        if (request.magnetUri != null) {
            val xts = BTIH.findAll(request.magnetUri).map { it.groupValues[1] }.toList()
            if (xts.size != 1 || !xts.single().equals(hash, ignoreCase = true)) return null
        }
        val now = clock()
        prune(now)
        val item = entries[Key(providerId, url, hash)] ?: return null
        if (
            item.description.files.none {
                it.index == request.selectedFileIndex &&
                    it.path == request.selectedFilePath &&
                    it.sizeBytes == size
            }
        ) return null
        return item.bytes.copyOf()
    }

    private fun prune(now: Long) {
        entries.entries.removeAll { (_, entry) ->
            val age = now - entry.createdAtMillis
            age < 0 || age >= ttlMillis
        }
    }

    private companion object {
        val V1_HASH = Regex("[a-fA-F0-9]{40}")
        val BTIH = Regex("(?i)(?:[?&])xt=urn:btih:([a-f0-9]{40})(?:[&#]|$)")

        fun describeTorrent(bytes: ByteArray): VerifiedTorrentDescription? {
            val torrent = TorrentInfo(bytes)
            val hash = torrent.infoHashV1()?.toHex() ?: return null
            val storage = torrent.files()
            val count = storage.numFiles()
            if (count !in 1..20_000) return null
            return VerifiedTorrentDescription(
                infoHash = hash,
                files = (0 until count).map { index ->
                    VerifiedTorrentFile(
                        index = index,
                        path = storage.filePath(index).replace('\\', '/'),
                        sizeBytes = storage.fileSize(index),
                    )
                },
            )
        }
    }
}
