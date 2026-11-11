package tachiyomi.domain.tsuzuki.diagnostics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StructuredDiagnosticEventTest {

    @Test
    fun `UI stall events preserve duration but never accept free form stack or URL`() {
        val attributes = mapOf(
            "untrusted_stack_trace" to DiagnosticAttributeValue.Text("Authorization: secret"),
            "request_url" to DiagnosticAttributeValue.Text("https://example.invalid/?token=secret"),
        )
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000L,
            severity = DiagnosticSeverity.WARN,
            subsystem = DiagnosticSubsystem.APP,
            name = DiagnosticEventName.APP_FRAME_STALL,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = null,
            stage = DiagnosticStage.RENDER,
            outcome = DiagnosticOutcome.PARTIAL,
            durationMillis = 420L,
            attributes = attributes,
        )
        val sanitized = requireNotNull(StructuredDiagnosticSanitizer.sanitize(event))
        sanitized.durationMillis shouldBe 420L
        sanitized.attributes shouldBe emptyMap()
    }

    @Test
    fun `Provider malformed reply diagnostic keeps closed stage and drops untrusted exception data`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000L,
            severity = DiagnosticSeverity.DEBUG,
            subsystem = DiagnosticSubsystem.CHAPTER,
            name = DiagnosticEventName.PROVIDER_READING_LOOKUP_ATTEMPT,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = null,
            stage = DiagnosticStage.LOOKUP,
            outcome = DiagnosticOutcome.FAILED,
            attributes = mapOf(
                "provider_malformed_stage" to DiagnosticAttributeValue.Code(
                    DiagnosticProviderMalformedResultStage.SCHEMA_DECODE,
                ),
                "untrusted_json" to DiagnosticAttributeValue.Text("token=secret"),
            ),
        )
        requireNotNull(StructuredDiagnosticSanitizer.sanitize(event)).attributes shouldBe
            mapOf(
                DiagnosticAttribute.PROVIDER_MALFORMED_STAGE to DiagnosticAttributeValue.Code(
                    DiagnosticProviderMalformedResultStage.SCHEMA_DECODE,
                ),
            )
    }

    @Test
    fun `canonical refresh phases accept typed causes and reject arbitrary strings`() {
        fun attributes(values: Map<String, DiagnosticAttributeValue>) =
            requireNotNull(
                StructuredDiagnosticSanitizer.sanitize(
                    StructuredDiagnosticEvent(
                        timestampMillis = 1_790_755_200_000L,
                        severity = DiagnosticSeverity.DEBUG,
                        subsystem = DiagnosticSubsystem.CHAPTER,
                        name = DiagnosticEventName.CHAPTER_RECONCILE_PHASE,
                        sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                        operationId = null,
                        stage = DiagnosticStage.RECONCILE,
                        outcome = DiagnosticOutcome.SUCCEEDED,
                        durationMillis = 82L,
                        attributes = values,
                    ),
                ),
            ).attributes

        attributes(
            mapOf(
                "chapter_reconcile_phase" to DiagnosticAttributeValue.Code(
                    DiagnosticChapterReconcilePhase.DELTA_WRITE,
                ),
                "chapter_ui_phase" to DiagnosticAttributeValue.Code(DiagnosticChapterUiPhase.ROWS_BUILD),
                "item_count" to DiagnosticAttributeValue.Number(2231),
            ),
        ) shouldBe mapOf(
            DiagnosticAttribute.CHAPTER_RECONCILE_PHASE to DiagnosticAttributeValue.Code(
                DiagnosticChapterReconcilePhase.DELTA_WRITE,
            ),
            DiagnosticAttribute.CHAPTER_UI_PHASE to DiagnosticAttributeValue.Code(
                DiagnosticChapterUiPhase.ROWS_BUILD,
            ),
            DiagnosticAttribute.ITEM_COUNT to DiagnosticAttributeValue.Number(2231),
        )
        attributes(
            mapOf("chapter_reconcile_phase" to DiagnosticAttributeValue.Text("DELTA_WRITE; cookie=secret")),
        ) shouldBe emptyMap()
    }

    @Test
    fun `isolated runtime phases remain closed and cannot accept arbitrary source diagnostics`() {
        fun sanitize(attributes: Map<String, DiagnosticAttributeValue>) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.DEBUG,
                subsystem = DiagnosticSubsystem.NETWORK,
                name = DiagnosticEventName.PROVIDER_RUNTIME_STAGE,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.BINDING,
                outcome = DiagnosticOutcome.REUSED,
                attributes = attributes,
            ),
        )?.attributes.orEmpty()

        sanitize(
            mapOf(
                "provider_runtime_phase" to DiagnosticAttributeValue.Code(
                    DiagnosticProviderRuntimePhase.BIND_REUSED,
                ),
            ),
        ) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_RUNTIME_PHASE to DiagnosticAttributeValue.Code(
                DiagnosticProviderRuntimePhase.BIND_REUSED,
            ),
        )

        sanitize(
            mapOf(
                "provider_runtime_phase" to DiagnosticAttributeValue.Text("BIND_REUSED; cookie=secret"),
                "request_url" to DiagnosticAttributeValue.Text("https://example.org/?token=secret"),
            ),
        ) shouldBe emptyMap()
    }

    @Test
    fun `Reader empty source audit keeps only closed causes and bounded counts`() {
        fun sanitize(values: Map<String, DiagnosticAttributeValue>) =
            StructuredDiagnosticSanitizer.sanitize(
                StructuredDiagnosticEvent(
                    timestampMillis = 1_790_755_200_000L,
                    severity = DiagnosticSeverity.INFO,
                    subsystem = DiagnosticSubsystem.CONTENT,
                    name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                    sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                    operationId = null,
                    stage = DiagnosticStage.BINDING,
                    outcome = DiagnosticOutcome.EMPTY,
                    attributes = values,
                ),
            )?.attributes.orEmpty()

        val expectedReason = DiagnosticAttributeValue.Code(
            DiagnosticProviderReaderNoMatchReason.NO_MATCHING_SOURCE_CHAPTER,
        )
        sanitize(
            mapOf(
                "provider_reader_no_match_reason" to expectedReason,
                "provider_reader_eligible_source_count" to DiagnosticAttributeValue.Number(1),
                "provider_reader_observed_evidence_count" to DiagnosticAttributeValue.Number(1250),
                "provider_reader_matching_number_count" to DiagnosticAttributeValue.Number(0),
                "provider_reader_mapped_evidence_count" to DiagnosticAttributeValue.Number(0),
                "provider_reader_eligible_mapped_count" to DiagnosticAttributeValue.Number(0),
            ),
        ) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_READER_NO_MATCH_REASON to expectedReason,
            DiagnosticAttribute.PROVIDER_READER_ELIGIBLE_SOURCE_COUNT to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.PROVIDER_READER_OBSERVED_EVIDENCE_COUNT to DiagnosticAttributeValue.Number(1250),
            DiagnosticAttribute.PROVIDER_READER_MATCHING_NUMBER_COUNT to DiagnosticAttributeValue.Number(0),
            DiagnosticAttribute.PROVIDER_READER_MAPPED_EVIDENCE_COUNT to DiagnosticAttributeValue.Number(0),
            DiagnosticAttribute.PROVIDER_READER_ELIGIBLE_MAPPED_COUNT to DiagnosticAttributeValue.Number(0),
        )
        sanitize(
            mapOf(
                "provider_reader_no_match_reason" to
                    DiagnosticAttributeValue.Text("NO_MATCHING_SOURCE_CHAPTER; bearer secret"),
                "provider_reader_observed_evidence_count" to DiagnosticAttributeValue.Number(-1),
                "provider_reader_matching_number_count" to DiagnosticAttributeValue.Number(2_000_000),
            ),
        ) shouldBe emptyMap()
    }

    @Test
    fun `mapped Reader source exclusion retains only typed source cause`() {
        fun sanitized(value: DiagnosticAttributeValue) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000L,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.BINDING,
                outcome = DiagnosticOutcome.EMPTY,
                attributes = mapOf(
                    "provider_reader_mapped_source_ineligibility_reason" to value,
                    "provider_work_id" to DiagnosticAttributeValue.Text("mangafire:secret-id"),
                    "reader_url" to DiagnosticAttributeValue.Text("https://example.test/?token=private"),
                ),
            ),
        )?.attributes.orEmpty()

        val reason = DiagnosticAttributeValue.Code(
            DiagnosticProviderReaderMappedSourceIneligibilityReason.FACET_UNAVAILABLE,
        )
        sanitized(reason) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_READER_MAPPED_SOURCE_INELIGIBILITY_REASON to reason,
        )
        sanitized(DiagnosticAttributeValue.Text("FACET_UNAVAILABLE; Authorization: secret")) shouldBe emptyMap()
        sanitized(DiagnosticAttributeValue.Code(DiagnosticProviderReaderNoMatchReason.NO_ELIGIBLE_SOURCE)) shouldBe
            emptyMap()
    }

    @Test
    fun `page-stage diagnostics keep closed values and reject source-authored content`() {
        fun attributes(value: DiagnosticAttributeValue) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000L,
                severity = DiagnosticSeverity.WARN,
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_PAGES_READY,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.READER,
                outcome = DiagnosticOutcome.TYPED_FAILURE,
                attributes = mapOf("provider_page_failure_stage" to value),
            ),
        )?.attributes.orEmpty()

        val stage = DiagnosticAttributeValue.Code(
            DiagnosticProviderPageFailureStage.AT_HOME_REQUEST_FAILED,
        )
        attributes(stage) shouldBe mapOf(DiagnosticAttribute.PROVIDER_PAGE_FAILURE_STAGE to stage)
        attributes(DiagnosticAttributeValue.Text("AT_HOME_REQUEST_FAILED; token=private")) shouldBe emptyMap()
        attributes(DiagnosticAttributeValue.Code(DiagnosticProviderReaderNoMatchReason.NO_PROVIDER_EVIDENCE)) shouldBe
            emptyMap()
    }

    @Test
    fun `Reader identity rejection reason is a closed diagnostic code`() {
        fun audited(value: DiagnosticAttributeValue) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000L,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.BINDING,
                outcome = DiagnosticOutcome.EMPTY,
                attributes = mapOf("provider_reader_identity_rejection_reason" to value),
            ),
        )?.attributes.orEmpty()

        val reason = DiagnosticAttributeValue.Code(
            DiagnosticProviderReaderIdentityRejectionReason.CANONICAL_VOLUME_CONFLICT,
        )
        audited(reason) shouldBe mapOf(DiagnosticAttribute.PROVIDER_READER_IDENTITY_REJECTION_REASON to reason)
        val conflictingChapter = DiagnosticAttributeValue.Code(
            DiagnosticProviderReaderIdentityRejectionReason.CONFLICTING_CHAPTER_MARKERS,
        )
        audited(conflictingChapter) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_READER_IDENTITY_REJECTION_REASON to conflictingChapter,
        )
        audited(DiagnosticAttributeValue.Text("unverified text")) shouldBe emptyMap()
        audited(DiagnosticAttributeValue.Code(DiagnosticProviderReaderNoMatchReason.IDENTITY_REJECTED)) shouldBe
            emptyMap()
    }

    @Test
    fun `selected Reader source metadata accepts only numbers and closed flags`() {
        fun attributes(raw: Map<String, DiagnosticAttributeValue>) =
            StructuredDiagnosticSanitizer.sanitize(
                StructuredDiagnosticEvent(
                    timestampMillis = 1_790_755_200_000L,
                    severity = DiagnosticSeverity.INFO,
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_SOURCE_SELECTED,
                    sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                    operationId = null,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    attributes = raw,
                ),
            )?.attributes.orEmpty()

        attributes(
            mapOf(
                "provider_reader_source_number_milli" to DiagnosticAttributeValue.Number(0),
                "provider_reader_source_volume" to DiagnosticAttributeValue.Number(1),
                "provider_reader_label_has_volume_marker" to DiagnosticAttributeValue.Flag(true),
                "provider_reader_title_is_volume_container" to DiagnosticAttributeValue.Flag(false),
                "provider_reader_title_present" to DiagnosticAttributeValue.Flag(true),
            ),
        ) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_READER_SOURCE_NUMBER_MILLI to DiagnosticAttributeValue.Number(0),
            DiagnosticAttribute.PROVIDER_READER_SOURCE_VOLUME to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.PROVIDER_READER_LABEL_HAS_VOLUME_MARKER to DiagnosticAttributeValue.Flag(true),
            DiagnosticAttribute.PROVIDER_READER_TITLE_IS_VOLUME_CONTAINER to DiagnosticAttributeValue.Flag(false),
            DiagnosticAttribute.PROVIDER_READER_TITLE_PRESENT to DiagnosticAttributeValue.Flag(true),
        )
        attributes(
            mapOf(
                "provider_reader_source_number_milli" to DiagnosticAttributeValue.Number(-1),
                "provider_reader_source_volume" to DiagnosticAttributeValue.Number(11111),
                "provider_reader_title_present" to DiagnosticAttributeValue.Text("Authorization: secret"),
            ),
        ) shouldBe emptyMap()
    }

    @Test
    fun `Reader detailed capture preserves zero as a number but rejects invalid chapter hints`() {
        fun sanitize(value: DiagnosticAttributeValue) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_SOURCE_SELECTED,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.READER,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf("canonical_chapter_base_number" to value),
            ),
        )?.attributes.orEmpty()
        sanitize(DiagnosticAttributeValue.Number(0))[DiagnosticAttribute.CANONICAL_CHAPTER_BASE_NUMBER] shouldBe
            DiagnosticAttributeValue.Number(0)
        sanitize(DiagnosticAttributeValue.Number(-1)) shouldBe emptyMap()
        sanitize(DiagnosticAttributeValue.Text("0; Authorization: secret")) shouldBe emptyMap()
    }

    @Test
    fun `Reader chapter diagnostics accept only bounded numeric public source chapter IDs`() {
        fun sanitizeChapter(value: String) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_SOURCE_SELECTED,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.READER,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf("provider_chapter_id" to DiagnosticAttributeValue.Text(value)),
            ),
        )?.attributes.orEmpty()

        sanitizeChapter("7540238")[DiagnosticAttribute.PROVIDER_CHAPTER_ID] shouldBe
            DiagnosticAttributeValue.Text("7540238")
        listOf("secret:chapter", "https://example.com?token=secret", "12\\nAuthorization: token", "abc", "0".repeat(33))
            .forEach { unsafe -> sanitizeChapter(unsafe) shouldBe emptyMap() }
    }

    @Test
    fun `provider candidate diagnostics accept only public bounded source identifiers`() {
        fun sanitizeCandidate(attributes: Map<String, DiagnosticAttributeValue>) =
            StructuredDiagnosticSanitizer.sanitize(
                StructuredDiagnosticEvent(
                    timestampMillis = 1_790_755_200_000,
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_CANDIDATE,
                    sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                    operationId = null,
                    stage = DiagnosticStage.LOOKUP,
                    outcome = DiagnosticOutcome.REJECTED,
                    attributes = attributes,
                ),
            )?.attributes.orEmpty()

        val safe = sanitizeCandidate(
            mapOf(
                "provider_work_id" to DiagnosticAttributeValue.Text("qnlvj"),
                "provider_work_title" to DiagnosticAttributeValue.Text("Vagabond"),
                "provider_work_url" to DiagnosticAttributeValue.Text("https://mangafire.to/title/qnlvj-vagabond22"),
                "provider_work_chapter_count" to DiagnosticAttributeValue.Number(327),
                "provider_coverage_outcome" to DiagnosticAttributeValue.Code(
                    DiagnosticProviderCoverageOutcome.OBSERVED,
                ),
                "provider_coverage_observed_count" to DiagnosticAttributeValue.Number(101),
                "provider_coverage_has_more" to DiagnosticAttributeValue.Flag(true),
                "chapter_number_min_milli" to DiagnosticAttributeValue.Number(1000),
                "chapter_number_max_milli" to DiagnosticAttributeValue.Number(101000),
                "provider_detail_outcome" to DiagnosticAttributeValue.Code(
                    DiagnosticProviderDetailOutcome.DETAIL_FAILED,
                ),
                "http_status" to DiagnosticAttributeValue.Number(503),
                "provider_candidate_reason" to DiagnosticAttributeValue.Code(
                    DiagnosticProviderCandidateReason.ALIAS_MATCH_BLOCKED,
                ),
            ),
        )
        safe[DiagnosticAttribute.PROVIDER_WORK_ID] shouldBe DiagnosticAttributeValue.Text("qnlvj")
        safe[DiagnosticAttribute.PROVIDER_WORK_TITLE] shouldBe DiagnosticAttributeValue.Text("Vagabond")
        safe[DiagnosticAttribute.PROVIDER_WORK_URL] shouldBe
            DiagnosticAttributeValue.Text("https://mangafire.to/title/qnlvj-vagabond22")
        safe[DiagnosticAttribute.PROVIDER_WORK_CHAPTER_COUNT] shouldBe DiagnosticAttributeValue.Number(327)
        safe[DiagnosticAttribute.PROVIDER_COVERAGE_OUTCOME] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticProviderCoverageOutcome.OBSERVED)
        safe[DiagnosticAttribute.PROVIDER_COVERAGE_OBSERVED_COUNT] shouldBe DiagnosticAttributeValue.Number(101)
        safe[DiagnosticAttribute.PROVIDER_COVERAGE_HAS_MORE] shouldBe DiagnosticAttributeValue.Flag(true)
        safe[DiagnosticAttribute.CHAPTER_NUMBER_MIN_MILLI] shouldBe DiagnosticAttributeValue.Number(1000)
        safe[DiagnosticAttribute.CHAPTER_NUMBER_MAX_MILLI] shouldBe DiagnosticAttributeValue.Number(101000)
        safe[DiagnosticAttribute.PROVIDER_DETAIL_OUTCOME] shouldBe
            DiagnosticAttributeValue.Code(DiagnosticProviderDetailOutcome.DETAIL_FAILED)
        safe[DiagnosticAttribute.HTTP_STATUS] shouldBe DiagnosticAttributeValue.Number(503)

        listOf(
            "https://mangafire.to/title/qnlvj-vagabond22?token=secret",
            "https://user:pass@mangafire.to/title/qnlvj-vagabond22",
            "https://evil.example/title/qnlvj-vagabond22",
            "http://mangafire.to/title/qnlvj-vagabond22",
        ).forEach { unsafe ->
            sanitizeCandidate(
                mapOf("provider_work_url" to DiagnosticAttributeValue.Text(unsafe)),
            ) shouldBe emptyMap()
        }
        sanitizeCandidate(
            mapOf("provider_work_title" to DiagnosticAttributeValue.Text("Bad\nAuthorization: Bearer secret")),
        ) shouldBe emptyMap()
        sanitizeCandidate(
            mapOf(
                "provider_detail_outcome" to DiagnosticAttributeValue.Text("Authorization: Bearer secret"),
                "http_status" to DiagnosticAttributeValue.Number(999),
            ),
        ) shouldBe emptyMap()
    }

    @Test
    fun `sanitizer keeps typed safe fields and drops unknown keys and unsafe values`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.WARN,
            subsystem = DiagnosticSubsystem.SOURCE,
            name = DiagnosticEventName.SOURCE_SEARCH_COMPLETED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            stage = DiagnosticStage.SEARCH,
            outcome = DiagnosticOutcome.FAILED,
            attributes = mapOf(
                "source_id" to DiagnosticAttributeValue.Number(-42),
                "preferred_source_count" to DiagnosticAttributeValue.Number(2),
                "target_source_count" to DiagnosticAttributeValue.Number(1),
                "http_status" to DiagnosticAttributeValue.Number(503),
                "language" to DiagnosticAttributeValue.Text("en-US"),
                "canonical_title_ref" to DiagnosticAttributeValue.Text("0123456789abcdef"),
                "mihon_manga_ref" to DiagnosticAttributeValue.Text("fedcba9876543210"),
                "search_text" to DiagnosticAttributeValue.Text("secret manga title"),
                "api_key" to DiagnosticAttributeValue.Text("sk_test_secret"),
                "extra" to DiagnosticAttributeValue.Text("https://user:pass@example.test/path?token=secret"),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.attributes shouldBe mapOf(
            DiagnosticAttribute.SOURCE_ID to DiagnosticAttributeValue.Number(-42),
            DiagnosticAttribute.PREFERRED_SOURCE_COUNT to DiagnosticAttributeValue.Number(2),
            DiagnosticAttribute.TARGET_SOURCE_COUNT to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.HTTP_STATUS to DiagnosticAttributeValue.Number(503),
            DiagnosticAttribute.LANGUAGE to DiagnosticAttributeValue.Text("en-US"),
            DiagnosticAttribute.CANONICAL_TITLE_REF to DiagnosticAttributeValue.Text("0123456789abcdef"),
            DiagnosticAttribute.MIHON_MANGA_REF to DiagnosticAttributeValue.Text("fedcba9876543210"),
        )
        sanitized?.attributes.toString().contains("secret") shouldBe false
        sanitized?.attributes.toString().contains("example.test") shouldBe false
        sanitized?.attributes.toString().contains("search_text") shouldBe false
    }

    @Test
    fun `P2P Host traces are bounded and reject unrecognized content identity fields`() {
        val safeLine = "provider_p2p event=METADATA_FAILED opRef=0123456789abcdef " +
            "source=MAGNET failure=METADATA_UNAVAILABLE elapsedMs=30001"

        fun sanitizeLine(line: String) = StructuredDiagnosticSanitizer.sanitize(
            StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.WARN,
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.PROVIDER_P2P_EVENT,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.SUMMARY,
                outcome = DiagnosticOutcome.FAILED,
                attributes = mapOf(
                    "provider_p2p_line" to DiagnosticAttributeValue.Text(line),
                ),
            ),
        )?.attributes

        sanitizeLine(safeLine) shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_P2P_LINE to DiagnosticAttributeValue.Text(safeLine),
        )
        listOf(
            safeLine + " filePath=secret.cbz",
            safeLine + " source=https://example.org/?token=secret",
            safeLine + " magnet=magnet:?xt=secret",
            safeLine + "\nAuthorization: Bearer secret",
        ).forEach { unsafe ->
            sanitizeLine(unsafe) shouldBe emptyMap()
        }
    }

    @Test
    fun `sanitizer keeps bounded Tsuzuki Rating composition fields`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.METADATA,
            name = DiagnosticEventName.TSUZUKI_RATING_COMPUTED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            stage = DiagnosticStage.SUMMARY,
            outcome = DiagnosticOutcome.SUCCEEDED,
            attributes = mapOf(
                "rating_source_count" to DiagnosticAttributeValue.Number(6),
                "rating_verified_source_count" to DiagnosticAttributeValue.Number(4),
                "rating_corroborated_source_count" to DiagnosticAttributeValue.Number(2),
                "tsuzuki_rating_present" to DiagnosticAttributeValue.Flag(true),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.attributes shouldBe mapOf(
            DiagnosticAttribute.RATING_SOURCE_COUNT to DiagnosticAttributeValue.Number(6),
            DiagnosticAttribute.RATING_VERIFIED_SOURCE_COUNT to DiagnosticAttributeValue.Number(4),
            DiagnosticAttribute.RATING_CORROBORATED_SOURCE_COUNT to DiagnosticAttributeValue.Number(2),
            DiagnosticAttribute.TSUZUKI_RATING_PRESENT to DiagnosticAttributeValue.Flag(true),
        )
    }

    @Test
    fun `sanitizer preserves Provider Reader handoff diagnostics`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.READER,
            name = DiagnosticEventName.READER_PAGES_READY,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            workflowId = "e1bf2346-14d8-4ef6-9f16-c03551f16013",
            workflow = DiagnosticWorkflow.READER_OPEN,
            stage = DiagnosticStage.READER,
            outcome = DiagnosticOutcome.READY,
            attributes = mapOf(
                "provider_id" to DiagnosticAttributeValue.Text("app.tsuzuki.mangafire"),
                "candidate_type" to DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                "candidate_count" to DiagnosticAttributeValue.Number(1),
                "binding_count" to DiagnosticAttributeValue.Number(1),
                "page_count" to DiagnosticAttributeValue.Number(24),
                "initialized" to DiagnosticAttributeValue.Flag(true),
                "error_category" to DiagnosticAttributeValue.Code(DiagnosticErrorCategory.NETWORK),
            ),
        )

        StructuredDiagnosticSanitizer.sanitize(event)?.attributes shouldBe mapOf(
            DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text("app.tsuzuki.mangafire"),
            DiagnosticAttribute.CANDIDATE_TYPE to DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
            DiagnosticAttribute.CANDIDATE_COUNT to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.BINDING_COUNT to DiagnosticAttributeValue.Number(1),
            DiagnosticAttribute.PAGE_COUNT to DiagnosticAttributeValue.Number(24),
            DiagnosticAttribute.INITIALIZED to DiagnosticAttributeValue.Flag(true),
            DiagnosticAttribute.ERROR_CATEGORY to DiagnosticAttributeValue.Code(DiagnosticErrorCategory.NETWORK),
        )
    }

    @Test
    fun `sanitizer rejects secret shaped correlation ids`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.SOURCE,
            name = DiagnosticEventName.SOURCE_RESOLVE_STARTED,
            sessionId = "https://private.example/?token=secret",
            operationId = null,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                "language" to DiagnosticAttributeValue.Text("Bearer top-secret-token"),
            ),
        )

        StructuredDiagnosticSanitizer.sanitize(event) shouldBe null
    }

    @Test
    fun `sanitizer drops secret url token and cookie values under a recognized attribute key`() {
        listOf(
            "https://user:pass@private.example/path?token=secret",
            "Bearer top-secret-token",
            "session=private-cookie",
        ).forEach { unsafeLanguage ->
            val event = StructuredDiagnosticEvent(
                timestampMillis = 1_790_755_200_000,
                severity = DiagnosticSeverity.INFO,
                subsystem = DiagnosticSubsystem.SOURCE,
                name = DiagnosticEventName.SOURCE_RESOLVE_STARTED,
                sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
                operationId = null,
                stage = DiagnosticStage.RESOLVE,
                outcome = DiagnosticOutcome.STARTED,
                attributes = mapOf(
                    "language" to DiagnosticAttributeValue.Text(unsafeLanguage),
                ),
            )

            StructuredDiagnosticSanitizer.sanitize(event)?.attributes shouldBe emptyMap()
        }
    }

    @Test
    fun `sanitizer preserves safe workflow correlation and rejects unsafe provider ids`() {
        val event = StructuredDiagnosticEvent(
            timestampMillis = 1_790_755_200_000,
            severity = DiagnosticSeverity.INFO,
            subsystem = DiagnosticSubsystem.ARTWORK,
            name = DiagnosticEventName.ARTWORK_RESOLVE_STARTED,
            sessionId = "d2719c3b-4518-4d6b-9b09-2834381a322c",
            operationId = "865624e0-50f1-41c9-81eb-9a68fc9e50a4",
            workflowId = "e1bf2346-14d8-4ef6-9f16-c03551f16013",
            parentOperationId = "6a024bea-3d20-4035-9a4f-969237ee67da",
            workflow = DiagnosticWorkflow.ARTWORK_RESOLUTION,
            stage = DiagnosticStage.RESOLVE,
            outcome = DiagnosticOutcome.STARTED,
            attributes = mapOf(
                "provider_id" to DiagnosticAttributeValue.Text("kitsu"),
                "request_data_present" to DiagnosticAttributeValue.Flag(true),
                "invariant_code" to DiagnosticAttributeValue.Code(
                    DiagnosticInvariantCode.ARTWORK_LOST_AFTER_RESOLUTION,
                ),
            ),
        )

        val sanitized = StructuredDiagnosticSanitizer.sanitize(event)

        sanitized?.workflowId shouldBe "e1bf2346-14d8-4ef6-9f16-c03551f16013"
        sanitized?.parentOperationId shouldBe "6a024bea-3d20-4035-9a4f-969237ee67da"
        sanitized?.workflow shouldBe DiagnosticWorkflow.ARTWORK_RESOLUTION
        sanitized?.attributes?.get(DiagnosticAttribute.PROVIDER_ID) shouldBe DiagnosticAttributeValue.Text("kitsu")
    }
}
