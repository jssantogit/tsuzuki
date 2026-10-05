package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.artwork.model.TitleArtworkObservation
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.library.model.TitleFormatObservation
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.repository.TitleFormatObservationRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository
import kotlin.time.Clock

class MaterializeCanonicalTitleFromCatalog internal constructor(
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
    private val reportedChapterCountRepository: ReportedChapterCountRepository?,
    private val clock: () -> Long,
    private val titleFormatObservationRepository: TitleFormatObservationRepository? = null,
    private val titleArtworkRepository: TitleArtworkRepository? = null,
    private val titleNameObservationRepository: TitleNameObservationRepository? = null,
) {

    @Inject
    constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        reportedChapterCountRepository: ReportedChapterCountRepository,
        titleFormatObservationRepository: TitleFormatObservationRepository,
        titleArtworkRepository: TitleArtworkRepository,
        titleNameObservationRepository: TitleNameObservationRepository,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = reportedChapterCountRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = titleFormatObservationRepository,
        titleArtworkRepository = titleArtworkRepository,
        titleNameObservationRepository = titleNameObservationRepository,
    )

    constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        reportedChapterCountRepository: ReportedChapterCountRepository,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = reportedChapterCountRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = null,
        titleNameObservationRepository = null,
    )

    internal constructor(
        materializeCanonicalTitle: MaterializeCanonicalTitle,
    ) : this(
        materializeCanonicalTitle = materializeCanonicalTitle,
        reportedChapterCountRepository = null,
        clock = { Clock.System.now().toEpochMilliseconds() },
        titleFormatObservationRepository = null,
        titleNameObservationRepository = null,
    )

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        val title = materializeCanonicalTitle.fromCatalog(
            displayTitle = catalogItem.title,
            provider = catalogItem.provider,
            externalId = catalogItem.providerId,
            externalIds = catalogItem.externalIds,
        )
        val now = clock()
        reportedChapterCountRepository?.upsert(
            ReportedChapterCount(
                canonicalTitleId = title.id,
                provider = catalogItem.provider,
                chapterCount = catalogItem.chapterCount?.takeIf { it > 0 },
                updatedAt = now,
            ),
        )
        if (catalogItem.format != CatalogItemFormat.UNKNOWN) {
            titleFormatObservationRepository?.upsert(
                TitleFormatObservation(
                    canonicalTitleId = title.id,
                    provider = catalogItem.provider,
                    format = catalogItem.format,
                    updatedAt = now,
                ),
            )
        }
        persistTitleNames(
            title = title,
            catalogItem = catalogItem,
            updatedAt = now,
        )
        val coverUrl = catalogItem.coverUrl?.takeIf(String::isNotBlank)
        val bannerUrl = catalogItem.bannerUrl?.takeIf(String::isNotBlank)
        if (coverUrl != null || bannerUrl != null) {
            titleArtworkRepository?.upsert(
                TitleArtworkObservation(
                    canonicalTitleId = title.id,
                    provider = catalogItem.provider,
                    coverUrl = coverUrl,
                    bannerUrl = bannerUrl,
                    updatedAt = now,
                ),
            )
        }
        return title
    }

    private suspend fun persistTitleNames(
        title: CanonicalTitle,
        catalogItem: CatalogItem,
        updatedAt: Long,
    ) {
        val repository = titleNameObservationRepository ?: return
        val seen = linkedSetOf(normalizeForDiscovery(title.displayTitle))
        catalogItem.titles.forEach { (rawSourceKey, rawValue) ->
            val sourceKey = rawSourceKey.trim()
            val value = rawValue.trim()
            if (sourceKey.isEmpty() || value.isEmpty()) return@forEach
            if (!seen.add(normalizeForDiscovery(value))) return@forEach
            repository.upsert(
                TitleNameObservation(
                    canonicalTitleId = title.id,
                    provider = catalogItem.provider,
                    sourceKey = sourceKey,
                    value = value,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    private fun normalizeForDiscovery(value: String): String = value.trim().lowercase()
}
