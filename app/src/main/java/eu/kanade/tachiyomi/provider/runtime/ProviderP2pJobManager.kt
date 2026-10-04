package eu.kanade.tachiyomi.provider.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
                    return ProviderP2pAcquireResponse.Failure(
                        ProviderP2pFailureCode.FILE_MISMATCH,
                    )
                }
                return existing.response
            }

            val entry = JobEntry(
                request = request,
                response = ProviderP2pAcquireResponse.Pending(UUID.randomUUID().toString()),
            )
            jobs[key] = entry
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
        val workingDirectory = File(
            root,
            "${sha256(key.providerId)}-${entry.jobId}",
        )
        ensureDirectory(workingDirectory)

        val response = try {
            when (val result = engine.download(entry.request, workingDirectory)) {
                is ProviderP2pDownloadResult.Ready -> {
                    val file = result.file
                    if (
                        !file.isFile ||
                        !file.canonicalFile.toPath().startsWith(workingDirectory.canonicalFile.toPath())
                    ) {
                        ProviderP2pAcquireResponse.Failure(
                            ProviderP2pFailureCode.STORAGE_ERROR,
                        )
                    } else {
                        runCatching {
                            managedFiles.adoptFile(
                                providerId = key.providerId,
                                source = file,
                                format = result.format,
                            )
                        }.fold(
                            onSuccess = { resource ->
                                ProviderP2pAcquireResponse.Ready(
                                    resource = resource,
                                    format = result.format,
                                )
                            },
                            onFailure = {
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
            ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.CANCELLED)
        } catch (_: Throwable) {
            ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.UNAVAILABLE)
        }

        synchronized(lock) {
            val current = jobs[key]
            if (current === entry) {
                entry.response = response
                entry.completedAtMillis = clock()
            }
        }
        workingDirectory.deleteRecursively()
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
        val workers = synchronized(lock) {
            val active = jobs.values
                .mapNotNull { entry -> entry.worker?.takeIf(Job::isActive) }
            jobs.clear()
            active
        }
        workers.forEach(Job::cancel)
        return workers.size
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

    private data class JobKey(
        val providerId: String,
        val operationId: String,
    )

    private data class JobEntry(
        val request: ProviderP2pAcquireRequest,
        var response: ProviderP2pAcquireResponse,
        var completedAtMillis: Long? = null,
        var worker: Job? = null,
    ) {
        val jobId: String
            get() = (response as? ProviderP2pAcquireResponse.Pending)?.jobId
                ?: error("Provider P2P job ID is only available while pending")
    }

    private companion object {
        const val DEFAULT_COMPLETED_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
