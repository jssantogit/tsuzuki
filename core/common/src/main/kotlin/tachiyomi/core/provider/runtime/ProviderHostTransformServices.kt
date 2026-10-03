package tachiyomi.core.provider.runtime

import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProviderHostServiceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class DefaultProviderDomHostService(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
) : ProviderDomHostService {

    override suspend fun selectText(
        resourceHandle: ProviderResourceHandle,
        cssSelector: String,
    ): String {
        if (cssSelector.isBlank() || cssSelector.length > MAX_SELECTOR_CHARS) {
            throw ProviderHostServiceException("Provider DOM selector is invalid")
        }

        val html = resources
            .read(owner, resourceHandle, ProviderResourceKind.HTML)
            .decodeToString()
        val node = try {
            Jsoup.parse(html).selectFirst(cssSelector)
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider DOM selector is invalid", error)
        } ?: throw ProviderHostServiceException("Provider DOM selector did not match")

        val text = node.text()
        if (text.length > MAX_DOM_RESULT_CHARS) {
            throw ProviderHostServiceException("Provider DOM result exceeds the text limit")
        }
        return text
    }

    private companion object {
        const val MAX_SELECTOR_CHARS = 2 * 1024
        const val MAX_DOM_RESULT_CHARS = 64 * 1024
    }
}

class DefaultProviderBinaryTransformHostService(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    private val fetcher: suspend (String) -> ByteArray = {
        throw ProviderHostServiceException("Provider binary fetch service is unavailable")
    },
    private val maxTransformBytes: Int = 16 * 1024 * 1024,
) : ProviderBinaryHostService {

    init {
        require(maxTransformBytes > 0) { "Provider binary transform limit must be positive" }
    }

    override suspend fun fetch(url: String): ProviderResourceHandle {
        val bytes = try {
            fetcher(url)
        } catch (error: ProviderHostServiceException) {
            throw error
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider binary fetch failed", error)
        }
        if (bytes.size > maxTransformBytes) {
            throw ProviderHostServiceException("Provider binary response exceeds the byte limit")
        }
        return resources.put(owner, ProviderResourceKind.BINARY, bytes)
    }

    override suspend fun zipEntry(
        resourceHandle: ProviderResourceHandle,
        entryName: String,
    ): ProviderResourceHandle {
        validateArchiveEntryName(entryName)
        val archive = resources.read(owner, resourceHandle)

        try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = normalizeArchiveEntry(entry.name)
                    if (!entry.isDirectory && path == entryName) {
                        val bytes = zip.readBounded(maxTransformBytes)
                        zip.closeEntry()
                        return resources.put(owner, ProviderResourceKind.BINARY, bytes)
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: ProviderHostServiceException) {
            throw error
        } catch (error: IOException) {
            throw ProviderHostServiceException("Provider archive could not be read", error)
        }

        throw ProviderHostServiceException("Provider archive entry was not found")
    }

    private fun validateArchiveEntryName(value: String) {
        if (value.isBlank() || value.length > MAX_ENTRY_NAME_CHARS) {
            throw ProviderHostServiceException("Provider archive entry name is invalid")
        }
        if (normalizeArchiveEntry(value) != value) {
            throw ProviderHostServiceException("Provider archive entry name is unsafe")
        }
    }

    private fun normalizeArchiveEntry(value: String): String {
        if (
            value.startsWith("/") ||
            value.startsWith("\\") ||
            '\\' in value ||
            ':' in value
        ) {
            throw ProviderHostServiceException("Provider archive entry path is unsafe")
        }
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            throw ProviderHostServiceException("Provider archive entry path is unsafe")
        }
        return segments.joinToString("/")
    }

    private companion object {
        const val MAX_ENTRY_NAME_CHARS = 4 * 1024
    }
}

class DefaultProviderCryptoHostService(
    private val owner: ProviderResourceOwner,
    private val resources: ProviderResourceStore,
    private val maxOutputBytes: Int = 16 * 1024 * 1024,
) : ProviderCryptoHostService {

    init {
        require(maxOutputBytes > 0) { "Provider crypto output limit must be positive" }
    }

    override suspend fun aesCbcDecrypt(
        resourceHandle: ProviderResourceHandle,
        keyHex: String,
        ivHex: String,
    ): ProviderResourceHandle {
        val key = decodeHex(keyHex)
        val iv = decodeHex(ivHex)
        if (key.size !in setOf(16, 24, 32)) {
            throw ProviderHostServiceException("Provider AES key length is invalid")
        }
        if (iv.size != 16) {
            throw ProviderHostServiceException("Provider AES-CBC IV length is invalid")
        }

        val encrypted = resources.read(owner, resourceHandle)
        val decrypted = try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(iv),
                )
                doFinal(encrypted)
            }
        } catch (error: Exception) {
            throw ProviderHostServiceException("Provider AES-CBC decryption failed", error)
        }

        if (decrypted.size > maxOutputBytes) {
            throw ProviderHostServiceException("Provider crypto output exceeds the byte limit")
        }
        return resources.put(owner, ProviderResourceKind.BINARY, decrypted)
    }
}

private fun decodeHex(value: String): ByteArray {
    if (value.length % 2 != 0 || value.length > 128) {
        throw ProviderHostServiceException("Provider hex value is invalid")
    }
    return ByteArray(value.length / 2) { index ->
        val offset = index * 2
        value.substring(offset, offset + 2).toIntOrNull(16)?.toByte()
            ?: throw ProviderHostServiceException("Provider hex value is invalid")
    }
}

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0

    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            throw ProviderHostServiceException("Provider transform output exceeds the byte limit")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
