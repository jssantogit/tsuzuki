package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Closed, explicit Android instrumentation hand-off to the in-memory CI parser.
 *
 * System.out.println inside an AndroidJUnit4 test is not guaranteed to appear
 * as an am-instrument status record. The host only accepts this status stream,
 * requires a successful single-test instrumentation result and discards raw output.
 *
 * Provider IDs are emitted only into a private captured process pipe, never
 * directly to GitHub Actions logs or persisted CI artifacts.
 */
internal object MangaAllInstrumentationVerdict {
    private val permitted = listOf(
        Regex("""MANGAALL_STATUS_PROBE\|outcome=VISIBLE"""),
        Regex("""MANGAALL_BATCH_AUDIT\|outcome=INCONCLUSIVE\|reason=[A-Z_]{3,64}"""),
        Regex(
            """MANGAALL_BATCH_AUDIT\|outcome=COMPLETE\|sources=3""" +
                """\|titleParity=(CONSISTENT|VARIANCE|UNVERIFIED)""" +
                """\|authorParity=(CONSISTENT|VARIANCE|UNVERIFIED)""",
        ),
        Regex(
            """MANGAALL_BATCH_SOURCE\|source=(mangadex|mangadot|mangafire)""" +
                """\|outcome=(MISSING|AMBIGUOUS|CHAPTERS_UNAVAILABLE|CHAPTER_ONE_NOT_ATTESTED|""" +
                """CHAPTER_CURSOR_LIMIT|AMBIGUOUS_CHAPTER_ONE|INVALID_SOURCE_IDENTITY)""" +
                """\|candidates=[0-9]{1,5}""",
        ),
        Regex(
            """MANGAALL_BATCH_SOURCE\|source=(mangadex|mangadot|mangafire)""" +
                """\|outcome=READY\|candidates=1""" +
                """\|workId=(mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}""" +
                """\|chapterId=[A-Za-z0-9._:-]{1,160}\|language=en""",
        ),
        Regex("""MANGAALL_LIVE_PAGE_GATE\|outcome=INCONCLUSIVE\|reason=[A-Z_]{3,64}"""),
        Regex(
            """MANGAALL_LIVE_PAGE_GATE\|outcome=PASS""" +
                """\|provenance=DIRECT_LIVE_ANDROID\|pagesDecoded=[12]""" +
                """\|readerActivityPixels=(NOT_TESTED|REAL_CDN_VISIBLE)""",
        ),
    )

    fun publish(record: String) {
        require(record.length <= 500 && permitted.any { it.matches(record) }) {
            "Only closed, bounded MangaAll test verdicts can be published"
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", record) },
        )
    }
}
