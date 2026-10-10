package eu.kanade.tachiyomi.provider.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import tachiyomi.core.provider.runtime.ProviderP2pHostService
import tachiyomi.domain.tsuzuki.provider.ProviderId
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun interface ProviderP2pDownloadEngine {
    suspend fun download(
        request: ProviderP2pAcquireRequest,
        workingDirectory: File,
    ): ProviderP2pDownloadResult
}

sealed interface ProviderP2pDownloadResult {
    data class Ready(
        val file: File,
        val format: ProviderManagedResourceFormat,
    ) : ProviderP2pDownloadResult

    data class Failure(
        val reason: ProviderP2pFailureCode,
    ) : ProviderP2pDownloadResult
}

class ProviderP2pJobManager internal constructor(
    private val root: File,
    private val managedFiles: ProviderManagedFileStore,
    private val engine: ProviderP2pDownloadEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val completedTtlMs: Long = DEFAULT_COMPLETED_TTL_MS,
) : AutoCloseable {

    init {
        require(completedTtlMs > 0L) { "Provider P2P completed-job TTL must be positive" }
        ensureDirectory(root)
    }

    private val lock = Any()
    private val jobs = mutableMapOf<JobKey, JobEntry>()

    fun service(providerId: String): ProviderP2pHostService {
        ProviderId(providerId)
        return ProviderP2pHostService { request ->
            acquire(
                providerId = providerId,
                request = request,
            )
        }
    }

    private suspend fun acquire(
        providerId: String,
        request: ProviderP2pAcquireRequest,
    ): ProviderP2pAcquireResponse {
        val key = JobKey(
            providerId = providerId,
            operationId = request.operationId,
        )

        val start = synchronized(lock) {
            pruneCompletedLocked()

            val existing = jobs[key]
            if (existing != null) {
                if (existing.request != request) {
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.JOB_REJECTED,
                        operationId = request.operationId,
                        jobId = existing.jobId,
                        providerId = providerId,
                        codes = mapOf("failure" to ProviderP2pFailureCode.FILE_MISMATCH.name),
                        priority = LogPriority.WARN,
                    )
                    return ProviderP2pAcquireResponse.Failure(
                        ProviderP2pFailureCode.FILE_MISMATCH,
                    )
                }
                existing.pollCount += 1L
                if (shouldLogPoll(existing.pollCount)) {
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.JOB_REUSED,
                        operationId = request.operationId,
                        jobId = existing.jobId,
                        providerId = providerId,
                        codes = mapOf("state" to existing.response.stateCode()),
                        numbers = buildMap {
                            put("pollCount", existing.pollCount)
                            existing.completedAtMillis?.let { completedAt ->
                                put("completedAgeMs", (clock() - completedAt).coerceAtLeast(0L))
                            }
                        },
                    )
                }
                return existing.response
            }

            val jobId = UUID.randomUUID().toString()
            val entry = JobEntry(
                request = request,
                jobId = jobId,
                response = ProviderP2pAcquireResponse.Pending(jobId),
            )
            jobs[key] = entry
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.ACQUISITION_REQUESTED,
                operationId = request.operationId,
                jobId = jobId,
                providerId = providerId,
                codes = mapOf("format" to selectedFormatCode(request)),
                numbers = buildMap {
                    put("fileIndex", request.selectedFileIndex.toLong())
                    request.selectedFileSizeBytes?.let { put("expectedBytes", it) }
                },
                flags = mapOf(
                    "hasInfoHash" to !request.infoHash.isNullOrBlank(),
                    "hasMagnet" to !request.magnetUri.isNullOrBlank(),
                    "hasTorrentUrl" to !request.torrentUrl.isNullOrBlank(),
                ),
            )
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.JOB_CREATED,
                operationId = request.operationId,
                jobId = jobId,
                providerId = providerId,
            )
            entry
        }

        val worker = scope.launch {
            runJob(
                key = key,
                entry = start,
            )
        }
        val accepted = synchronized(lock) {
            if (jobs[key] === start) {
                start.worker = worker
                true
            } else {
                false
            }
        }
        if (!accepted) {
            worker.cancel()
        }
        return start.response
    }

    private suspend fun runJob(
        key: JobKey,
        entry: JobEntry,
    ) {
        val startedAtMillis = clock()
        val workingDirectory = File(
            root,
            "${sha256(key.providerId)}-${entry.jobId}",
        )
        ensureDirectory(workingDirectory)
        ProviderP2pDiagnostics.record(
            event = ProviderP2pDiagnosticEvent.WORKER_STARTED,
            operationId = entry.request.operationId,
            jobId = entry.jobId,
            providerId = key.providerId,
        )

        var terminalExceptionClass: String? = null
        val response = try {
            when (val result = engine.download(entry.request, workingDirectory)) {
                is ProviderP2pDownloadResult.Ready -> {
                    val file = result.file
                    val withinWorkingDirectory = runCatching {
                        file.canonicalFile.toPath().startsWith(workingDirectory.canonicalFile.toPath())
                    }.getOrDefault(false)
                    ProviderP2pDiagnostics.record(
                        event = ProviderP2pDiagnosticEvent.SELECTED_FILE_CHECK,
                        operationId = entry.request.operationId,
                        jobId = entry.jobId,
                        providerId = key.providerId,
                        codes = mapOf("format" to result.format.name),
                        numbers = mapOf("actualBytes" to file.length().coerceAtLeast(0L)),
                        flags = mapOf(
                            "isFile" to file.isFile,
                            "withinJobRoot" to withinWorkingDirectory,
                        ),
                    )
                    if (!file.isFile || !withinWorkingDirectory) {
                        ProviderP2pAcquireResponse.Failure(
                            ProviderP2pFailureCode.STORAGE_ERROR,
                        )
                    } else {
                        ProviderP2pDiagnostics.record(
                            event = ProviderP2pDiagnosticEvent.ADOPTION_START,
                            operationId = entry.request.operationId,
                            jobId = entry.jobId,
                            providerId = key.providerId,
                            codes = mapOf("format" to result.format.name),
                            numbers = mapOf("actualBytes" to file.length().coerceAtLeast(0L)),
                        )
                        runCatching {
                            managedFiles.adoptFile(
                                providerId = key.providerId,
                                source = file,
                                format = result.format,
                                diagnosticOperationId = entry.request.operationId,
                                diagnosticJobId = entry.jobId,
                            )
                        }.fold(
                            onSuccess = { resource ->
                                ProviderP2pDiagnostics.record(
                                    event = ProviderP2pDiagnosticEvent.ADOPTION_READY,
                                    operationId = entry.request.operationId,
                                    jobId = entry.jobId,
                                    providerId = key.providerId,
                                    codes = mapOf("format" to result.format.name),
                                )
                                ProviderP2pAcquireResponse.Ready(
                                    resource = resource,
                                    format = result.format,
                                )
                            },
                            onFailure = { error ->
                                terminalExceptionClass = error::class.qualifiedName
                                ProviderP2pDiagnostics.record(
                                    event = ProviderP2pDiagnosticEvent.ADOPTION_FAILED,
                                    operationId = entry.request.operationId,
                                    jobId = entry.jobId,
                                    providerId = key.providerId,
                                    codes = mapOf("failure" to ProviderP2pFailureCode.STORAGE_ERROR.name),
                                    exceptionClass = terminalExceptionClass,
                                    priority = LogPriority.ERROR,
                                )
                                ProviderP2pAcquireResponse.Failure(
                                    ProviderP2pFailureCode.STORAGE_ERROR,
                                )
                            },
                        )
                    }
                }

                is ProviderP2pDownloadResult.Failure ->
                    ProviderP2pAcquireResponse.Failure(result.reason)
            }
        } catch (error: CancellationException) {
            terminalExceptionClass = error::class.qualifiedName
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.JOB_CANCELLED,
                operationId = entry.request.operationId,
                jobId = entry.jobId,
                providerId = key.providerId,
                codes = mapOf("failure" to ProviderP2pFailureCode.CANCELLED.name),
                exceptionClass = terminalExceptionClass,
                priority = LogPriority.WARN,
            )
            ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.CANCELLED)
        } catch (error: Throwable) {
            terminalExceptionClass = error::class.qualifiedName
            ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.UNAVAILABLE)
        }

        val completedAtMillis = clock()
        ProviderP2pDiagnostics.record(
            event = ProviderP2pDiagnosticEvent.JOB_COMPLETED,
            operationId = entry.request.operationId,
            jobId = entry.jobId,
            providerId = key.providerId,
            codes = when (response) {
                is ProviderP2pAcquireResponse.Ready -> mapOf(
                    "state" to "READY",
                    "format" to response.format.name,
                )
                is ProviderP2pAcquireResponse.Pending -> mapOf("state" to "PENDING")
                is ProviderP2pAcquireResponse.Failure -> mapOf(
                    "state" to "FAILED",
                    "failure" to response.reason.name,
                )
            },
            numbers = mapOf(
                "elapsedMs" to (completedAtMillis - startedAtMillis).coerceAtLeast(0L),
                "pollCount" to entry.pollCount,
            ),
            exceptionClass = terminalExceptionClass,
            priority = if (response is ProviderP2pAcquireResponse.Failure) {
                LogPriority.WARN
            } else {
                LogPriority.INFO
            },
        )

        synchronized(lock) {
            val current = jobs[key]
            if (current === entry) {
                entry.response = response
                entry.completedAtMillis = completedAtMillis
            }
        }
        val cleanupSucceeded = workingDirectory.deleteRecursively()
        ProviderP2pDiagnostics.record(
            event = ProviderP2pDiagnosticEvent.JOB_COMPLETED,
            operationId = entry.request.operationId,
            jobId = entry.jobId,
            providerId = key.providerId,
            codes = mapOf("phase" to "CLEANUP"),
            flags = mapOf("cleanupSucceeded" to cleanupSucceeded),
        )
    }

    private fun pruneCompletedLocked() {
        val now = clock()
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            val completedAt = entry.completedAtMillis ?: continue
            val age = now - completedAt
            if (age < 0L || age > completedTtlMs) {
                iterator.remove()
            }
        }
    }

    fun cancelAll(): Int {
        val active = synchronized(lock) {
            val values = jobs.mapNotNull { (key, entry) ->
                entry.worker
                    ?.takeIf { worker -> worker.isActive }
                    ?.let { worker -> Triple(key, entry, worker) }
            }
            jobs.clear()
            values
        }
        active.forEach { (key, entry, worker) ->
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.JOB_CANCELLED,
                operationId = entry.request.operationId,
                jobId = entry.jobId,
                providerId = key.providerId,
                codes = mapOf("reason" to "CANCEL_ALL"),
                priority = LogPriority.WARN,
            )
            worker.cancel()
        }
        return active.size
    }

    override fun close() {
        cancelAll()
        scope.cancel()
        root.deleteRecursively()
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Provider P2P working directory could not be created")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun selectedFormatCode(request: ProviderP2pAcquireRequest): String =
        request.selectedFilePath.substringAfterLast('.', missingDelimiterValue = "UNKNOWN")
            .uppercase()
            .takeIf { it == "CBZ" || it == "ZIP" }
            ?: "UNKNOWN"

    private fun ProviderP2pAcquireResponse.stateCode(): String = when (this) {
        is ProviderP2pAcquireResponse.Pending -> "PENDING"
        is ProviderP2pAcquireResponse.Ready -> "READY"
        is ProviderP2pAcquireResponse.Failure -> "FAILED"
    }

    private fun shouldLogPoll(pollCount: Long): Boolean =
        pollCount in POLL_MILESTONES || (pollCount >= 1_000L && pollCount % 1_000L == 0L)

    private data class JobKey(
        val providerId: String,
        val operationId: String,
    )

    private data class JobEntry(
        val request: ProviderP2pAcquireRequest,
        val jobId: String,
        var response: ProviderP2pAcquireResponse,
        var completedAtMillis: Long? = null,
        var worker: Job? = null,
        var pollCount: Long = 0L,
    )

    private companion object {
        const val DEFAULT_COMPLETED_TTL_MS = 24L * 60L * 60L * 1000L
        val POLL_MILESTONES = setOf(1L, 2L, 5L, 10L, 25L, 50L, 100L, 250L, 500L)
    }
}
