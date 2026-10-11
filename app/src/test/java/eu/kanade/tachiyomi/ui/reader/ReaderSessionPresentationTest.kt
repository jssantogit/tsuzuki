package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentAcquisitionException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionFailure
import java.io.IOException

class ReaderSessionPresentationTest {
    private val chapters = listOf(
        chapter("twelve", "12"),
        chapter("fraction", "12.5"),
        chapter("suffix", "12a"),
        chapter("special", "", "Special"),
        chapter("gap", "18"),
    )

    @Test
    fun `session starts without a card or protection tint`() {
        ReaderSessionPresentation().card shouldBe null
        ReaderSessionPresentation().protection shouldBe ReaderVisualProtection.OFF
        ReaderVisualProtection.OFF.tintArgb shouldBe null
    }

    @Test
    fun `opening another card replaces the first and dismiss keeps protection`() {
        val initial = ReaderSessionPresentation(protection = ReaderVisualProtection.WARM)
        val changed = initial.open(ReaderSessionTool.CHAPTERS).open(ReaderSessionTool.BRIGHTNESS)
        changed.card shouldBe ReaderSessionTool.BRIGHTNESS
        changed.dismiss().card shouldBe null
        changed.dismiss().protection shouldBe ReaderVisualProtection.WARM
    }

    @Test
    fun `direct actions are not contextual cards`() {
        ReaderSessionPresentation().open(ReaderSessionTool.PREVIOUS).card shouldBe null
        ReaderSessionPresentation().open(ReaderSessionTool.NEXT).card shouldBe null
        ReaderSessionPresentation().open(ReaderSessionTool.KEEP_SCREEN_ON).card shouldBe null
    }

    @Test
    fun `protection changes only session state`() {
        val initial = ReaderSessionPresentation(card = ReaderSessionTool.VISUAL_PROTECTION)
        val changed = initial.withProtection(ReaderVisualProtection.COOL)
        changed.protection shouldBe ReaderVisualProtection.COOL
        initial.protection shouldBe ReaderVisualProtection.OFF
        changed.card shouldBe initial.card
        ReaderVisualProtection.entries.filter { it != ReaderVisualProtection.OFF }
            .all { it.tintArgb != null } shouldBe true
    }

    @Test
    fun `tools preserve approved order and omit unsupported capabilities`() {
        availableReaderTools(canonical = true, fit = true) shouldBe ReaderSessionTool.entries
        availableReaderTools(canonical = false, fit = false) shouldBe listOf(
            ReaderSessionTool.PREVIOUS,
            ReaderSessionTool.NEXT,
            ReaderSessionTool.BRIGHTNESS,
            ReaderSessionTool.CROP,
            ReaderSessionTool.KEEP_SCREEN_ON,
            ReaderSessionTool.VISUAL_PROTECTION,
        )
    }

    @Test
    fun `progress uses a real zero based page and total`() {
        readerPageProgress(0, 17) shouldBe ReaderPageProgress(1, 17)
        readerPageProgress(16, 17) shouldBe ReaderPageProgress(17, 17)
    }

    @Test
    fun `unknown and invalid progress is omitted`() {
        readerPageProgress(-1, -1) shouldBe null
        readerPageProgress(0, 0) shouldBe null
        readerPageProgress(17, 17) shouldBe null
    }

    @Test
    fun `repository order and gaps remain intact`() {
        readerCanonicalChapters(chapters).map { it.id } shouldBe chapters.map { it.id }
        readerCanonicalChapters(chapters).map { it.displayNumber } shouldBe listOf("12", "12.5", "12a", "", "18")
    }

    @Test
    fun `jump distinguishes twelve fractional and suffix`() {
        resolveReaderChapterJump(chapters, "12")?.id shouldBe "twelve"
        resolveReaderChapterJump(chapters, "12.5")?.id shouldBe "fraction"
        resolveReaderChapterJump(chapters, "12a")?.id shouldBe "suffix"
    }

    @Test
    fun `jump reaches exact canonical identity and special title`() {
        resolveReaderChapterJump(chapters, "special")?.id shouldBe "special"
        resolveReaderChapterJump(chapters, "Special")?.id shouldBe "special"
        resolveReaderChapterJump(chapters, " fraction ")?.id shouldBe "fraction"
    }

    @Test
    fun `missing and blank targets never fabricate a chapter`() {
        resolveReaderChapterJump(chapters, "13") shouldBe null
        resolveReaderChapterJump(chapters, "") shouldBe null
    }

    @Test
    fun `current position uses canonical identity instead of numeric chapter`() {
        readerChapterPosition(chapters, "suffix") shouldBe 2
        readerChapterPosition(chapters, "missing") shouldBe 0
    }

    @Test
    fun `typed P2P consent remains a distinct actionable requirement`() {
        classifyReaderRecovery(
            ProviderTorrentAcquisitionException(TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED),
        ) shouldBe ReaderRecovery.P2P_REQUIRED
    }

    @Test
    fun `recognized canonical content failures are recoverable`() {
        classifyReaderRecovery(IOException("content could not load")) shouldBe ReaderRecovery.SOURCE_UNAVAILABLE
        classifyReaderRecovery(
            ProviderTorrentAcquisitionException(TorrentAcquisitionFailure.UNAVAILABLE),
        ) shouldBe ReaderRecovery.SOURCE_UNAVAILABLE
    }

    @Test
    fun `unexpected programming and cancellation errors remain outside recovery`() {
        classifyReaderRecovery(IllegalStateException("invariant violation")) shouldBe null
        classifyReaderRecovery(NullPointerException()) shouldBe null
        classifyReaderRecovery(kotlinx.coroutines.CancellationException()) shouldBe null
    }

    private fun chapter(id: String, number: String, title: String? = null) = CanonicalChapter(
        id = id,
        canonicalTitleId = "work",
        displayNumber = number,
        title = title,
    )
}
