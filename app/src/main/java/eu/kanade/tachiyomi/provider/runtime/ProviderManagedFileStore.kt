package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.core.content.FileProvider
import logcat.LogPriority
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

class ProviderManagedFileStore internal constructor(
    private val root: File,
    private val uriFactory: (File) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFilesPerProvider: Int = DEFAULT_MAX_FILES_PER_PROVIDER,
    private val maxTotalBytesPerProvider: Long = DEFAULT_MAX_TOTAL_BYTES_PER_PROVIDER,
    private val maxArchiveEntries: Int = DEFAULT_MAX_ARCHIVE_ENTRIES,
    private val maxArchiveEntryBytes: Int = DEFAULT_MAX_ARCHIVE_ENTRY_BYTES,
    private val maxArchiveUncompressedBytes: Long = DEFAULT_MAX_ARCHIVE_UNCOMPRESSED_BYTES,
) : ProviderManagedResourceResolver {

    constructor(context: Context) : this(
        root = File(context.cacheDir, "provider-managed-files"),
        uriFactory = { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file,
            ).toString()
        },
    )

    init {
        require(ttlMs > 0L) { "Provider managed resource TTL must be positive" }
        require(maxFileBytes > 0) { "Provider managed resource file limit must be positive" }
        require(maxFilesPerProvider > 0) { "Provider managed resource count limit must be positive" }
        require(maxTotalBytesPerProvider >= maxFileBytes) {
            "Provider managed resource total byte limit must cover one maximum-sized file"
        }
        require(maxArchiveEntries > 0) { "Provider managed archive entry limit must be positive" }
        require(maxArchiveEntryBytes > 0) { "Provider managed archive entry byte limit must be positive" }
        require(maxArchiveUncompressedBytes >= maxArchiveEntryBytes) {
            "Provider managed archive expansion limit must cover one maximum-sized entry"
        }
        ensureDirectory(root)
    }

    @Synchronized
    fun promote(
        providerId: String,
        bytes: ByteArray,
        format: ProviderManagedResourceFormat,
    ): String {
        val provider = ProviderId(providerId)
        if (bytes.isEmpty() || bytes.size.toLong() > maxFileBytes) {
            throw IllegalStateException("Provider managed resource exceeds the file byte limit")
        }
        // Managed files outlive the invocation resource store and enter the inherited Reader,
        // so archive structure/expansion must be bounded before an opaque token is issued.
        validateArchive(bytes)

        val directory = providerDirectory(provider)
        ensureDirectory(directory)
        pruneExpired(directory)

        val existing = directory.listFiles().orEmpty().filter(File::isFile)
        if (existing.size >= maxFilesPerProvider) {
            throw IllegalStateException("Provider managed resource count limit exceeded")
        }
        val totalBytes = existing.sumOf(File::length)
        if (totalBytes + bytes.size.toLong() > maxTotalBytesPerProvider) {
            throw IllegalStateException("Provider managed resource total byte limit exceeded")
        }

        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.${format.extension}")
        val temp = File(directory, ".$id.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveAtomically(temp, target)
            target.setLastModified(clock())
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        }

        return "managed:$id"
    }

    @Synchronized
    fun adoptFile(
        providerId: String,
        source: File,
        format: ProviderManagedResourceFormat,
        diagnosticOperationId: String? = null,
        diagnosticJobId: String? = null,
    ): String {
        val provider = ProviderId(providerId)
        val sourceValid = source.isFile && !Files.isSymbolicLink(source.toPath())
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_CHECK",
            state = "STARTED",
            flags = mapOf("sourceValid" to sourceValid),
        )
        if (!sourceValid) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = "SOURCE_CHECK",
                state = "FAILED",
                flags = mapOf("sourceValid" to false),
                priority = LogPriority.WARN,
            )
        }
        require(sourceValid) {
            "Provider managed source must be a regular host-owned file"
        }
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_CHECK",
            state = "SUCCEEDED",
            flags = mapOf("sourceValid" to true),
        )

        val sourceBytes = source.length()
        val sourceSizeValid = sourceBytes > 0L && sourceBytes <= maxFileBytes
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_SIZE",
            state = "STARTED",
            numbers = mapOf(
                "sourceBytes" to sourceBytes.coerceAtLeast(0L),
                "maxFileBytes" to maxFileBytes,
            ),
        )
        if (!sourceSizeValid) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = "SOURCE_SIZE",
                state = "FAILED",
                numbers = mapOf(
                    "sourceBytes" to sourceBytes.coerceAtLeast(0L),
                    "maxFileBytes" to maxFileBytes,
                ),
                priority = LogPriority.WARN,
            )
            throw IllegalStateException("Provider managed resource exceeds the file byte limit")
        }
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_SIZE",
            state = "SUCCEEDED",
            numbers = mapOf("sourceBytes" to sourceBytes),
        )

        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_ARCHIVE_VALIDATE",
            state = "STARTED",
        )
        try {
            validateArchive(source)
        } catch (error: Throwable) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = "SOURCE_ARCHIVE_VALIDATE",
                state = "FAILED",
                exceptionClass = error.javaClass.name,
                priority = LogPriority.WARN,
            )
            throw error
        }
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "SOURCE_ARCHIVE_VALIDATE",
            state = "SUCCEEDED",
        )

        val directory = providerDirectory(provider)
        ensureDirectory(directory)
        pruneExpired(directory)

        val existing = directory.listFiles().orEmpty().filter(File::isFile)
        val countWithinLimit = existing.size < maxFilesPerProvider
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "QUOTA_CHECK",
            state = "STARTED",
            numbers = mapOf(
                "existingFiles" to existing.size.toLong(),
                "incomingBytes" to sourceBytes,
            ),
            flags = mapOf("countWithinLimit" to countWithinLimit),
        )
        if (!countWithinLimit) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = "QUOTA_CHECK",
                state = "FAILED",
                codes = mapOf("reason" to "FILE_COUNT_LIMIT"),
                priority = LogPriority.WARN,
            )
            throw IllegalStateException("Provider managed resource count limit exceeded")
        }

        val totalBytes = existing.sumOf(File::length)
        val bytesWithinLimit = totalBytes + sourceBytes <= maxTotalBytesPerProvider
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "QUOTA_CHECK",
            state = "STARTED",
            numbers = mapOf(
                "existingFiles" to existing.size.toLong(),
                "existingBytes" to totalBytes.coerceAtLeast(0L),
                "incomingBytes" to sourceBytes,
            ),
            flags = mapOf(
                "countWithinLimit" to true,
                "bytesWithinLimit" to bytesWithinLimit,
            ),
        )
        if (!bytesWithinLimit) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = "QUOTA_CHECK",
                state = "FAILED",
                codes = mapOf("reason" to "TOTAL_BYTES_LIMIT"),
                priority = LogPriority.WARN,
            )
            throw IllegalStateException("Provider managed resource total byte limit exceeded")
        }
        recordAdoptionStage(
            operationId = diagnosticOperationId,
            jobId = diagnosticJobId,
            providerId = providerId,
            phase = "QUOTA_CHECK",
            state = "SUCCEEDED",
            numbers = mapOf(
                "existingFiles" to existing.size.toLong(),
                "existingBytes" to totalBytes.coerceAtLeast(0L),
                "incomingBytes" to sourceBytes,
            ),
        )

        val id = UUID.randomUUID().toString()
        val target = File(directory, "$id.${format.extension}")
        val temp = File(directory, ".$id.tmp")
        var currentPhase = "COPY"

        try {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "STARTED",
                numbers = mapOf("sourceBytes" to sourceBytes),
            )
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    var copied = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        copied += read
                        if (copied > maxFileBytes) {
                            throw IllegalStateException(
                                "Provider managed resource exceeds the file byte limit",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    if (copied != sourceBytes) {
                        throw IllegalStateException("Provider managed source changed during adoption")
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "SUCCEEDED",
                numbers = mapOf("copiedBytes" to sourceBytes),
            )

            currentPhase = "TEMP_ARCHIVE_VALIDATE"
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "STARTED",
            )
            validateArchive(temp)
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "SUCCEEDED",
            )

            currentPhase = "MOVE"
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "STARTED",
            )
            moveAtomically(temp, target)
            target.setLastModified(clock())
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "SUCCEEDED",
            )

            currentPhase = "RETIRE_SOURCE"
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "STARTED",
            )
            if (!source.delete()) {
                target.delete()
                throw IllegalStateException("Provider managed source could not be retired after adoption")
            }
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "SUCCEEDED",
            )
        } catch (error: Throwable) {
            recordAdoptionStage(
                operationId = diagnosticOperationId,
                jobId = diagnosticJobId,
                providerId = providerId,
                phase = currentPhase,
                state = "FAILED",
                exceptionClass = error.javaClass.name,
                priority = LogPriority.ERROR,
            )
            temp.delete()
            target.delete()
            throw error
        }

        return "managed:$id"
    }

    @Synchronized
    fun clearAll() {
        if (root.exists() && !root.deleteRecursively()) {
            throw IllegalStateException("Provider managed resource cache could not be cleared")
        }
        ensureDirectory(root)
    }

    @Synchronized
    override fun resolve(
        providerId: ProviderId,
        resource: ProviderManagedResourceRef,
        format: ProviderManagedFileFormat,
    ): String? {
        val directory = providerDirectory(providerId)
        if (!directory.isDirectory) return null
        pruneExpired(directory)

        val id = resource.value.removePrefix("managed:")
        val file = File(directory, "$id.${format.extension}")
        if (!file.isFile || isExpired(file)) {
            file.delete()
            return null
        }
        if (file.length() <= 0L || file.length() > maxFileBytes) {
            file.delete()
            return null
        }
        return uriFactory(file)
    }

    private fun recordAdoptionStage(
        operationId: String?,
        jobId: String?,
        providerId: String,
        phase: String,
        state: String,
        codes: Map<String, String> = emptyMap(),
        numbers: Map<String, Long> = emptyMap(),
        flags: Map<String, Boolean> = emptyMap(),
        exceptionClass: String? = null,
        priority: LogPriority = LogPriority.INFO,
    ) {
        val safeOperationId = operationId ?: return
        ProviderP2pDiagnostics.record(
            event = ProviderP2pDiagnosticEvent.ADOPTION_STAGE,
            operationId = safeOperationId,
            jobId = jobId,
            providerId = providerId,
            codes = codes + mapOf(
                "phase" to phase,
                "state" to state,
            ),
            numbers = numbers,
            flags = flags,
            exceptionClass = exceptionClass,
            priority = priority,
        )
    }

    private fun providerDirectory(providerId: ProviderId): File =
        File(root, sha256(providerId.value))

    private fun pruneExpired(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .forEach { file ->
                if (file.name.endsWith(".tmp") || isExpired(file)) {
                    file.delete()
                }
            }
    }

    private fun isExpired(file: File): Boolean {
        val age = clock() - file.lastModified()
        return age < 0L || age > ttlMs
    }

    // Stream validation stops as soon as any archive quota is exceeded; the expanded bytes are
    // never retained as a second in-memory copy by the managed-file store.
    private fun validateArchive(bytes: ByteArray) {
        if (!hasZipLocalFileHeader(bytes)) {
            throw IllegalStateException("Provider managed resource is not a ZIP archive")
        }
        ByteArrayInputStream(bytes).use(::validateArchiveStream)
    }

    private fun validateArchive(file: File) {
        if (!hasZipLocalFileHeader(file)) {
            throw IllegalStateException("Provider managed resource is not a ZIP archive")
        }
        try {
            FileInputStream(file).use(::validateArchiveStream)
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: IOException) {
            throw IllegalStateException("Provider managed archive could not be validated", error)
        }
    }

    private fun validateArchiveStream(input: java.io.InputStream) {
        var entryCount = 0
        var fileCount = 0
        var totalUncompressedBytes = 0L

        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > maxArchiveEntries) {
                        throw IllegalStateException("Provider managed archive contains too many entries")
                    }
                    validateArchivePath(entry.name, entry.isDirectory)

                    if (!entry.isDirectory) {
                        fileCount += 1
                        var entryBytes = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue

                            entryBytes += read
                            totalUncompressedBytes += read
                            if (entryBytes > maxArchiveEntryBytes) {
                                throw IllegalStateException(
                                    "Provider managed archive entry exceeds the byte limit",
                                )
                            }
                            if (totalUncompressedBytes > maxArchiveUncompressedBytes) {
                                throw IllegalStateException(
                                    "Provider managed archive exceeds the expansion limit",
                                )
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: IOException) {
            throw IllegalStateException("Provider managed archive could not be validated", error)
        }

        if (fileCount == 0) {
            throw IllegalStateException("Provider managed archive contains no files")
        }
    }

    private fun validateArchivePath(
        raw: String,
        isDirectory: Boolean,
    ) {
        val candidate = if (isDirectory) raw.removeSuffix("/") else raw
        if (
            candidate.isBlank() ||
            candidate.startsWith("/") ||
            candidate.startsWith("\\") ||
            '\\' in candidate ||
            ':' in candidate
        ) {
            throw IllegalStateException("Provider managed archive contains an unsafe path")
        }
        if (candidate.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalStateException("Provider managed archive contains an unsafe path")
        }
    }

    private fun hasZipLocalFileHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 3.toByte() &&
            bytes[3] == 4.toByte()

    private fun hasZipLocalFileHeader(file: File): Boolean =
        runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                input.read(header) == header.size && hasZipLocalFileHeader(header)
            }
        }.getOrDefault(false)

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IllegalStateException("Provider managed resource directory could not be created")
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private val ProviderManagedResourceFormat.extension: String
        get() = name.lowercase()

    private val ProviderManagedFileFormat.extension: String
        get() = name.lowercase()

    private companion object {
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAX_FILES_PER_PROVIDER = 32
        const val DEFAULT_MAX_TOTAL_BYTES_PER_PROVIDER = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ARCHIVE_ENTRIES = 5_000
        const val DEFAULT_MAX_ARCHIVE_ENTRY_BYTES = 64 * 1024 * 1024
        const val DEFAULT_MAX_ARCHIVE_UNCOMPRESSED_BYTES = 1024L * 1024L * 1024L
    }
}
