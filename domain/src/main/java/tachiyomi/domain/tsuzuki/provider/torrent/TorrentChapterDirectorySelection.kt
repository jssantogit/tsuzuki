package tachiyomi.domain.tsuzuki.provider.torrent

import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel

/**
 * A verified torrent selection describes content, not an editorial chapter.
 * ImageDirectory selects the actual image files from metadata; a directory itself
 * never receives a synthetic torrent file index.
 */
sealed interface TorrentChapterPayload {
    data class SingleArchive(val file: TorrentCandidateFile) : TorrentChapterPayload

    data class ImageDirectory(
        val prefix: String,
        val files: List<TorrentCandidateFile>,
    ) : TorrentChapterPayload {
        init {
            require(prefix.isNotBlank() && files.isNotEmpty())
            require(files.all { it.path.startsWith("$prefix/") })
        }
    }
}

sealed interface TorrentChapterPayloadMatch {
    data object None : TorrentChapterPayloadMatch
    data class Exact(val payload: TorrentChapterPayload) : TorrentChapterPayloadMatch
    data class Ambiguous(val options: List<TorrentChapterPayload>) : TorrentChapterPayloadMatch {
        init {
            require(options.size > 1)
        }
    }
}

/**
 * Augments the existing fail-closed archive mapper with explicit chapter folders.
 * It is intentionally not connected to acquisition until the Host can download,
 * validate and package multiple image files in one P2P job.
 */
fun TorrentChapterMapper.select(
    request: TorrentChapterRequest,
    candidate: TorrentCandidate,
): TorrentChapterPayloadMatch {
    val archive = when (val match = map(request, candidate)) {
        TorrentChapterFileMatch.None -> emptyList()
        is TorrentChapterFileMatch.Exact -> listOf(TorrentChapterPayload.SingleArchive(match.file))
        is TorrentChapterFileMatch.Ambiguous ->
            match.files.map(TorrentChapterPayload::SingleArchive)
    }
    val directories = exactImageDirectories(request, candidate)
    val choices = archive + directories
    return when (choices.size) {
        0 -> TorrentChapterPayloadMatch.None
        1 -> TorrentChapterPayloadMatch.Exact(choices.single())
        else -> TorrentChapterPayloadMatch.Ambiguous(choices)
    }
}

private const val MAX_CHAPTER_IMAGES = 400
private const val MAX_IMAGE_BYTES = 32L * 1024L * 1024L
private const val MAX_CHAPTER_BYTES = 256L * 1024L * 1024L
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
private val chapterDirectoryMarker = Regex(
    "(?i)^(?:chapter|ch\\.?|cap[ií]tulo)\\s*0*\\d+(?:\\.\\d+|[a-z])?$",
)
private val volumeDirectoryMarker = Regex("(?i)^vol(?:ume)?\\.?\\s*(\\d+)$")
private val normalizedTokens = Regex("[\\p{L}\\p{N}]+")
private val naturalNumber = Regex("\\d+")

private fun exactImageDirectories(
    request: TorrentChapterRequest,
    candidate: TorrentCandidate,
): List<TorrentChapterPayload.ImageDirectory> {
    val all = candidate.files.orEmpty()
    if (all.isEmpty() || request.titles.isEmpty() || all.size > 20_000) return emptyList()
    if (all.any { !isSafeTorrentFilePath(it.path) }) return emptyList()

    return all.asSequence()
        .filter { file -> file.path.substringAfterLast('.').lowercase() in imageExtensions }
        .groupBy { file -> file.path.substringBeforeLast('/', missingDelimiterValue = "") }
        .mapNotNull { (prefix, pages) ->
            if (prefix.isBlank() || !directoryMatchesCanonicalChapter(prefix, request)) {
                return@mapNotNull null
            }
            if (pages.size > MAX_CHAPTER_IMAGES ||
                pages.map { it.path.lowercase() }.distinct().size != pages.size
            ) {
                return@mapNotNull null
            }
            var total = 0L
            for (page in pages) {
                val size = page.sizeBytes ?: return@mapNotNull null
                if (size <= 0L || size > MAX_IMAGE_BYTES || total > MAX_CHAPTER_BYTES - size) {
                    return@mapNotNull null
                }
                total += size
            }
            TorrentChapterPayload.ImageDirectory(
                prefix = prefix,
                files = pages.sortedWith(
                    compareBy<TorrentCandidateFile>({ naturalFileSortKey(it.path) }, { it.index }),
                ),
            )
        }
        .sortedBy(TorrentChapterPayload.ImageDirectory::prefix)
}

private fun directoryMatchesCanonicalChapter(
    prefix: String,
    request: TorrentChapterRequest,
): Boolean {
    val segments = prefix.split('/')
    if (segments.size < 2) return false
    val chapterSegment = segments.last()
    if (!chapterDirectoryMarker.matches(chapterSegment)) return false
    if (ParseCanonicalChapterLabel()(chapterSegment).identity != request.identity) return false

    val parents = segments.dropLast(1)
    val volumes = parents.mapNotNull { volumeDirectoryMarker.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
    if (volumes.distinct().size > 1) return false
    if (request.volume != null && volumes.singleOrNull() != request.volume) return false

    val workIndices = parents.indices.filter { index ->
        val normalizedParent = normalizeWorkLabel(parents[index])
        request.titles.any { title ->
            val normalizedTitle = normalizeWorkLabel(title)
            normalizedTitle.isNotBlank() &&
                (normalizedParent == normalizedTitle || normalizedParent == "$normalizedTitle manga")
        }
    }
    if (workIndices.size != 1) return false
    // Only transparent organizational folders are permitted beyond the verified work.
    return parents.indices.all { index ->
        index in workIndices ||
            volumeDirectoryMarker.matches(parents[index]) ||
            parents[index].lowercase() in setOf("chapters", "chapter", "pack", "files", "release")
    }
}

private fun normalizeWorkLabel(value: String): String =
    normalizedTokens.findAll(value.lowercase()).joinToString(" ") { it.value }

private fun naturalFileSortKey(path: String): String =
    naturalNumber.replace(path.substringAfterLast('/').lowercase()) { match ->
        match.value.padStart(12, '0')
    }

private fun isSafeTorrentFilePath(path: String): Boolean =
    path.isNotBlank() &&
        path.length <= 4096 &&
        !path.startsWith('/') &&
        !path.startsWith('\\') &&
        '\\' !in path &&
        ':' !in path &&
        '\u0000' !in path &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
