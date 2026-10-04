package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.PrepareTorrentArtifact
import tachiyomi.domain.tsuzuki.content.TorrentArtifactEngine
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonChapterContentPreparer(
    private val canonicalReaderGateway: CanonicalReaderGateway,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val prepareTorrentArtifact: PrepareTorrentArtifact,
) : ChapterContentPreparer {

    internal constructor(
        canonicalReaderGateway: CanonicalReaderGateway,
    ) : this(
        canonicalReaderGateway = canonicalReaderGateway,
        canonicalDownloadRepository = EmptyCanonicalDownloadRepository,
        prepareTorrentArtifact = PrepareTorrentArtifact(UnsupportedTorrentArtifactEngine),
    )

    internal constructor(
        canonicalReaderGateway: CanonicalReaderGateway,
        torrentEngine: TorrentArtifactEngine,
    ) : this(
        canonicalReaderGateway = canonicalReaderGateway,
        canonicalDownloadRepository = EmptyCanonicalDownloadRepository,
        prepareTorrentArtifact = PrepareTorrentArtifact(torrentEngine),
    )

    override suspend fun prepare(
        option: ContentOption,
        progress: CanonicalChapterProgress?,
    ): Result<PreparedChapterContent> {
        return try {
            require(progress == null || progress.canonicalChapterId == option.canonicalChapterId) {
                "Canonical progress does not belong to content option chapter"
            }

            when (val delivery = option.delivery) {
                is ContentDelivery.Mihon -> {
                    canonicalReaderGateway.materialize(
                        canonicalChapterId = option.canonicalChapterId,
                        delivery = delivery,
                        progress = progress,
                    ).map { target ->
                        PreparedChapterContent.MihonOperational(
                            mangaId = target.mihonMangaId,
                            chapterId = target.mihonChapterId,
                            sourceId = target.sourceId,
                        )
                    }
                }

                is ContentDelivery.LocalArchive -> {
                    val artifact = canonicalDownloadRepository
                        .get(option.canonicalChapterId)
                        ?.takeIf { it.localUri == delivery.uri }
                    Result.success(
                        artifact?.let {
                            PreparedChapterContent.CanonicalDownload(
                                uri = it.localUri,
                                format = it.format,
                            )
                        } ?: PreparedChapterContent.LocalArchive(delivery.uri),
                    )
                }

                is ContentDelivery.LocalDirectory -> {
                    val artifact = canonicalDownloadRepository
                        .get(option.canonicalChapterId)
                        ?.takeIf { it.localUri == delivery.uri }
                    Result.success(
                        artifact?.let {
                            PreparedChapterContent.CanonicalDownload(
                                uri = it.localUri,
                                format = it.format,
                            )
                        } ?: PreparedChapterContent.LocalDirectory(delivery.uri),
                    )
                }

                is ContentDelivery.Torrent ->
                    prepareTorrentArtifact.execute(delivery)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private object UnsupportedTorrentArtifactEngine : TorrentArtifactEngine {
        override suspend fun acquire(
            request: tachiyomi.domain.tsuzuki.content.TorrentArtifactRequest,
        ) = Result.failure<tachiyomi.domain.tsuzuki.content.PreparedTorrentArtifact>(
            UnsupportedOperationException("Torrent artifact engine is unavailable"),
        )
    }

    private object EmptyCanonicalDownloadRepository : CanonicalDownloadRepository {
        override suspend fun get(canonicalChapterId: String) = null
        override suspend fun upsert(
            artifact: tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact,
        ) = Unit
        override suspend fun delete(canonicalChapterId: String) = Unit
        override suspend fun deleteOriginMetadata(
            addonId: tachiyomi.domain.tsuzuki.addon.AddonId,
        ) = Unit
    }
}
