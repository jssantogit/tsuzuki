package eu.kanade.tachiyomi.ui.reader

import eu.kanade.presentation.tsuzuki.detail.resolveChapterJump
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentAcquisitionException
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionFailure
import java.io.IOException

enum class ReaderSessionTool {
    CHAPTERS,
    PREVIOUS,
    NEXT,
    BRIGHTNESS,
    FIT,
    CROP,
    SOURCE,
    KEEP_SCREEN_ON,
    VISUAL_PROTECTION,
}

/** Additional page comfort only. No preference, authentication or security dependencies. */
enum class ReaderVisualProtection(val tintArgb: Int?) {
    OFF(null),
    WARM(0x26F4B86A),
    COOL(0x204E91B9),
    SOFT(0x228A927C),
}

data class ReaderSessionPresentation(
    val card: ReaderSessionTool? = null,
    val protection: ReaderVisualProtection = ReaderVisualProtection.OFF,
) {
    fun open(tool: ReaderSessionTool): ReaderSessionPresentation = copy(
        card = tool.takeUnless {
            it == ReaderSessionTool.PREVIOUS || it == ReaderSessionTool.NEXT ||
                it == ReaderSessionTool.KEEP_SCREEN_ON
        },
    )
    fun dismiss(): ReaderSessionPresentation = copy(card = null)
    fun withProtection(mode: ReaderVisualProtection): ReaderSessionPresentation = copy(protection = mode)
}

fun availableReaderTools(canonical: Boolean, fit: Boolean): List<ReaderSessionTool> =
    ReaderSessionTool.entries.filter {
        (canonical || (it != ReaderSessionTool.CHAPTERS && it != ReaderSessionTool.SOURCE)) &&
            (fit || it != ReaderSessionTool.FIT)
    }

data class ReaderPageProgress(val current: Int, val total: Int)

/** Reader state is already one-based; unknown/out-of-range values have no count. */
fun readerPageProgress(current: Int, total: Int): ReaderPageProgress? =
    ReaderPageProgress(current, total).takeIf { total > 0 && current in 1..total }

/** Repository structured ordering is authoritative. This adapter neither sorts nor numbers. */
fun readerCanonicalChapters(chapters: List<CanonicalChapter>): List<CanonicalChapter> = chapters.toList()

fun resolveReaderChapterJump(chapters: List<CanonicalChapter>, input: String): CanonicalChapter? =
    resolveChapterJump(
        chapters.map {
            CanonicalChapterDetailItem(chapter = it, progress = null, downloaded = false)
        },
        input,
    )
        ?.chapter

fun readerChapterPosition(chapters: List<CanonicalChapter>, currentId: String?): Int =
    chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

enum class ReaderRecovery { SOURCE_UNAVAILABLE, P2P_REQUIRED }

/** Only known content failures qualify; unexpected programming errors retain fatal handling. */
fun classifyReaderRecovery(error: Throwable): ReaderRecovery? = when (error) {
    is ProviderTorrentAcquisitionException -> if (error.reason == TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED) {
        ReaderRecovery.P2P_REQUIRED
    } else {
        ReaderRecovery.SOURCE_UNAVAILABLE
    }
    is IOException -> ReaderRecovery.SOURCE_UNAVAILABLE
    else -> null
}
