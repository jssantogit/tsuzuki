package tachiyomi.core.provider.runtime

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class FileProviderStorageHostService(
    private val root: File,
    private val providerId: String,
    private val maxEntries: Int = 256,
    private val maxValueChars: Int = 64 * 1024,
    private val maxTotalChars: Int = 512 * 1024,
) : ProviderStorageHostService {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    private val lock = Any()

    init {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(maxEntries > 0) { "Provider storage entry limit must be positive" }
        require(maxValueChars > 0) { "Provider storage value limit must be positive" }
        require(maxTotalChars >= maxValueChars) {
            "Provider storage total limit must cover one maximum-sized value"
        }
        ensureDirectory(root)
    }

    override suspend fun get(key: String): String? = withStateLock {
        validateKey(key)
        readState()[key]
    }

    override suspend fun set(key: String, value: String) {
        withStateLock {
            validateKey(key)
            if (value.length > maxValueChars) {
                throw ProviderHostServiceException("Provider storage value exceeds the size limit")
            }

            val state = readState().toMutableMap()
            state[key] = value
            if (state.size > maxEntries) {
                throw ProviderHostServiceException("Provider storage exceeds the entry limit")
            }
            if (state.values.sumOf(String::length) > maxTotalChars) {
                throw ProviderHostServiceException("Provider storage exceeds the total size limit")
            }
            writeState(state)
        }
    }

    /**
     * HOST-PRIVATE future MangaALL snapshot replacement; NOT exposed through the
     * Provider storage interface or to QuickJs. Replace reserved keys with one
     * atomic provider-state write, preserving unrelated entries and the previous
     * snapshot if the combined state exceeds any quota.
     */
    internal suspend fun replaceFutureMangaAllChapterSnapshot(index: String, parts: List<String>) {
        if (providerId != "app.tsuzuki.mangaall" || parts.size > 16) {
            throw ProviderHostServiceException("Future MangaALL snapshot scope is invalid")
        }
        withStateLock {
            val replacement = readState().toMutableMap()
            replacement.remove(FUTURE_MANGAALL_INDEX_KEY)
            repeat(16) { replacement.remove(FUTURE_MANGAALL_PART_PREFIX + it) }

            val newValues = parts.mapIndexed { n, value ->
                FUTURE_MANGAALL_PART_PREFIX + n to value
            } + (FUTURE_MANGAALL_INDEX_KEY to index)
            for ((key, value) in newValues) {
                validateKey(key)
                if (value.length > maxValueChars ||
                    value.encodeToByteArray().size > 64 * 1024
                ) {
                    throw ProviderHostServiceException("Future MangaALL snapshot value exceeds quota")
                }
                replacement[key] = value
            }

            if (replacement.size > maxEntries ||
                replacement.values.sumOf(String::length) > maxTotalChars ||
                replacement.values.sumOf { it.encodeToByteArray().size.toLong() } > maxTotalChars
            ) {
                throw ProviderHostServiceException("Future MangaALL snapshot exceeds shared provider quota")
            }

            // Same-filesystem atomic move; no clear-then-write intermediate
            // state and no loss of other Provider keys on quota rejection.
            writeState(replacement)
        }
    }

    override suspend fun remove(key: String) {
        withStateLock {
            validateKey(key)
            val state = readState().toMutableMap()
            if (state.remove(key) != null) {
                writeState(state)
            }
        }
    }

    /**
     * Future MangaALL's single provider-state file is shared by fresh Host
     * instances and may be opened from different Android processes. Atomic
     * rename alone does not serialize independent read-modify-write cycles.
     *
     * Keep the existing per-instance behavior for every other Provider.
     * MangaALL uses one JVM monitor per canonical lock path (FileLock otherwise
     * throws OverlappingFileLockException in one process), and an OS advisory
     * lock for every state read/write, including unrelated storage keys. The
     * lock is held only for bounded filesystem work, never while HTTP or JS
     * executes. All callers must use this storage service to honor the lock.
     *
     * Do not lock the replaced JSON inode: its inode changes on atomic rename.
     * A stable sidecar file remains the coordination point across processes.
     */
    private fun <T> withStateLock(block: () -> T): T {
        if (providerId != FUTURE_MANGAALL_PROVIDER_ID) {
            return synchronized(lock) { block() }
        }
        val lockFile = try {
            File(root.canonicalFile, ".$providerId.snapshot.lock")
        } catch (error: IOException) {
            throw ProviderHostServiceException("Future MangaALL storage lock path is unavailable", error)
        }
        val startedAt = System.nanoTime()
        val monitor = FUTURE_MANGAALL_LOCKS.computeIfAbsent(lockFile.path) { ReentrantLock() }
        var heldMonitor = false
        try {
            // The same three-second budget covers BOTH contention in this JVM
            // and the OS sidecar file, preventing 3s * N queued callers.
            if (!monitor.tryLock(LOCK_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
                throw ProviderHostServiceException("Future MangaALL storage lock is busy")
            }
            heldMonitor = true
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.use { channel ->
                    var acquired = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    while (acquired == null) {
                        if (System.nanoTime() - startedAt >= LOCK_WAIT_NANOS) {
                            throw ProviderHostServiceException("Future MangaALL storage lock is busy")
                        }
                        Thread.sleep(LOCK_RETRY_MILLIS)
                        acquired = try {
                            channel.tryLock()
                        } catch (_: OverlappingFileLockException) {
                            null
                        }
                    }
                    // A queued JVM caller cannot start a fresh OS-lock wait:
                    // its total budget already began before monitor.tryLock.
                    if (System.nanoTime() - startedAt >= LOCK_WAIT_NANOS) {
                        acquired.close()
                        throw ProviderHostServiceException("Future MangaALL storage lock is busy")
                    }
                    acquired.use { block() }
                }
            }
        } catch (error: ProviderHostServiceException) {
            throw error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ProviderHostServiceException("Future MangaALL storage lock was interrupted", error)
        } catch (error: IOException) {
            throw ProviderHostServiceException("Future MangaALL storage lock could not be acquired", error)
        } finally {
            if (heldMonitor) monitor.unlock()
        }
    }

    private fun readState(): Map<String, String> {
        val file = stateFile()
        if (!file.exists()) return emptyMap()

        val state = try {
            json.decodeFromString<Map<String, String>>(file.readText())
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider storage state is malformed", error)
        }

        if (
            state.size > maxEntries ||
            state.keys.any { !STORAGE_KEY.matches(it) } ||
            state.values.any { it.length > maxValueChars } ||
            state.values.sumOf(String::length) > maxTotalChars
        ) {
            throw ProviderHostServiceException("Provider storage state exceeds configured limits")
        }
        return state
    }

    private fun writeState(state: Map<String, String>) {
        atomicWrite(
            target = stateFile(),
            bytes = json.encodeToString(state).encodeToByteArray(),
        )
    }

    private fun stateFile(): File = File(root, "$providerId.json")

    private fun validateKey(key: String) {
        if (!STORAGE_KEY.matches(key)) {
            throw ProviderHostServiceException("Provider storage key is invalid")
        }
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val STORAGE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val FUTURE_MANGAALL_PROVIDER_ID = "app.tsuzuki.mangaall"
        const val LOCK_WAIT_NANOS = 3_000_000_000L
        const val LOCK_RETRY_MILLIS = 20L
        val FUTURE_MANGAALL_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
        const val FUTURE_MANGAALL_INDEX_KEY = "mangaall_private_chapters_index"
        const val FUTURE_MANGAALL_PART_PREFIX = "mangaall_private_chapters_part_"
    }
}

