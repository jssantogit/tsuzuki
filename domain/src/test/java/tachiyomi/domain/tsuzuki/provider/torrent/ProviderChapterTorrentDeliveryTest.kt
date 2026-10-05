package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ProviderChapterTorrentDeliveryTest {

    @Test
    fun `exact Provider option maps to Reader torrent delivery`() {
        val file = TorrentCandidateFile(
            index = 0,
            path = "Example Manga - Vol 2 Ch 12.cbz",
        )
        val option = ProviderChapterTorrentOption(
            canonicalChapterId = "chapter-12",
            providerId = ProviderId("org.example.torrent"),
            candidate = TorrentCandidate(
                infoHash = "0123456789abcdef0123456789abcdef01234567",
                magnetUri = null,
                torrentUrl = null,
                displayName = "Example Manga",
                files = listOf(file),
            ),
            selectedFile = file,
        )

        option.toContentDelivery() shouldBe ContentDelivery.Torrent(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = null,
            fileIndex = 0,
            filePath = file.path,
        )
    }
}
