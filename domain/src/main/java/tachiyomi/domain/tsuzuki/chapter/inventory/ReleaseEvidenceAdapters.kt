package tachiyomi.domain.tsuzuki.chapter.inventory

/**
 * Deliberately pure HTTP-response adapters. Clients must validate the exact
 * source work ID on EVERY page before passing records here. Release IDs never
 * become canonical chapter IDs; these observations are shadow-only.
 */
data class InventoryReleaseRecord(
    val releaseId: String,
    val chapterLabel: String?,
    val volumeLabel: String?,
    val language: String?,
    val upstreamSourceId: String?,
    val externalWorkId: String,
)

object MangaUpdatesReleaseEvidenceAdapter {
    fun adapt(
        scope: InventoryScope,
        pageKey: String,
        observedAt: Long,
        releases: List<InventoryReleaseRecord>,
        hasNextPage: Boolean,
    ): InventoryEvidenceBatch {
        require(scope.producerId == "mangaupdates")
        return releaseBatch(scope, pageKey, observedAt, releases, hasNextPage)
    }
}

object MangaDexReleaseEvidenceAdapter {
    fun adapt(
        scope: InventoryScope,
        pageKey: String,
        observedAt: Long,
        releases: List<InventoryReleaseRecord>,
        hasNextPage: Boolean,
    ): InventoryEvidenceBatch {
        require(scope.producerId == "mangadex")
        return releaseBatch(scope, pageKey, observedAt, releases, hasNextPage)
    }
}

private fun releaseBatch(
    scope: InventoryScope,
    pageKey: String,
    observedAt: Long,
    releases: List<InventoryReleaseRecord>,
    hasNextPage: Boolean,
): InventoryEvidenceBatch {
    require(scope.workIdentityVerified) { "Release observations require a verified exact work ID" }
    require(releases.all { it.releaseId.isNotBlank() })
    require(releases.all { it.externalWorkId == scope.externalWorkId }) {
        "Source page contains a release for a different work"
    }
    val stableReleases = releases.distinctBy(InventoryReleaseRecord::releaseId)
    require(
        stableReleases.size == releases.size || releases.groupBy { it.releaseId }.values.all { group ->
            group.distinct().size == 1
        },
    ) { "Conflicting releases reuse a source record ID" }

    return InventoryEvidenceBatch(
        scope = scope,
        pageKey = pageKey,
        observedAt = observedAt,
        outcome = if (hasNextPage) InventoryFetchOutcome.PARTIAL else InventoryFetchOutcome.EXHAUSTED,
        claims = stableReleases.map { release ->
            InventoryClaim(
                sourceRecordId = release.releaseId,
                label = release.chapterLabel,
                volumeLabel = release.volumeLabel,
                language = release.language,
                kind = InventoryClaimPolicy.classifyReleaseLabel(release.chapterLabel),
                derivation = InventoryDerivation.RELEASE_RECORD,
                upstreamSourceId = release.upstreamSourceId,
                observedAt = observedAt,
            )
        },
    )
}
