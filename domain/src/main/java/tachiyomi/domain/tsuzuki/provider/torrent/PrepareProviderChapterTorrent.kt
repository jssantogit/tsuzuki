package tachiyomi.domain.tsuzuki.provider.torrent

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.content.PrepareTorrentArtifact
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

sealed interface ProviderChapterTorrentPreparation {
    data object Unavailable : ProviderChapterTorrentPreparation

    data class Ambiguous(
        val candidateCount: Int,
    ) : ProviderChapterTorrentPreparation {
        init {
            require(candidateCount > 1) { "Ambiguous Provider torrent preparation requires multiple candidates" }
        }
    }

    data class Ready(
        val providerId: ProviderId,
        val content: PreparedChapterContent,
    ) : ProviderChapterTorrentPreparation

    data class Failed(
        val providerId: ProviderId,
        val error: Throwable,
    ) : ProviderChapterTorrentPreparation
}

@Inject
class PrepareProviderChapterTorrent(
    private val resolver: ResolveProviderChapterTorrent,
    private val prepareTorrentArtifact: PrepareTorrentArtifact,
) {

    suspend fun prepare(
        canonicalChapterId: String,
        trace: DiagnosticTrace? = null,
    ): ProviderChapterTorrentPreparation {
        val options = resolver.options(canonicalChapterId, trace)
        if (options.isEmpty()) return ProviderChapterTorrentPreparation.Unavailable
        if (options.size > 1) {
            return ProviderChapterTorrentPreparation.Ambiguous(options.size)
        }

        val option = options.single()
        val delivery = option.toContentDelivery()
            ?: return ProviderChapterTorrentPreparation.Unavailable

        return try {
            prepareTorrentArtifact.execute(delivery).fold(
                onSuccess = { content ->
                    ProviderChapterTorrentPreparation.Ready(
                        providerId = option.providerId,
                        content = content,
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    ProviderChapterTorrentPreparation.Failed(
                        providerId = option.providerId,
                        error = error,
                    )
                },
            )
        } catch (error: CancellationException) {
            throw error
        }
    }
}
