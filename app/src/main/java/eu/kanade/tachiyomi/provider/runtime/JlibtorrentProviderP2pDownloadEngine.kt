package eu.kanade.tachiyomi.provider.runtime

import android.os.Build
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TcpEndpoint
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import java.io.File
import kotlin.coroutines.coroutineContext

class JlibtorrentProviderP2pDownloadEngine internal constructor(
    private val metadataResolver: suspend (
        session: SessionManager,
        request: ProviderP2pAcquireRequest,
        workingDirectory: File,
    ) -> TorrentInfo? = DEFAULT_METADATA_RESOLVER,
    private val initialPeers: (ProviderP2pAcquireRequest) -> List<TcpEndpoint> = { emptyList() },
    private val sessionParamsFactory: () -> SessionParams = DEFAULT_SESSION_PARAMS_FACTORY,
    private val downloadTimeoutMs: Long = DEFAULT_DOWNLOAD_TIMEOUT_MS,
    private val nativeSupport: () -> Boolean = JlibtorrentNativeSupport::isCurrentRuntimeSupported,
    private val sessionManagerFactory: () -> SessionManager = { SessionManager() },
) : ProviderP2pDownloadEngine {

    constructor() : this(
        metadataResolver = DEFAULT_METADATA_RESOLVER,
        initialPeers = { emptyList() },
        sessionParamsFactory = DEFAULT_SESSION_PARAMS_FACTORY,
        downloadTimeoutMs = DEFAULT_DOWNLOAD_TIMEOUT_MS,
        nativeSupport = JlibtorrentNativeSupport::isCurrentRuntimeSupported,
        sessionManagerFactory = { SessionManager() },
    )

    init {
        require(downloadTimeoutMs in 1_000L..MAX_DOWNLOAD_TIMEOUT_MS) {
            "Provider P2P download timeout is outside supported bounds"
        }
    }

    override suspend fun download(
        request: ProviderP2pAcquireRequest,
        workingDirectory: File,
    ): ProviderP2pDownloadResult = withContext(Dispatchers.IO) {
        val nativeSupported = nativeSupport()
        ProviderP2pDiagnostics.record(
            event = ProviderP2pDiagnosticEvent.NATIVE_SUPPORT,
            operationId = request.operationId,
            flags = mapOf("nativeSupported" to nativeSupported),
            codes = mapOf("abi" to currentAbiCode()),
            priority = if (nativeSupported) LogPriority.INFO else LogPriority.WARN,
        )
        if (!nativeSupported) {
            return@withContext ProviderP2pDownloadResult.Failure(
                ProviderP2pFailureCode.UNAVAILABLE,
            )
        }

        ensureDirectory(workingDirectory)

        val session = sessionManagerFactory()
        var started = false
        var currentStage = "SESSION_START"
        try {
            val sessionStartedAt = System.nanoTime()
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.SESSION_START,
                operationId = request.operationId,
                flags = mapOf(
                    "hasInitialPeers" to initialPeers(request).isNotEmpty(),
                    "lsdDisabledByDefault" to true,
                ),
            )
            session.start(sessionParamsFactory())
            started = true
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.SESSION_READY,
                operationId = request.operationId,
                numbers = mapOf("elapsedMs" to elapsedMillis(sessionStartedAt)),
            )

            currentStage = "METADATA"
            val metadataStartedAt = System.nanoTime()
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.METADATA_START,
                operationId = request.operationId,
                codes = mapOf("source" to metadataSource(request)),
                numbers = mapOf("timeoutMs" to METADATA_TIMEOUT_SECONDS * 1_000L),
                flags = mapOf(
                    "hasInfoHash" to !request.infoHash.isNullOrBlank(),
                    "hasMagnet" to !request.magnetUri.isNullOrBlank(),
                    "hasTorrentUrl" to !request.torrentUrl.isNullOrBlank(),
                ),
            )
            val torrent = metadataResolver(session, request, workingDirectory)
            if (torrent == null) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.METADATA_FAILED,
                    operationId = request.operationId,
                    codes = mapOf(
                        "source" to metadataSource(request),
                        "failure" to ProviderP2pFailureCode.METADATA_UNAVAILABLE.name,
                    ),
                    numbers = mapOf("elapsedMs" to elapsedMillis(metadataStartedAt)),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                )
            }
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.METADATA_READY,
                operationId = request.operationId,
                codes = mapOf("source" to metadataSource(request)),
                numbers = mapOf(
                    "elapsedMs" to elapsedMillis(metadataStartedAt),
                    "fileCount" to torrent.files().numFiles().toLong(),
                ),
                flags = mapOf(
                    "hasV1" to (torrent.infoHashV1() != null),
                    "hasV2" to (torrent.infoHashV2() != null),
                ),
            )

            currentStage = "IDENTITY_CHECK"
            validateIdentity(request, torrent)?.let { failure ->
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.IDENTITY_CHECK,
                    operationId = request.operationId,
                    codes = mapOf("failure" to failure.name),
                    flags = mapOf("matched" to false),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(failure)
            }
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.IDENTITY_CHECK,
                operationId = request.operationId,
                flags = mapOf("matched" to true),
            )

            currentStage = "FILE_SELECTION"
            val files = torrent.files()
            val selectedIndex = request.selectedFileIndex
            if (selectedIndex !in 0 until files.numFiles()) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.FILE_SELECTION_CHECK,
                    operationId = request.operationId,
                    numbers = mapOf(
                        "selectedIndex" to selectedIndex.toLong(),
                        "fileCount" to files.numFiles().toLong(),
                    ),
                    flags = mapOf("indexMatched" to false),
                    codes = mapOf("failure" to ProviderP2pFailureCode.FILE_MISMATCH.name),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val selectedPath = files.filePath(selectedIndex)
                .replace('\\', '/')
            val pathMatched = selectedPath == request.selectedFilePath
            if (!pathMatched) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.FILE_SELECTION_CHECK,
                    operationId = request.operationId,
                    numbers = mapOf("selectedIndex" to selectedIndex.toLong()),
                    flags = mapOf(
                        "indexMatched" to true,
                        "pathMatched" to false,
                    ),
                    codes = mapOf("failure" to ProviderP2pFailureCode.FILE_MISMATCH.name),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val selectedSize = files.fileSize(selectedIndex)
            val sizeMatched = request.selectedFileSizeBytes == null ||
                request.selectedFileSizeBytes == selectedSize
            if (!sizeMatched) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.FILE_SELECTION_CHECK,
                    operationId = request.operationId,
                    numbers = mapOf(
                        "selectedIndex" to selectedIndex.toLong(),
                        "metadataBytes" to selectedSize.coerceAtLeast(0L),
                        "expectedBytes" to request.selectedFileSizeBytes.orZero(),
                    ),
                    flags = mapOf(
                        "indexMatched" to true,
                        "pathMatched" to true,
                        "sizeMatched" to false,
                    ),
                    codes = mapOf("failure" to ProviderP2pFailureCode.FILE_MISMATCH.name),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val format = when {
                selectedPath.endsWith(".cbz", ignoreCase = true) ->
                    ProviderManagedResourceFormat.CBZ
                selectedPath.endsWith(".zip", ignoreCase = true) ->
                    ProviderManagedResourceFormat.ZIP
                else -> {
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.FILE_SELECTION_CHECK,
                        operationId = request.operationId,
                        numbers = mapOf("selectedIndex" to selectedIndex.toLong()),
                        flags = mapOf(
                            "indexMatched" to true,
                            "pathMatched" to true,
                            "sizeMatched" to true,
                        ),
                        codes = mapOf(
                            "format" to "UNSUPPORTED",
                            "failure" to ProviderP2pFailureCode.FILE_MISMATCH.name,
                        ),
                        priority = LogPriority.WARN,
                    )
                    return@withContext ProviderP2pDownloadResult.Failure(
                        ProviderP2pFailureCode.FILE_MISMATCH,
                    )
                }
            }
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.FILE_SELECTION_CHECK,
                operationId = request.operationId,
                codes = mapOf("format" to format.name),
                numbers = mapOf(
                    "selectedIndex" to selectedIndex.toLong(),
                    "metadataBytes" to selectedSize.coerceAtLeast(0L),
                ),
                flags = mapOf(
                    "indexMatched" to true,
                    "pathMatched" to true,
                    "sizeMatched" to true,
                ),
            )

            currentStage = "DOWNLOAD_SUBMIT"
            val downloadRoot = File(workingDirectory, DOWNLOAD_DIRECTORY)
            ensureDirectory(downloadRoot)

            val priorities = Priority.array(Priority.IGNORE, torrent.numFiles())
            priorities[selectedIndex] = Priority.NORMAL
            val peers = initialPeers(request)

            session.download(
                torrent,
                downloadRoot,
                null,
                priorities,
                peers,
                TorrentFlags.PAUSED,
            )
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.DOWNLOAD_SUBMITTED,
                operationId = request.operationId,
                numbers = mapOf(
                    "selectedIndex" to selectedIndex.toLong(),
                    "fileCount" to torrent.numFiles().toLong(),
                    "initialPeerCount" to peers.size.toLong(),
                ),
            )

            currentStage = "HANDLE_WAIT"
            val handleStartedAt = System.nanoTime()
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.HANDLE_WAIT,
                operationId = request.operationId,
                numbers = mapOf("timeoutMs" to HANDLE_TIMEOUT_MS),
            )
            val handle: TorrentHandle = try {
                withTimeout(HANDLE_TIMEOUT_MS) {
                    var resolved: TorrentHandle? = null
                    while (resolved == null) {
                        coroutineContext.ensureActive()
                        resolved = session.find(torrent)
                        if (resolved == null) {
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                    requireNotNull(resolved)
                }
            } catch (_: TimeoutCancellationException) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.HANDLE_FAILED,
                    operationId = request.operationId,
                    codes = mapOf(
                        "failure" to ProviderP2pFailureCode.UNAVAILABLE.name,
                        "reason" to "TIMEOUT",
                    ),
                    numbers = mapOf(
                        "elapsedMs" to elapsedMillis(handleStartedAt),
                        "timeoutMs" to HANDLE_TIMEOUT_MS,
                    ),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.UNAVAILABLE,
                )
            }
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.HANDLE_READY,
                operationId = request.operationId,
                numbers = mapOf("elapsedMs" to elapsedMillis(handleStartedAt)),
            )

            handle.prioritizeFiles(priorities)
            handle.resume()

            currentStage = "TRANSFER"
            var latestWanted = 0L
            var latestDone = 0L
            var latestPeers = 0
            var latestSeeds = 0
            var latestRate = 0L
            var lastDone = 0L
            var lastProgressAt = System.nanoTime()
            var lastProgressBucket = -1
            var firstPeerObserved = false
            var firstByteObserved = false

            val completedWithoutError: Boolean = try {
                withTimeout(downloadTimeoutMs) {
                    var completed: Boolean? = null
                    while (completed == null) {
                        coroutineContext.ensureActive()
                        val status = handle.status(true)
                        val now = System.nanoTime()
                        val errorValue = status.errorCode().value()
                        latestWanted = status.totalWanted().coerceAtLeast(0L)
                        latestDone = status.totalWantedDone().coerceAtLeast(0L)
                        latestPeers = status.numPeers().coerceAtLeast(0)
                        latestSeeds = status.numSeeds().coerceAtLeast(0)
                        latestRate = status.downloadRate().toLong().coerceAtLeast(0L)

                        if (latestDone > lastDone) {
                            lastDone = latestDone
                            lastProgressAt = now
                        }
                        val progressPercent = progressPercent(latestDone, latestWanted)
                        val bucket = progressBucket(progressPercent)
                        val peerTransition = latestPeers > 0 && !firstPeerObserved
                        val byteTransition = latestDone > 0L && !firstByteObserved
                        if (peerTransition) firstPeerObserved = true
                        if (byteTransition) firstByteObserved = true

                        if (bucket != lastProgressBucket || peerTransition || byteTransition) {
                            lastProgressBucket = bucket
                            ProviderP2pDiagnostics.record(
                                event = ProviderP2pDiagnosticEvent.TRANSFER_PROGRESS,
                                operationId = request.operationId,
                                numbers = mapOf(
                                    "wantedBytes" to latestWanted,
                                    "doneBytes" to latestDone,
                                    "progressPercent" to progressPercent.toLong(),
                                    "peerCount" to latestPeers.toLong(),
                                    "seedCount" to latestSeeds.toLong(),
                                    "downloadRate" to latestRate,
                                    "stalledMs" to elapsedMillis(lastProgressAt, now),
                                ),
                                flags = mapOf(
                                    "finished" to status.isFinished,
                                    "firstPeerObserved" to firstPeerObserved,
                                    "firstByteObserved" to firstByteObserved,
                                ),
                            )
                        }

                        if (errorValue != 0) {
                            ProviderP2pDiagnostics.record(
                                event = ProviderP2pDiagnosticEvent.TRANSFER_FAILED,
                                operationId = request.operationId,
                                codes = mapOf("failure" to ProviderP2pFailureCode.NETWORK_ERROR.name),
                                numbers = mapOf(
                                    "nativeErrorCode" to errorValue.toLong().coerceAtLeast(0L),
                                    "wantedBytes" to latestWanted,
                                    "doneBytes" to latestDone,
                                    "peerCount" to latestPeers.toLong(),
                                    "seedCount" to latestSeeds.toLong(),
                                ),
                                priority = LogPriority.WARN,
                            )
                            completed = false
                        } else if (
                            status.isFinished ||
                            (latestWanted > 0L && latestDone >= latestWanted)
                        ) {
                            completed = true
                        } else {
                            delay(POLL_INTERVAL_MS)
                        }
                    }
                    requireNotNull(completed)
                }
            } catch (_: TimeoutCancellationException) {
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.TRANSFER_TIMEOUT,
                    operationId = request.operationId,
                    codes = mapOf("failure" to ProviderP2pFailureCode.NETWORK_ERROR.name),
                    numbers = mapOf(
                        "timeoutMs" to downloadTimeoutMs,
                        "wantedBytes" to latestWanted,
                        "doneBytes" to latestDone,
                        "peerCount" to latestPeers.toLong(),
                        "seedCount" to latestSeeds.toLong(),
                        "downloadRate" to latestRate,
                    ),
                    flags = mapOf(
                        "firstPeerObserved" to firstPeerObserved,
                        "firstByteObserved" to firstByteObserved,
                    ),
                    priority = LogPriority.WARN,
                )
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.NETWORK_ERROR,
                )
            }
            if (!completedWithoutError) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.NETWORK_ERROR,
                )
            }

            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.TRANSFER_PROGRESS,
                operationId = request.operationId,
                codes = mapOf("state" to "COMPLETE"),
                numbers = mapOf(
                    "wantedBytes" to latestWanted,
                    "doneBytes" to latestDone,
                    "progressPercent" to progressPercent(latestDone, latestWanted).toLong(),
                    "peerCount" to latestPeers.toLong(),
                    "seedCount" to latestSeeds.toLong(),
                ),
            )

            handle.pause()
            session.remove(handle)

            currentStage = "SELECTED_FILE_CHECK"
            val selectedFile = File(downloadRoot, selectedPath)
            val exists = selectedFile.isFile
            val actualSize = selectedFile.length().coerceAtLeast(0L)
            val sizeComplete = exists && actualSize == selectedSize
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.SELECTED_FILE_CHECK,
                operationId = request.operationId,
                codes = mapOf("format" to format.name),
                numbers = mapOf(
                    "expectedBytes" to selectedSize.coerceAtLeast(0L),
                    "actualBytes" to actualSize,
                ),
                flags = mapOf(
                    "exists" to exists,
                    "sizeComplete" to sizeComplete,
                ),
                priority = if (sizeComplete) LogPriority.INFO else LogPriority.WARN,
            )
            if (!sizeComplete) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.STORAGE_ERROR,
                )
            }

            ProviderP2pDownloadResult.Ready(
                file = selectedFile,
                format = format,
            )
        } catch (error: CancellationException) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.JOB_CANCELLED,
                operationId = request.operationId,
                codes = mapOf("stage" to currentStage),
                exceptionClass = error::class.qualifiedName,
                priority = LogPriority.WARN,
            )
            throw error
        } catch (error: IllegalArgumentException) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.METADATA_FAILED,
                operationId = request.operationId,
                codes = mapOf(
                    "stage" to currentStage,
                    "failure" to ProviderP2pFailureCode.METADATA_UNAVAILABLE.name,
                ),
                exceptionClass = error::class.qualifiedName,
                priority = LogPriority.WARN,
            )
            ProviderP2pDownloadResult.Failure(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
            )
        } catch (error: Throwable) {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.TRANSFER_FAILED,
                operationId = request.operationId,
                codes = mapOf(
                    "stage" to currentStage,
                    "failure" to ProviderP2pFailureCode.NETWORK_ERROR.name,
                ),
                exceptionClass = error::class.qualifiedName,
                priority = LogPriority.ERROR,
            )
            ProviderP2pDownloadResult.Failure(
                ProviderP2pFailureCode.NETWORK_ERROR,
            )
        } finally {
            if (started) {
                val stopped = runCatching { session.stop() }.isSuccess
                ProviderP2pDiagnostics.record(
                    event = ProviderP2pDiagnosticEvent.SESSION_READY,
                    operationId = request.operationId,
                    codes = mapOf("phase" to "STOP"),
                    flags = mapOf("stopped" to stopped),
                    priority = if (stopped) LogPriority.INFO else LogPriority.WARN,
                )
            }
        }
    }

    private fun validateIdentity(
        request: ProviderP2pAcquireRequest,
        torrent: TorrentInfo,
    ): ProviderP2pFailureCode? {
        val expected = request.infoHash ?: return null
        val actual = when (expected.length) {
            40 -> torrent.infoHashV1()?.toHex()
            64 -> torrent.infoHashV2()?.toHex()
            else -> null
        }
        return if (actual != null && actual.equals(expected, ignoreCase = true)) {
            null
        } else {
            ProviderP2pFailureCode.FILE_MISMATCH
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Provider P2P directory could not be created")
        }
    }

    private fun metadataSource(request: ProviderP2pAcquireRequest): String = when {
        !request.magnetUri.isNullOrBlank() -> "MAGNET"
        request.infoHash?.length == 40 -> "INFO_HASH"
        !request.torrentUrl.isNullOrBlank() -> "TORRENT_URL_UNUSED"
        else -> "NONE"
    }

    private fun currentAbiCode(): String =
        Build.SUPPORTED_ABIS.firstOrNull()
            ?.uppercase()
            ?.replace('-', '_')
            ?.takeIf { ABI_CODE.matches(it) }
            ?: "UNKNOWN"

    private fun elapsedMillis(startedAtNanos: Long, nowNanos: Long = System.nanoTime()): Long =
        ((nowNanos - startedAtNanos) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    private fun progressPercent(done: Long, wanted: Long): Int = when {
        wanted <= 0L -> 0
        done >= wanted -> 100
        else -> ((done * 100L) / wanted).coerceIn(0L, 99L).toInt()
    }

    private fun progressBucket(percent: Int): Int = when {
        percent >= 100 -> 100
        percent >= 75 -> 75
        percent >= 50 -> 50
        percent >= 25 -> 25
        percent >= 10 -> 10
        percent >= 1 -> 1
        else -> 0
    }

    private fun Long?.orZero(): Long = this?.coerceAtLeast(0L) ?: 0L

    companion object {
        internal const val DOWNLOAD_DIRECTORY = "download"

        private const val METADATA_TIMEOUT_SECONDS = 30
        private const val HANDLE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 10L * 60L * 1000L
        private const val MAX_DOWNLOAD_TIMEOUT_MS = 60L * 60L * 1000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val ABI_CODE = Regex("^[A-Z0-9_]{1,32}$")

        private val DEFAULT_SESSION_PARAMS_FACTORY: () -> SessionParams = {
            val settings = SettingsPack()
            // Local peer discovery broadcasts are unnecessary for Tsuzuki acquisition.
            // DHT/PEX remain available after the user has explicitly allowed direct P2P.
            settings.setEnableLsd(false)
            SessionParams(settings)
        }

        private val DEFAULT_METADATA_RESOLVER:
            suspend (SessionManager, ProviderP2pAcquireRequest, File) -> TorrentInfo? =
            { session, request, workingDirectory ->
                val magnet = request.magnetUri
                    ?: request.infoHash
                        ?.takeIf { it.length == 40 }
                        ?.let { hash -> "magnet:?xt=urn:btih:$hash" }

                magnet?.let { value ->
                    val metadataDirectory = File(workingDirectory, "metadata").apply {
                        mkdirs()
                    }
                    session.fetchMagnet(
                        value,
                        METADATA_TIMEOUT_SECONDS,
                        metadataDirectory,
                    )?.let(::TorrentInfo)
                }
            }
    }
}

/**
 * Release Direct P2P is limited to native artifacts that pass Tsuzuki's packaging gate.
 *
 * FrostWire jlibtorrent 2.0.12.9 x86_64 remains debug-only because its published ELF
 * currently fails the Android 16 KiB GNU_RELRO boundary requirement.
 */
internal object JlibtorrentNativeSupport {

    // Debug instrumentation may opt into additional ABIs explicitly; production never does.
    private val RELEASE_SUPPORTED_ABIS = setOf(
        "armeabi-v7a",
        "arm64-v8a",
        "x86",
    )

    fun isReleaseAbiSupported(abi: String?): Boolean =
        abi != null && abi in RELEASE_SUPPORTED_ABIS

    fun isCurrentRuntimeSupported(): Boolean =
        isReleaseAbiSupported(Build.SUPPORTED_ABIS.firstOrNull())
}
