package tachiyomi.core.provider.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderP2pChapterImageRequestTest {

    private val images = listOf(
        ProviderP2pChapterImage(0, "Tokyo Ghoul Manga/Chapter 01/001.jpg", 120L),
        ProviderP2pChapterImage(1, "Tokyo Ghoul Manga/Chapter 01/002.jpg", 240L),
    )

    private fun request(images: List<ProviderP2pChapterImage> = this.images) = ProviderP2pAcquireRequest(
        operationId = "read:chapter-1",
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        selectedFileIndex = 0,
        selectedFilePath = "Tokyo Ghoul Manga/Chapter 01/001.jpg",
        selectedFileSizeBytes = 120L,
        selectedChapterImages = images,
    )

    @Test
    fun `Host-owned chapter image list retains exact file identities across the bounded protocol`() {
        val approved = request()
        ProviderP2pProtocol.decodeRequest(ProviderP2pProtocol.encodeRequest(approved)) shouldBe approved
        // Older JS providers echo only the representative page.
        val scriptEcho = approved.copy(selectedChapterImages = emptyList())
        ProviderP2pProtocol.decodeRequest(ProviderP2pProtocol.encodeRequest(scriptEcho)) shouldBe scriptEcho
    }

    @Test
    fun `chapter image request rejects traversal duplicate indices and sibling release images`() {
        shouldThrow<IllegalArgumentException> {
            request(images.take(1) + ProviderP2pChapterImage(2, "../other/002.jpg", 50))
        }
        shouldThrow<IllegalArgumentException> {
            request(images.take(1) + images.last().copy(index = 0))
        }
        shouldThrow<IllegalArgumentException> {
            request(images.take(1) + ProviderP2pChapterImage(2, "Tokyo Ghoul Manga/Chapter 02/002.jpg", 50))
        }
    }

    @Test
    fun `chapter image request requires verified first file and bounded nonzero page sizes`() {
        shouldThrow<IllegalArgumentException> {
            request(images.map { it.copy(sizeBytes = 0) })
        }
        shouldThrow<IllegalArgumentException> {
            request(images.reversed())
        }
        shouldThrow<IllegalArgumentException> {
            request(images.take(1) + images.last().copy(sizeBytes = 257L * 1024L * 1024L))
        }
    }
}
