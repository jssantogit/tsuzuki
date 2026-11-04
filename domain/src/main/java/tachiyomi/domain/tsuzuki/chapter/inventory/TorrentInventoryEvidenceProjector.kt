package tachiyomi.domain.tsuzuki.chapter.inventory

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterMapper
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterPayloadMatch
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterRequest
import tachiyomi.domain.tsuzuki.provider.torrent.select
import java.security.MessageDigest

/**
 * Projects verified torrent file layouts into local release observations only.
 *
 * Torrent uploads do not attest publisher, edition, language, or editorial chapter
 * existence. Callers must opt in to the Provider; no work identity crosswalk is
 * fabricated and no canonical chapters, sync or Reader entries are written here.
 */
class TorrentInventoryEvidenceProjector {

    fun project(
        canonicalTitleId: String,
        displayTitle: String,
        providerId: ProviderId,
        candidates: List<TorrentCandidate>,
        language: String?,
        observedAt: Long,
    ): List<InventoryEvidenceBatch> {
        require(canonicalTitleId.isNotBlank() && displayTitle.isNotBlank())
        require(observedAt >= 0L)
        val mapper = TorrentChapterMapper()
        return candidates.asSequence()
            .take(MAX_TORRENTS_PER_SCAN)
            .filter { candidate -> !candidate.infoHash.isNullOrBlank() && !candidate.files.isNullOrEmpty() }
            .distinctBy { it.infoHash }
            .mapNotNull { candidate ->
                val infoHash = requireNotNull(candidate.infoHash)
                val identities = candidate.files.orEmpty().asSequence()
                    .filter { file -> file.path.length <= 4096 }
                    .flatMap { file ->
                        file.path.split('/').asSequence().mapNotNull { segment ->
                            val label = segment.substringBeforeLast('.', missingDelimiterValue = segment)
                            val chapter = CHAPTER_SEGMENT.matchEntire(label.trim()) ?: return@mapNotNull null
                            chapter.groupValues[1].toIntOrNull()
                        }
                    }
                    .filter { it in 0..MAX_CHAPTER_NUMBER }
                    .distinct()
                    .sorted()
                    .take(MAX_CHAPTERS_PER_TORRENT)
                    .toList()

                val claims = identities.mapNotNull { number ->
                    val request = TorrentChapterRequest(
                        identity = CanonicalChapterIdentity(
                            type = CanonicalChapterType.REGULAR,
                            baseNumber = number,
                        ),
                        volume = null,
                        titles = listOf(displayTitle),
                        chapterNumber = number.toString(),
                    )
                    val selection = mapper.select(request, candidate)
                    if (selection !is TorrentChapterPayloadMatch.Exact) return@mapNotNull null
                    InventoryClaim(
                        sourceRecordId = "torrent:sha256:" + sha256(
                            providerId.value + ":" + infoHash + ":" + number,
                        ),
                        label = number.toString(),
                        volumeLabel = null,
                        language = language,
                        kind = InventoryClaimKind.INDIVIDUAL,
                        derivation = InventoryDerivation.RELEASE_RECORD,
                        upstreamSourceId = providerId.value,
                        observedAt = observedAt,
                    )
                }
                if (claims.isEmpty()) return@mapNotNull null
                InventoryEvidenceBatch(
                    scope = InventoryScope(
                        canonicalTitleId = canonicalTitleId,
                        producerId = "provider:torrent:" + providerId.value,
                        externalWorkId = "release:sha256:" + sha256(infoHash),
                        editionKey = null,
                        language = language,
                        workIdentityVerified = false,
                        editionIdentityVerified = false,
                    ),
                    pageKey = "metadata-v1",
                    observedAt = observedAt,
                    outcome = InventoryFetchOutcome.PARTIAL,
                    claims = claims,
                )
            }
            .toList()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_TORRENTS_PER_SCAN = 200
        const val MAX_CHAPTERS_PER_TORRENT = 256
        const val MAX_CHAPTER_NUMBER = 10_000
        val CHAPTER_SEGMENT = Regex(
            "(?i)^(?:chapter|ch\\.?|cap[ií]tulo)\\s*0*(\\d+)$",
        )
    }
}
