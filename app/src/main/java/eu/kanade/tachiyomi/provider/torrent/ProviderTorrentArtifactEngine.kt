package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderP2pDiagnosticEvent
import eu.kanade.tachiyomi.provider.runtime.ProviderP2pDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
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
        val operationId = operationId(request)
        val startedAtNanos = System.nanoTime()
        var pendingPollCount = 0L
        var lastJobId: String? = null
        var lastProviderId: String? = null
        var lastRoute: String? = null

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
                operationId = operationId,
                candidate = TorrentCandidate(
                    infoHash = request.infoHash,
                    magnetUri = request.magnetUri,
                    torrentUrl = null,
                    displayName = request.infoHash,
                    files = listOf(selectedFile),
                ),
                selectedFile = selectedFile,
            )

            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_STATE,
                operationId = operationId,
                codes = mapOf(
                    "state" to "STARTED",
                    "preference" to preferences.acquisitionPreference.get().name,
                ),
                numbers = mapOf(
                    "selectedIndex" to selectedIndex.toLong(),
                    "pollIntervalMs" to pollIntervalMs,
                    "timeoutMs" to acquisitionTimeoutMs,
                ),
                flags = mapOf(
                    "directP2pAllowed" to preferences.directP2pAllowed.get(),
                    "hasInfoHash" to request.infoHash.isNotBlank(),
                    "hasMagnet" to !request.magnetUri.isNullOrBlank(),
                    "hasTorrentUrl" to false,
                ),
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
                            lastProviderId = state.providerId.value
                            lastRoute = state.route.name
                            val content = state.content
                            if (content !is PreparedChapterContent.CanonicalDownload) {
                                ProviderP2pDiagnostics.record(
                                    event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                                    operationId = operationId,
                                    jobId = lastJobId,
                                    providerId = lastProviderId,
                                    codes = mapOf(
                                        "route" to state.route.name,
                                        "reason" to "NON_CANONICAL_CONTENT",
                                    ),
                                    numbers = mapOf(
                                        "elapsedMs" to elapsedMillis(startedAtNanos),
                                        "pendingPollCount" to pendingPollCount,
                                    ),
                                    priority = LogPriority.ERROR,
                                )
                                return@withTimeout Result.failure(
                                    IllegalStateException(
                                        "Torrent Provider did not converge to a canonical local artifact",
                                    ),
                                )
                            }
                            ProviderP2pDiagnostics.record(
                                event = ProviderP2pDiagnosticEvent.READER_ARTIFACT_READY,
                                operationId = operationId,
                                jobId = lastJobId,
                                providerId = lastProviderId,
                                codes = mapOf(
                                    "route" to state.route.name,
                                    "format" to safeFormatCode(content.format),
                                ),
                                numbers = mapOf(
                                    "elapsedMs" to elapsedMillis(startedAtNanos),
                                    "pendingPollCount" to pendingPollCount,
                                ),
                            )
                            return@withTimeout Result.success(
                                PreparedTorrentArtifact(
                                    localUri = content.uri,
                                    format = content.format,
                                ),
                            )
                        }

                        is ProviderTorrentReaderState.Pending -> {
                            pendingPollCount += 1L
                            lastJobId = state.jobId
                            lastProviderId = state.providerId.value
                            lastRoute = state.route.name
                            if (shouldLogPendingPoll(pendingPollCount)) {
                                ProviderP2pDiagnostics.record(
                                    event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_STATE,
                                    operationId = operationId,
                                    jobId = state.jobId,
                                    providerId = state.providerId.value,
                                    codes = mapOf(
                                        "state" to "PENDING",
                                        "route" to state.route.name,
                                    ),
                                    numbers = mapOf(
                                        "pollCount" to pendingPollCount,
                                        "elapsedMs" to elapsedMillis(startedAtNanos),
                                    ),
                                )
                            }
                            delay(pollIntervalMs)
                        }

                        is ProviderTorrentReaderState.Failure -> {
                            ProviderP2pDiagnostics.record(
                                event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                                operationId = operationId,
                                jobId = lastJobId,
                                providerId = lastProviderId,
                                codes = buildMap {
                                    put("failure", state.reason.name)
                                    lastRoute?.let { put("route", it) }
                                },
                                numbers = mapOf(
                                    "elapsedMs" to elapsedMillis(startedAtNanos),
                                    "pendingPollCount" to pendingPollCount,
                                ),
                                priority = LogPriority.WARN,
                            )
                            return@withTimeout Result.failure(
                                ProviderTorrentAcquisitionException(state.reason),
                            )
                        }
                    }
                }

                @Suppress("UNREACHABLE_CODE")
                Result.failure<PreparedTorrentArtifact>(
                    IllegalStateException("Provider torrent acquisition loop terminated unexpectedly"),
                )
            }
        } catch (_: TimeoutCancellationException) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                operationId = operationId,
                jobId = lastJobId,
                providerId = lastProviderId,
                codes = buildMap {
                    put("failure", TorrentAcquisitionFailure.ACQUISITION_FAILED.name)
                    put("reason", "OUTER_TIMEOUT")
                    lastRoute?.let { put("route", it) }
                },
                numbers = mapOf(
                    "elapsedMs" to elapsedMillis(startedAtNanos),
                    "timeoutMs" to acquisitionTimeoutMs,
                    "pendingPollCount" to pendingPollCount,
                ),
                priority = LogPriority.ERROR,
            )
            Result.failure(
                ProviderTorrentAcquisitionException(
                    TorrentAcquisitionFailure.ACQUISITION_FAILED,
                ),
            )
        } catch (error: CancellationException) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.JOB_CANCELLED,
                operationId = operationId,
                jobId = lastJobId,
                providerId = lastProviderId,
                codes = buildMap {
                    put("stage", "READER_ACQUISITION")
                    lastRoute?.let { put("route", it) }
                },
                numbers = mapOf(
                    "elapsedMs" to elapsedMillis(startedAtNanos),
                    "pendingPollCount" to pendingPollCount,
                ),
                exceptionClass = error::class.qualifiedName,
                priority = LogPriority.WARN,
            )
            throw error
        } catch (error: Throwable) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.READER_ACQUISITION_FAILED,
                operationId = operationId,
                jobId = lastJobId,
                providerId = lastProviderId,
                codes = buildMap {
                    put("reason", "THREW")
                    lastRoute?.let { put("route", it) }
                },
                numbers = mapOf(
                    "elapsedMs" to elapsedMillis(startedAtNanos),
                    "pendingPollCount" to pendingPollCount,
                ),
                exceptionClass = error::class.qualifiedName,
                priority = LogPriority.ERROR,
            )
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

    private fun elapsedMillis(startedAtNanos: Long): Long =
        ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    private fun shouldLogPendingPoll(pollCount: Long): Boolean =
        pollCount in PENDING_POLL_MILESTONES || (pollCount >= 1_000L && pollCount % 1_000L == 0L)

    private fun safeFormatCode(format: String): String =
        format.uppercase()
            .takeIf { SAFE_CODE.matches(it) }
            ?: "UNKNOWN"

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 500L
        const val DEFAULT_ACQUISITION_TIMEOUT_MS = 15L * 60L * 1000L
        const val MAX_ACQUISITION_TIMEOUT_MS = 60L * 60L * 1000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val PENDING_POLL_MILESTONES = setOf(1L, 2L, 5L, 10L, 25L, 50L, 100L, 250L, 500L)
        val SAFE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}

class ProviderTorrentAcquisitionException(
    val reason: TorrentAcquisitionFailure,
) : IllegalStateException(
    when (reason) {
        TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED ->
            "Direct P2P consent is required. Enable Direct P2P in " +
                "Settings > Advanced > Provider torrent acquisition."
        else -> "Provider torrent acquisition failed: " + reason.name
    },
)
