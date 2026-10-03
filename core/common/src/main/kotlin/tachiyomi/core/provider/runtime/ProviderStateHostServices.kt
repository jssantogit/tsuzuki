package tachiyomi.core.provider.runtime

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    override suspend fun get(key: String): String? = synchronized(lock) {
        validateKey(key)
        readState()[key]
    }

    override suspend fun set(key: String, value: String) {
        synchronized(lock) {
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

    override suspend fun remove(key: String) {
        synchronized(lock) {
            validateKey(key)
            val state = readState().toMutableMap()
            if (state.remove(key) != null) {
                writeState(state)
            }
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
    }
}

class ScopedProviderSecretsHostService(
    private val providerId: String,
    allowedKeys: Set<String>,
    private val resolver: suspend (providerId: String, key: String) -> String?,
) : ProviderSecretsHostService {

    private val allowedKeys = allowedKeys.toSet()

    init {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }
        require(this.allowedKeys.all(SECRET_KEY::matches)) { "Provider secret key is invalid" }
    }

    override suspend fun get(key: String): String? {
        if (key !in allowedKeys) {
            throw ProviderHostServiceException("Provider secret access is not allowed")
        }
        return try {
            resolver(providerId, key)
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
