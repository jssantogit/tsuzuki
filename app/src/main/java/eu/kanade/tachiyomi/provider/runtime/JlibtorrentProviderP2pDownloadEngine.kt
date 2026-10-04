package eu.kanade.tachiyomi.provider.runtime

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
) : ProviderP2pDownloadEngine {

    constructor() : this(
        metadataResolver = DEFAULT_METADATA_RESOLVER,
        initialPeers = { emptyList() },
        sessionParamsFactory = DEFAULT_SESSION_PARAMS_FACTORY,
        downloadTimeoutMs = DEFAULT_DOWNLOAD_TIMEOUT_MS,
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
        ensureDirectory(workingDirectory)

        val session = SessionManager()
        var started = false
        try {
            session.start(sessionParamsFactory())
            started = true

            val torrent = metadataResolver(session, request, workingDirectory)
                ?: return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                )

            validateIdentity(request, torrent)?.let { failure ->
                return@withContext ProviderP2pDownloadResult.Failure(failure)
            }

            val files = torrent.files()
            val selectedIndex = request.selectedFileIndex
            if (selectedIndex !in 0 until files.numFiles()) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val selectedPath = files.filePath(selectedIndex)
                .replace('\\', '/')
            if (selectedPath != request.selectedFilePath) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val selectedSize = files.fileSize(selectedIndex)
            if (
                request.selectedFileSizeBytes != null &&
                request.selectedFileSizeBytes != selectedSize
            ) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.FILE_MISMATCH,
                )
            }

            val format = when {
                selectedPath.endsWith(".cbz", ignoreCase = true) ->
                    ProviderManagedResourceFormat.CBZ
                selectedPath.endsWith(".zip", ignoreCase = true) ->
                    ProviderManagedResourceFormat.ZIP
                else ->
                    return@withContext ProviderP2pDownloadResult.Failure(
                        ProviderP2pFailureCode.FILE_MISMATCH,
                    )
            }

            val downloadRoot = File(workingDirectory, DOWNLOAD_DIRECTORY)
            ensureDirectory(downloadRoot)

            val priorities = Priority.array(Priority.IGNORE, torrent.numFiles())
            priorities[selectedIndex] = Priority.NORMAL

            session.download(
                torrent,
                downloadRoot,
                null,
                priorities,
                initialPeers(request),
                TorrentFlags.PAUSED,
            )

            val handle: TorrentHandle = try {
                withTimeout<TorrentHandle>(HANDLE_TIMEOUT_MS) {
                    while (true) {
                        coroutineContext.ensureActive()
                        session.find(torrent)?.let { return@withTimeout it }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.UNAVAILABLE,
                )
            }

            handle.prioritizeFiles(priorities)
            handle.resume()

            try {
                withTimeout(downloadTimeoutMs) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val status = handle.status(true)
                        if (status.errorCode().value() != 0) {
                            return@withTimeout false
                        }
                        val wanted = status.totalWanted()
                        val complete =
                            status.isFinished ||
                                (wanted > 0L && status.totalWantedDone() >= wanted)
                        if (complete) {
                            return@withTimeout true
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.NETWORK_ERROR,
                )
            }.let { completedWithoutError ->
                if (!completedWithoutError) {
                    return@withContext ProviderP2pDownloadResult.Failure(
                        ProviderP2pFailureCode.NETWORK_ERROR,
                    )
                }
            }

            handle.pause()
            session.remove(handle)

            val selectedFile = File(downloadRoot, selectedPath)
            if (
                !selectedFile.isFile ||
                selectedFile.length() != selectedSize
            ) {
                return@withContext ProviderP2pDownloadResult.Failure(
                    ProviderP2pFailureCode.STORAGE_ERROR,
                )
            }

            ProviderP2pDownloadResult.Ready(
                file = selectedFile,
                format = format,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            ProviderP2pDownloadResult.Failure(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
            )
        } catch (_: Throwable) {
            ProviderP2pDownloadResult.Failure(
                ProviderP2pFailureCode.NETWORK_ERROR,
            )
        } finally {
            if (started) {
                runCatching { session.stop() }
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

    companion object {
        internal const val DOWNLOAD_DIRECTORY = "download"

        private const val METADATA_TIMEOUT_SECONDS = 30
        private const val HANDLE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 10L * 60L * 1000L
        private const val MAX_DOWNLOAD_TIMEOUT_MS = 60L * 60L * 1000L

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
