package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProviderHostTransformServicesTest {

    private val owner = ProviderResourceOwner(
        providerId = "org.example.reader",
        invocationId = "invocation-1",
    )
    private val store = ProviderResourceStore()

    @Test
    fun `dom selectors operate on host side html handles`() = runBlocking {
        val handle = store.put(
            owner = owner,
            kind = ProviderResourceKind.HTML,
            bytes = "<html><body><h1 id=\"title\">Tsuzuki</h1></body></html>".encodeToByteArray(),
        )
        val dom = DefaultProviderDomHostService(owner, store)

        dom.selectText(handle, "#title") shouldBe "Tsuzuki"
        (runCatching { dom.selectText(handle, "#missing") }.exceptionOrNull() is ProviderHostServiceException) shouldBe
            true
    }

    @Test
    fun `zip extraction keeps archive and entry bytes behind opaque handles`() = runBlocking {
        val archive = store.put(
            owner = owner,
            kind = ProviderResourceKind.ARCHIVE,
            bytes = zip("chapter/page.txt", "page-data"),
        )
        val binary = DefaultProviderBinaryTransformHostService(owner, store)

        val entry = binary.zipEntry(archive, "chapter/page.txt")

        store.read(owner, entry, ProviderResourceKind.BINARY).decodeToString() shouldBe "page-data"
    }

    @Test
    fun `aes cbc decrypt returns a new host resource without exposing bytes to script`() = runBlocking {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val encrypted = aesEncrypt(
            plaintext = "secret-page".encodeToByteArray(),
            key = key,
            iv = iv,
        )
        val encryptedHandle = store.put(owner, ProviderResourceKind.BINARY, encrypted)
        val crypto = DefaultProviderCryptoHostService(owner, store)

        val decrypted = crypto.aesCbcDecrypt(
            encryptedHandle,
            key.toHex(),
            iv.toHex(),
        )

        store.read(owner, decrypted, ProviderResourceKind.BINARY).decodeToString() shouldBe "secret-page"
    }

    private fun zip(path: String, value: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(value.encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun aesEncrypt(
        plaintext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv),
            )
            doFinal(plaintext)
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
