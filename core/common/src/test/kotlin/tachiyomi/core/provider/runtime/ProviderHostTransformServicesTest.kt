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
    fun `dom selectors operate on host side html handles`(): Unit = runBlocking {
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
    fun `large html resource yields only unique bounded structured metadata`(): Unit = runBlocking {
        val json = """{"@type":"ComicSeries","name":"Work","author":{"@type":"Person","name":"Creator"}}"""
        val script = """<script type="application/ld+json">$json</script>"""
        val html = "<html><head>$script</head><body>" + "x".repeat(350 * 1024) + "</body></html>"
        val handle = store.put(owner, ProviderResourceKind.HTML, html.encodeToByteArray())
        val dom = DefaultProviderDomHostService(owner, store)
        dom.selectText(handle, "script[type=application/ld+json]") shouldBe json
        dom.selectText(handle, "script") shouldBe ""
        val other = DefaultProviderDomHostService(owner.copy(invocationId = "invocation-2"), store)
        runCatching { other.selectText(handle, "script[type=application/ld+json]") }.isFailure shouldBe true
    }

    @Test
    fun `ordinary DOM text retains its character bound`(): Unit = runBlocking {
        val value = "é".repeat(40 * 1024)
        val handle = store.put(owner, ProviderResourceKind.HTML, "<h1>$value</h1>".encodeToByteArray())
        val dom = DefaultProviderDomHostService(owner, store)
        dom.selectText(handle, "h1") shouldBe value
    }

    @Test
    fun `structured metadata rejects duplicate or oversized script blocks`(): Unit = runBlocking {
        val script = """<script type="application/ld+json">{"name":"Work"}</script>"""
        val dom = DefaultProviderDomHostService(owner, store)
        val duplicate = store.put(owner, ProviderResourceKind.HTML, (script + script).encodeToByteArray())
        runCatching { dom.selectText(duplicate, "script[type=application/ld+json]") }.isFailure shouldBe true
        val oversized = """<script type="application/ld+json">""" + "é".repeat(40 * 1024) + "</script>"
        val handle = store.put(owner, ProviderResourceKind.HTML, oversized.encodeToByteArray())
        runCatching { dom.selectText(handle, "script[type=application/ld+json]") }.isFailure shouldBe true
    }

    @Test
    fun `unique semantic text rejects duplicate matches without changing ordinary selectors`(): Unit = runBlocking {
        val html = "<div><span class='author'>Writer</span><span class='author'>Other</span>" +
            "<span class='artist'>Artist</span></div>"
        val handle = store.put(owner, ProviderResourceKind.HTML, html.encodeToByteArray())
        val dom = DefaultProviderDomHostService(owner, store)
        dom.selectText(handle, ".author") shouldBe "Writer"
        dom.selectText(handle, ":tsuzuki-unique .artist") shouldBe "Artist"
        runCatching { dom.selectText(handle, ":tsuzuki-unique .author") }.isFailure shouldBe true
        runCatching { dom.selectText(handle, ":tsuzuki-unique .missing") }.isFailure shouldBe true
    }

    @Test
    fun `zip extraction keeps archive and entry bytes behind opaque handles`(): Unit = runBlocking {
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
    fun `binary resource promotion stays host owned and returns only opaque managed token`(): Unit = runBlocking {
        val handle = store.put(
            owner = owner,
            kind = ProviderResourceKind.BINARY,
            bytes = "chapter-cbz".encodeToByteArray(),
        )
        var promotedBytes: ByteArray? = null
        var promotedFormat: ProviderManagedResourceFormat? = null
        val binary = DefaultProviderBinaryTransformHostService(
            owner = owner,
            resources = store,
            promoter = { bytes, format ->
                promotedBytes = bytes
                promotedFormat = format
                "managed:chapter-token"
            },
        )

        binary.promote(handle, ProviderManagedResourceFormat.CBZ) shouldBe "managed:chapter-token"
        promotedBytes?.decodeToString() shouldBe "chapter-cbz"
        promotedFormat shouldBe ProviderManagedResourceFormat.CBZ
    }

    @Test
    fun `aes cbc decrypt returns a new host resource without exposing bytes to script`(): Unit = runBlocking {
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
