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
            """MANGAALL_BATCH_AUDIT\|outcome=INCONCLUSIVE\|reason=LOOKUP_UNAVAILABLE""" +
                """\|code=[A-Z_]{3,40}\|sourceFailure=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|""" +
                """INVALID_RESPONSE|UNCLASSIFIED_ERROR)""" +
                """\|httpStatus=(NONE|[1-5][0-9]{2})\|retryable=(true|false)""",
        ),
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
        // Accept only closed Host page diagnostic enums for the signed code9 live gate.
        // Generic code8 inconclusive records above remain unchanged.
        Regex(
            """MANGAALL_LIVE_PAGE_GATE\|outcome=INCONCLUSIVE\|reason=PROVIDER_PAGES_UNAVAILABLE""" +
                """\|providerError=(""" +
                """UNAVAILABLE|HOST_ERROR|PACKAGE_INVALID|MALFORMED_REQUEST|SOURCE_TOO_LARGE|""" +
                """SOURCE_READ_ERROR|INVOCATION_CONFLICT|PERMISSION_DENIED|""" +
                """HOST_API_UNSUPPORTED|SCRIPT_ERROR|TIMEOUT|RUNTIME_DIED|NETWORK_POLICY|""" +
                """CHAPTER_IDENTITY_UNVERIFIED|NETWORK_ERROR|BROWSER_ERROR|RESOURCE_LIMIT|""" +
                """MALFORMED_RESULT|AUTH_REQUIRED|ACQUISITION_FAILED""" +
                """)\|pageFailureStage=(""" +
                """NONE|INVALID_CHAPTER_ID|CHAPTER_DETAIL_REQUEST_FAILED|CHAPTER_WORK_MISMATCH|""" +
                """CHAPTER_LANGUAGE_MISMATCH|EXTERNAL_CHAPTER|AT_HOME_REQUEST_FAILED|""" +
                """UNTRUSTED_IMAGE_ORIGIN|INVALID_PAGE_METADATA""" +
                """)""",
        ),
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