class ScopedProviderSecretsHostService(
    private val providerId: String,
    allowedKeys: Set<String>,
    private val maxValueChars: Int = 64 * 1024,
    private val resolver: suspend (providerId: String, key: String) -> String?,
) : ProviderSecretsHostService {

    private val allowedKeys = allowedKeys.toSet()

    init {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(this.allowedKeys.all(SECRET_KEY::matches)) { "Provider secret key is invalid" }
        require(maxValueChars > 0) { "Provider secret value limit must be positive" }
    }

    override suspend fun get(key: String): String? {
        if (key !in allowedKeys) {
            throw ProviderHostServiceException("Provider secret access is not allowed")
        }
        return try {
            resolver(providerId, key)?.also { value ->
                if (value.length > maxValueChars) {
                    throw ProviderHostServiceException("Provider secret value exceeds the size limit")
                }
            }
        } catch (error: ProviderHostServiceException) {
            throw error
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider secret lookup failed", error)
        }
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SECRET_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

class BoundedProviderLogHostService(
    private val maxChars: Int = 1024,
    private val sink: (String) -> Unit,
) : ProviderLogHostService {

    init {
        require(maxChars > 0) { "Provider log limit must be positive" }
    }

    override suspend fun info(message: String) {
        val sanitized = buildString(minOf(message.length, maxChars)) {
            message.forEach { char ->
                if (length >= maxChars) return@forEach
                if (!char.isISOControl() || char == '\n' || char == '\t') {
                    append(char)
                }
            }
        }
        sink(sanitized)
    }
}

private fun ensureDirectory(directory: File) {
    if (!directory.exists() && !directory.mkdirs()) {
        throw ProviderHostServiceException("Provider storage directory could not be created")
    }
    if (!directory.isDirectory) {
        throw ProviderHostServiceException("Provider storage root is not a directory")
    }
}

private fun atomicWrite(
    target: File,
    bytes: ByteArray,
) {
    val parent = target.parentFile
        ?: throw ProviderHostServiceException("Provider storage target has no parent")
    ensureDirectory(parent)

    val temporary = File.createTempFile(".provider-storage-", ".tmp", parent)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw ProviderHostServiceException("Provider storage requires atomic same-filesystem moves", error)
        }
    } catch (error: ProviderHostServiceException) {
        throw error
    } catch (error: Exception) {
        throw ProviderHostServiceException("Provider storage write failed", error)
    } finally {
        if (temporary.exists()) {
            temporary.delete()
        }
    }
}
