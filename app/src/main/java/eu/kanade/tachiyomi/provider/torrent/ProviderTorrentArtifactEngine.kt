package eu.kanade.tachiyomi.provider.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.tsuzuki.content.PreparedTorrentArtifact
import tachiyomi.domain.tsuzuki.content.TorrentArtifactEngine
import tachiyomi.domain.tsuzuki.content.TorrentArtifactRequest
import tachiyomi.domain.tsuzuki.provider.torrent.PrepareProviderTorrentForReader
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentReaderState
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionFailure
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

class ProviderTorrentArtifactEngine(
    private val prepareForReader: PrepareProviderTorrentForReader,
    private val preferences: ProviderTorrentPreferences,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val acquisitionTimeoutMs: Long = DEFAULT_ACQUISITION_TIMEOUT_MS,
) : TorrentArtifactEngine {

    init {
        require(pollIntervalMs in 100L..10_000L) {
            "Provider torrent polling interval is outside supported bounds"
        }
        require(acquisitionTimeoutMs in 1_000L..MAX_ACQUISITION_TIMEOUT_MS) {
            "Provider torrent acquisition timeout is outside supported bounds"
        }
    }

    override suspend fun acquire(
        request: TorrentArtifactRequest,
    ): Result<PreparedTorrentArtifact> {
        return try {
            val selectedIndex = request.fileIndex
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Provider torrent acquisition requires an explicit selected file index",
                    ),
                )
            val selectedPath = request.filePath
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Provider torrent acquisition requires an explicit selected file path",
                    ),
                )
            val selectedFile = TorrentCandidateFile(
                index = selectedIndex,
                path = selectedPath,
            )
            val providerRequest = TorrentAcquisitionRequest(
                operationId = operationId(request),
                candidate = TorrentCandidate(
                    infoHash = request.infoHash,
                    magnetUri = request.magnetUri,
                    torrentUrl = null,
                    displayName = request.infoHash,
                    files = listOf(selectedFile),
                ),
                selectedFile = selectedFile,
            )

            withTimeout(acquisitionTimeoutMs) {
                while (true) {
                    when (
                        val state = prepareForReader.prepare(
                            request = providerRequest,
                            preference = preferences.acquisitionPreference.get(),
                            directP2pAllowed = preferences.directP2pAllowed.get(),
                        )
                    ) {
                        is ProviderTorrentReaderState.Ready -> {
                            val content = state.content
                            if (content !is PreparedChapterContent.CanonicalDownload) {
                                return@withTimeout Result.failure(
                                    IllegalStateException(
                                        "Torrent Provider did not converge to a canonical local artifact",
                                    ),
                                )
                            }
                            return@withTimeout Result.success(
                                PreparedTorrentArtifact(
                                    localUri = content.uri,
                                    format = content.format,
                                ),
                            )
                        }

                        is ProviderTorrentReaderState.Pending ->
                            delay(pollIntervalMs)

                        is ProviderTorrentReaderState.Failure ->
                            return@withTimeout Result.failure(
                                ProviderTorrentAcquisitionException(state.reason),
                            )
                    }
                }

                @Suppress("UNREACHABLE_CODE")
                Result.failure<PreparedTorrentArtifact>(
                    IllegalStateException("Provider torrent acquisition loop terminated unexpectedly"),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun operationId(request: TorrentArtifactRequest): String =
        buildString {
            append("reader:")
            append(request.infoHash.take(24))
            append(':')
            append(request.fileIndex ?: 0)
        }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 500L
        const val DEFAULT_ACQUISITION_TIMEOUT_MS = 15L * 60L * 1000L
        const val MAX_ACQUISITION_TIMEOUT_MS = 60L * 60L * 1000L
    }
}

class ProviderTorrentAcquisitionException(
    val reason: TorrentAcquisitionFailure,
) : IllegalStateException("Provider torrent acquisition failed: " + reason.name)
