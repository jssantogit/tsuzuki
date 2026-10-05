package tachiyomi.domain.tsuzuki.provider.torrent

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

data class ProviderChapterTorrentOption(
    val canonicalChapterId: String,
    val providerId: ProviderId,
    val candidate: TorrentCandidate,
    val selectedFile: TorrentCandidateFile,
) {
    init {
        require(canonicalChapterId.isNotBlank()) { "Canonical chapter ID must not be blank" }
        require(candidate.files.orEmpty().any { it == selectedFile }) {
            "Selected torrent file must belong to the candidate"
        }
    }
}

fun ProviderChapterTorrentOption.toContentDelivery(): ContentDelivery.Torrent? {
    val infoHash = candidate.infoHash ?: return null
    return ContentDelivery.Torrent(
        infoHash = infoHash,
        magnetUri = candidate.magnetUri,
        fileIndex = selectedFile.index,
        filePath = selectedFile.path,
    )
}

/**
 * Discovers Provider torrent releases that can be mapped to one canonical chapter without guessing.
 *
 * Discovery is deliberately provider-neutral. A candidate is exposed only when the existing
 * canonical torrent mapper proves one exact readable archive file for the requested chapter.
 * Ambiguous packs, unnumbered chapters and candidates without a stable info hash remain unavailable.
 */
@Inject
class ResolveProviderChapterTorrent(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val providerRegistry: ProviderRegistry,
    private val gateway: TorrentSearchGateway,
) {

    suspend fun options(canonicalChapterId: String): List<ProviderChapterTorrentOption> {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return emptyList()
        if (!chapter.identity.isSpecific || !chapter.identity.isNumbered) return emptyList()
        val title = canonicalTitleRepository.getById(chapter.canonicalTitleId) ?: return emptyList()

        providerRegistry.awaitReady()
        val targets = providerRegistry.providers()
            .asSequence()
            .filter { it.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
            .filter { it.descriptor.runtime == ProviderRuntimeKind.SCRIPT }
            .filter { ProviderCapabilities.TorrentSearchV1 in it.enabledCapabilities }
            .map { it.descriptor.id }
            .sortedBy(ProviderId::value)
            .toList()
        if (targets.isEmpty()) return emptyList()

        val request = TorrentChapterRequest(
            identity = chapter.identity,
            volume = chapter.volume,
        )
        val mapper = TorrentChapterMapper()

        return targets
            .flatMap { providerId ->
                discover(
                    providerId = providerId,
                    title = title.displayTitle,
                    chapterNumber = chapter.displayNumber,
                    volume = chapter.volume,
                ).mapNotNull { candidate ->
                    if (candidate.infoHash == null) return@mapNotNull null
                    val file = when (val match = mapper.map(request, candidate)) {
                        is TorrentChapterFileMatch.Exact -> match.file
                        TorrentChapterFileMatch.None,
                        is TorrentChapterFileMatch.Ambiguous,
                        -> return@mapNotNull null
                    }
                    ProviderChapterTorrentOption(
                        canonicalChapterId = canonicalChapterId,
                        providerId = providerId,
                        candidate = candidate,
                        selectedFile = file,
                    )
                }
            }
            .distinct()
            .sortedWith(
                compareBy<ProviderChapterTorrentOption>(
                    { it.providerId.value },
                    { it.candidate.infoHash.orEmpty() },
                    { it.selectedFile.index },
                    { it.selectedFile.path },
                ),
            )
    }

    private suspend fun discover(
        providerId: ProviderId,
        title: String,
        chapterNumber: String,
        volume: Int?,
    ): List<TorrentCandidate> {
        return try {
            val items = mutableListOf<TorrentCandidate>()
            val seenCursors = mutableSetOf<String>()
            var cursor: ProviderCursor? = null
            var pageCount = 0
            while (true) {
                if (++pageCount > MAX_SEARCH_PAGES) return emptyList()
                val page = when (
                    val result = gateway.search(
                        providerId = providerId,
                        request = TorrentSearchRequest(
                            titles = listOf(title),
                            chapterNumber = chapterNumber,
                            volume = volume,
                            cursor = cursor,
                        ),
                    )
                ) {
                    is ProviderCallResult.Failure -> return emptyList()
                    is ProviderCallResult.Success -> result.value
                }
                items += page.items
                if (items.size > MAX_SEARCH_ITEMS) return emptyList()
                val next = page.nextCursor ?: break
                if (!seenCursors.add(next.value)) return emptyList()
                cursor = next
            }
            items
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private companion object {
        const val MAX_SEARCH_PAGES = 8
        const val MAX_SEARCH_ITEMS = 2_000
    }
}
