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
        // One unified signed-code10 lookup: the three source candidate counts
        // are diagnostic only, never a canonical identity grant.
        Regex(
            """MANGAALL_CI_SOURCE\|work=(punpun|koe|akame)""" +
                """\|source=(mangadex|mangadot|mangafire)""" +
                """\|candidates=[0-9]{1,2}\|lookup=(COMPLETE|PARTIAL)""",
        ),
        // Test-only CI Android emulator. No sensitive browser state, URLs,
        // cookies, challenge payloads or external IDs are allowed.
        // Separate CI-only MangaFire chapter/cursor proof; no HIDs or chapter
        // IDs may leave the instrumentation process.
        Regex(
            """MANGAALL_CI_CHAPTER\|phase=WARMUP""" +
                """\|outcome=(SKIPPED|PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|""" +
                """RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)""",
        ),
        Regex(
            """MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP_FAILURE""" +
                """\|cause=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|""" +
                """UNCLASSIFIED_ERROR)\|http=(NONE|200|403|OTHER)""",
        ),
        Regex(
            """MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=API""" +
                """\|signedUrl=(OBSERVED|NOT_OBSERVED)\|http=(NONE|200|403|OTHER)""" +
                """\|transport=(HTTP|BROWSER|NONE)\|json=(VALIDATED|UNVERIFIED)""",
        ),
        Regex(
            """MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)\|phase=LOOKUP""" +
                """\|outcome=(COMPLETE|CURSOR_LIMIT|READ_ERROR|TOO_LARGE|TIMEOUT|OTHER|""" +
                """INVALID_IDENTITY|IDENTITY_CONFLICT)\|pages=[0-8]\|candidates=[0-9]{1,2}""",
        ),
        Regex(
            """MANGAALL_CI_CHAPTER\|work=(punpun|koe|akame)""" +
                """\|outcome=(READY|INCONCLUSIVE)\|pages=[0-4]""" +
                """\|count=([0-9]|[1-9][0-9]|[1-9][0-9]{2})""" +
                """\|chapterOne=(YES|NO|MULTIPLE|UNVERIFIED)""" +
                """\|reason=(NONE|LOOKUP_AMBIGUOUS|LOOKUP_UNAVAILABLE|LOOKUP_INCOMPLETE|TIMEOUT|""" +
                """SOURCE_TOO_LARGE|SOURCE_READ_ERROR|OTHER|CURSOR_LIMIT|""" +
                """INVALID_IDENTITY|EMPTY|CHAPTER_ONE_NOT_ATTESTED)""",
        ),
        Regex(
            """MANGAALL_CI_CHAPTER\|outcome=(CHAPTERS_ACCEPTED|INCONCLUSIVE)""" +
                """\|ready=[0-3]\|works=[23]\|canonical=NOT_TESTED\|readerPixels=NOT_TESTED""",
        ),
        Regex("""MANGAALL_CI_WEBVIEW\|phase=STARTED"""),
        Regex(
            """MANGAALL_CI_WEBVIEW\|phase=WARMUP""" +
                """\|outcome=(PAGE_FINISHED|HTTP_REJECTED|TLS_REJECTED|""" +
                """RENDERER_EXITED|TIMEOUT|PROFILE_UNSUPPORTED)""",
        ),
        Regex(
            """MANGAALL_CI_WEBVIEW\|work=(punpun|koe|akame)""" +
                """\|api=(VALID_JSON|BLOCKED|NOT_OBSERVED)\|candidates=[0-9]{1,2}""",
        ),
        Regex(
            """MANGAALL_CI_WEBVIEW\|outcome=(CI_SIGNED_API_ACCEPTED|CI_INCONCLUSIVE)""" +
                """\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]""" +
                """\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED""",
        ),
        Regex("""MANGAALL_A54_PROFILE\|phase=(STARTED|ACTIVITY_REQUESTED|OPERATOR_CONFIRMED)"""),
        // Same-profile physical A54 validation. Only the precise closed fields
        // emitted by the manual, debug-only session test are permitted here.
        // Never export challenge content, cookies, URLs or upstream messages.
        Regex(
            """MANGAALL_A54_PROFILE\|outcome=INCONCLUSIVE""" +
                """\|reason=USER_VERIFICATION_NOT_COMPLETED""",
        ),
        Regex(
            """MANGAALL_A54_PROFILE\|work=(punpun|koe)\|outcome=INCONCLUSIVE""" +
                """\|reason=SIGNED_API_REQUEST_NOT_OBSERVED\|mangafireCandidates=[0-9]{1,2}""",
        ),
        Regex(
            """MANGAALL_A54_PROFILE\|work=(punpun|koe)""" +
                """\|outcome=(SIGNED_API_JSON_VALID|INCONCLUSIVE)""" +
                """\|mangafireCandidates=[0-9]{1,2}""" +
                """\|browserApi=(VALID_JSON|BLOCKED_OR_UNVERIFIED)""",
        ),
        Regex(
            """MANGAALL_A54_PROFILE\|outcome=(BROWSER_SIGNED_API_ACCEPTED|INCONCLUSIVE)""" +
                """\|signedJson=[0-2]\|works=2\|providerCandidateWorks=[0-2]""" +
                """\|canonical=NOT_TESTED\|chapters=NOT_TESTED\|readerPixels=NOT_TESTED""",
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
        // Code10 real Android chapter attestation: only closed Host diagnostic
        // enums. Never publish upstream messages, response bodies or CDN URLs.
        Regex(
            """MANGAALL_LIVE_PAGE_GATE\|outcome=INCONCLUSIVE\|reason=CHAPTER_ATTESTATION_UNAVAILABLE""" +
                """\|providerError=(""" +
                """UNAVAILABLE|HOST_ERROR|PACKAGE_INVALID|MALFORMED_REQUEST|SOURCE_TOO_LARGE|""" +
                """SOURCE_READ_ERROR|INVOCATION_CONFLICT|PERMISSION_DENIED|""" +
                """HOST_API_UNSUPPORTED|SCRIPT_ERROR|TIMEOUT|RUNTIME_DIED|NETWORK_POLICY|""" +
                """CHAPTER_IDENTITY_UNVERIFIED|NETWORK_ERROR|BROWSER_ERROR|RESOURCE_LIMIT|""" +
                """MALFORMED_RESULT|AUTH_REQUIRED|ACQUISITION_FAILED""" +
                """)\|sourceFailure=(NONE|CHALLENGE_BLOCKED|HTTP_ERROR|INVALID_RESPONSE|UNCLASSIFIED_ERROR)""" +
                """\|httpStatus=(NONE|[1-5][0-9]{2})\|retryable=(true|false)""" +
                """\|sourceRequests=([0-9]|[1-9][0-9])\|sourceHttp=(NONE|[1-5][0-9]{2})""",
        ),
        Regex(
            """MANGAALL_LIVE_PAGE_GATE\|outcome=PASS""" +
                """\|provenance=DIRECT_LIVE_ANDROID\|pagesDecoded=[12]""" +
                """\|readerActivityPixels=(NOT_TESTED|REAL_CDN_VISIBLE)""",
        ),
    )

    internal fun isPermitted(record: String): Boolean =
        record.length <= 500 && permitted.any { it.matches(record) }

    fun publish(record: String) {
        require(isPermitted(record)) {
            "Only closed, bounded MangaAll test verdicts can be published"
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", record) },
        )
    }
}
