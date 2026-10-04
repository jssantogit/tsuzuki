package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.core.content.FileProvider
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class ProviderManagedFileStore internal constructor(
    private val root: File,
    private val uriFactory: (File) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val maxFilesPerProvider: Int = DEFAULT_MAX_FILES_PER_PROVIDER,
    private val maxTotalBytesPerProvider: Long = DEFAULT_MAX_TOTAL_BYTES_PER_PROVIDER,
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
        ensureDirectory(root)
    }

    @Synchronized
    fun promote(
        providerId: String,
        bytes: ByteArray,
        format: ProviderManagedResourceFormat,
    ): String {
        val provider = ProviderId(providerId)
        if (bytes.isEmpty() || bytes.size > maxFileBytes) {
            throw IllegalStateException("Provider managed resource exceeds the file byte limit")
        }

        val directory = providerDirectory(provider)
        ensureDirectory(directory)
        pruneExpired(directory)

        val existing = directory.listFiles().orEmpty().filter(File::isFile)
        if (existing.size >= maxFilesPerProvider) {
            throw IllegalStateException("Provider managed resource count limit exceeded")
        }
        val totalBytes = existing.sumOf(File::length)
        if (totalBytes + bytes.size > maxTotalBytesPerProvider) {
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
        const val DEFAULT_MAX_FILE_BYTES = 16 * 1024 * 1024
        const val DEFAULT_MAX_FILES_PER_PROVIDER = 32
        const val DEFAULT_MAX_TOTAL_BYTES_PER_PROVIDER = 64L * 1024L * 1024L
    }
}
