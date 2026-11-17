package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import java.security.MessageDigest

/**
 * Opt-in TEST-ONLY reader of actual normalized signed MangaAll source responses.
 * Uses the real Host ScriptProviderReadingGateway V1 decoder with genuine
 * captured requests/results. No invented source candidate or chapter is used.
 *
 * Not a canonical title resolver, source HTTP runtime, SQLite test or Reader
 * image/pixel test. Those require further Host #207 integration.
 */
internal object MangaAllRealSourceReplay {
    private const val PROVIDER_ID = "app.tsuzuki.mangaall"
    private const val REPOSITORY_ID = "app.tsuzuki.providers"
    private val workId = Regex("(?:mangadex|mangadot|mangafire):[A-Za-z0-9._-]{1,128}")
    private val chapterId = Regex("(?:mangadex|mangadot|mangafire):[A-Za-z0-9._:-]{1,200}")
    private val digest = Regex("[a-f0-9]{64}")
    private val caseId = Regex("[A-Za-z0-9_-]{1,100}")
    private val json = Json { ignoreUnknownKeys = false }

    internal data class Case(
        val id: String,
        val replayCompleteness: String,
        val records: List<JsonObject>,
    )

    internal data class CursorCoverage(
        val pendingLookupCursors: Int,
        val pendingChapterCursors: Int,
        val orphanContinuations: Int,
        val abandonedContinuations: Int,
    )

    /**
     * Tracks real, Host-shaped page cursors by exact source query/binding.
     * A bounded live probe can stop even when HTTP 200 and every observed
     * page decode succeeded. This never certifies a complete work inventory.
     */
    internal fun cursorCoverage(records: List<JsonObject>): CursorCoverage {
        val waiting = mutableMapOf<String, String>()
        var orphan = 0
        var abandoned = 0
        for (record in records) {
            val operation = record.string("operation")
            if (operation != "lookup" && operation != "chapters") continue
            val request = record["request"]?.jsonObject ?: error("Source cursor request missing")
            val key = if (operation == "lookup") {
                "lookup:" + request["titles"].toString() + ":" +
                    request.string("requestedLanguage").orEmpty()
            } else {
                val binding = request["binding"]?.jsonObject ?: error("Source binding missing")
                "chapters:" + binding.string("providerId") + ":" +
                    binding.string("facetId") + ":" + binding.string("externalWorkId")
            }
            val submitted = request.string("cursor")
            val expected = waiting.remove(key)
            if (expected != null && expected != submitted) abandoned++
            if (submitted != null && expected != submitted) orphan++
            if (record.string("outcome") == "RESULT") {
                val next = record["response"]?.jsonObject?.string("nextCursor")
                if (next != null) waiting[key] = next
            }
        }
        return CursorCoverage(
            pendingLookupCursors = waiting.keys.count { it.startsWith("lookup:") },
            pendingChapterCursors = waiting.keys.count { it.startsWith("chapters:") },
            orphanContinuations = orphan,
            abandonedContinuations = abandoned,
        )
    }

    internal data class Report(
        val id: String,
        val sourceState: String,
        val lookupDecoded: Int,
        val chapterPagesDecoded: Int,
        val typedSourceFailures: Int,
        val upstreamErrors: Int,
        val pageMetadataOnly: Int,
        val pendingLookupCursors: Int = 0,
        val pendingChapterCursors: Int = 0,
        val orphanContinuations: Int = 0,
        val abandonedContinuations: Int = 0,
        val replayGaps: Int = 0,
        val catalogStage: String = "NOT_EXECUTED_HOST",
        val canonicalStage: String = "NOT_EXECUTED_HOST",
        val readerStage: String = "NOT_EXECUTED_HOST",
    ) {
        fun toClosedLine(): String =
            "case=$id|source=$sourceState|lookup=$lookupDecoded|" +
                "chapters=$chapterPagesDecoded|typedFailure=$typedSourceFailures|" +
                "upstreamError=$upstreamErrors|pagesMetadata=$pageMetadataOnly|" +
                "lookupCursorsPending=$pendingLookupCursors|" +
                "chapterCursorsPending=$pendingChapterCursors|" +
                "orphanCursors=$orphanContinuations|abandonedCursors=$abandonedContinuations|" +
                "replayGaps=$replayGaps|" +
                "catalog=$catalogStage|canonical=$canonicalStage|reader=$readerStage"
    }

    internal fun validate(captureText: String): List<Case> {
        require(captureText.toByteArray().size <= 35_000_000) { "Capture exceeds size limit" }
        val batch = json.parseToJsonElement(captureText).jsonObject
        verifySha(batch)
        require(batch.number("schemaVersion") == 1L)
        require(batch.string("captureKind") == "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH")
        require(batch.string("origin") == "LIVE_SIGNED_PROVIDER_JS")
        require(batch.string("providerId") == PROVIDER_ID)
        require(batch.string("repositoryId") == REPOSITORY_ID)
        require(batch.number("providerVersionCode")?.let { it >= 8 } == true)
        require(digest.matches(batch.string("packageSha256").orEmpty()))
        require(batch.number("signedIndexSequence")?.let { it >= 34 } == true)
        require(batch.string("catalogStage") == "NOT_EXECUTED_HOST")
        require(batch.string("canonicalStage") == "NOT_EXECUTED_HOST")
        require(batch.string("readerPixelStage") == "NOT_EXECUTED_HOST")
        require(batch.string("captureOutcome") in setOf("SOURCE_ATTEMPTS_RECORDED", "NO_WORK_ASSIGNED"))
        val items = batch["cases"]?.jsonArray ?: error("Capture cases missing")
        require(items.size <= 30)
        if (items.isEmpty()) require(batch.string("captureOutcome") == "NO_WORK_ASSIGNED")

        return items.map { row ->
            val value = row.jsonObject
            verifySha(value)
            require(value.string("captureKind") == "REAL_PROVIDER_NORMALIZED_ENVELOPES")
            require(caseId.matches(value.string("caseId").orEmpty()))
            require(value.string("providerId") == PROVIDER_ID)
            require(value.number("providerCode") == batch.number("providerVersionCode"))
            require(value.string("packageSha256") == batch.string("packageSha256"))
            require(value.string("canonicalStage") == "NOT_EXECUTED_HOST")
            require(value.string("readerStage") == "PAGE_METADATA_ONLY_NOT_REPLAYABLE")
            val state = value.string("replayCompleteness").orEmpty()
            require(
                state in setOf(
                    "LOOKUP_CHAPTERS_ONLY",
                    "INCOMPLETE_REJECTED_FIELDS",
                    "INCOMPLETE_BUDGET_EXCEEDED",
                ),
            )
            val records = value["records"]?.jsonArray ?: error("Records missing")
            require(records.size <= 128)
            val list = records.mapIndexed { index, raw ->
                val record = raw.jsonObject
                require(record.number("sequence") == (index + 1).toLong())
                require(record.string("operation") in setOf("lookup", "chapters", "pages"))
                require(record.string("outcome") in setOf("RESULT", "SOURCE_FAILURE", "SOURCE_ERROR"))
                require(record["request"] is JsonObject)
                if (record.string("outcome") != "SOURCE_ERROR") {
                    require(record["response"] is JsonObject)
                }
                if (record.string("operation") == "pages") {
                    require(record.string("replayability") == "READER_URLS_REDACTED")
                    require(record["response"]?.jsonObject?.get("pages") == null)
                }
                record
            }
            Case(
                id = value.string("caseId")!!,
                replayCompleteness = state,
                records = list,
            )
        }
    }

    /**
     * Exact signed Host gateway shared by decoder checks and the canonical
     * collector replay. The invoker rejects any request not present on the
     * captured tape; it does not implement its own candidate selection.
     */
    internal class PreparedPackage(
        val provider: ProviderId,
        val registry: DefaultProviderRegistry,
        private val packageSource: ActiveProviderScriptPackageSource,
        private val providerVersionCode: Long,
    ) {
        fun gateway(tape: MangaAllCapturedRequestTape) = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = packageSource,
            invokePackage = ProviderPackageCapabilityInvoker { request, _, input, _ ->
                require(request.providerId == PROVIDER_ID)
                require(request.artifactVersionCode == providerVersionCode)
                val operation = request.capabilityId.removePrefix("reading.")
                require(operation in setOf("lookup", "chapters"))
                val captured = tape.replay(operation, json.parseToJsonElement(input).jsonObject)
                ProviderRuntimeInvocationResponse.success(captured.toString())
            },
            invocationIdFactory = { "real-source-canonical-replay" },
        )
    }

    internal fun prepare(
        signedPackageBytes: ByteArray,
        expectedSha256: String,
        expectedVersionCode: Long,
    ): PreparedPackage {
        require(digest.matches(expectedSha256) && sha256(signedPackageBytes) == expectedSha256) {
            "Host must use the exact signed MangaAll package captured by the Provider"
        }
        val parsed = ProviderPackageParser().parse(signedPackageBytes)
        require(parsed.manifest.id == PROVIDER_ID)
        require(parsed.manifest.version.code == expectedVersionCode)
        require(parsed.manifest.capabilities.any { it.id == "reading.lookup" && it.version == 1 })
        require(parsed.manifest.capabilities.any { it.id == "reading.chapters" && it.version == 1 })
        val provider = ProviderId(PROVIDER_ID)
        val descriptor = ProviderDescriptor(
            id = provider,
            name = parsed.manifest.name,
            version = ProviderVersion(parsed.manifest.version.name, parsed.manifest.version.code),
            origin = ProviderOrigin.Repository(REPOSITORY_ID),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingLookupV1,
                ProviderCapabilities.ReadingChaptersV1,
                ProviderCapabilities.ReadingPagesV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = setOf("en", "pt-br"),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = descriptor,
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        configurationFingerprint = "real-source-captured-only",
                    ),
                )
            },
        )
        val source = ActiveProviderScriptPackageSource {
            if (it == provider) {
                ActiveProviderScriptPackage(
                    repositoryId = REPOSITORY_ID,
                    versionCode = parsed.manifest.version.code,
                    bytes = signedPackageBytes,
                    parsed = parsed,
                )
            } else {
                null
            }
        }
        return PreparedPackage(provider, registry, source, expectedVersionCode)
    }

    internal suspend fun decode(
        captureText: String,
        signedPackageBytes: ByteArray,
    ): List<Report> {
        val cases = validate(captureText)
        val batch = json.parseToJsonElement(captureText).jsonObject
        require(sha256(signedPackageBytes) == batch.string("packageSha256")) {
            "Host must use the actual signed provider archive, never a reconstructed mock"
        }
        val prepared = prepare(
            signedPackageBytes = signedPackageBytes,
            expectedSha256 = batch.string("packageSha256")!!,
            expectedVersionCode = batch.number("providerVersionCode")!!,
        )

        return cases.map { case ->
            if (case.replayCompleteness != "LOOKUP_CHAPTERS_ONLY") {
                return@map Report(case.id, case.replayCompleteness, 0, 0, 0, 0, 0)
            }
            // The same validated case-scoped capture must serve every Host V1 request.
            // Requests cannot borrow replies from another title, facet or cursor.
            val tape = MangaAllCapturedRequestTape(case.records)
            val gateway = prepared.gateway(tape)
            var lookupDecoded = 0
            var chapterDecoded = 0
            var typedFailure = 0
            var sourceError = 0
            var pagesSkipped = 0
            for (record in case.records) {
                val operation = record.string("operation")!!
                if (operation == "pages") {
                    pagesSkipped++
                    continue
                }
                if (record.string("outcome") == "SOURCE_ERROR") {
                    if (operation == "lookup" || operation == "chapters") {
                        val recordedRequest = record["request"]!!.jsonObject
                        try {
                            tape.replay(operation, recordedRequest)
                            error("Captured upstream error was replayed as a successful response")
                        } catch (_: MangaAllCapturedSourceErrorException) {
                            sourceError++
                        }
                    } else {
                        sourceError++
                    }
                    continue
                }
                val expectedRequest = record["request"]!!.jsonObject
                val response = record["response"]!!.jsonObject
                if (operation == "lookup") {
                    val requested = ProviderReadingLookupRequest(
                        titles = expectedRequest["titles"]!!.jsonArray.map { it.jsonPrimitive.content },
                        cursor = expectedRequest.string("cursor")?.let(::ProviderCursor),
                        requestedLanguage = expectedRequest.string("requestedLanguage"),
                        coverageEvidenceVersion = expectedRequest.number("coverageEvidenceVersion")?.toInt(),
                    )
                    val decoded = gateway.lookup(prepared.provider, requested)
                    if (tape.replayGapCount > 0) break
                    if (record.string("outcome") == "SOURCE_FAILURE") {
                        require(decoded is ProviderCallResult.Failure) {
                            "Typed lookup failure must NEVER become empty successful Host lookup"
                        }
                        require(decoded.error.sourceFailureCode?.name == response.string("lookupFailureCode"))
                        typedFailure++
                    } else {
                        require(decoded is ProviderCallResult.Success) {
                            "Host failed real lookup decode"
                        }
                        val observed = response["items"]!!.jsonArray
                        require(decoded.value.items.size == observed.size)
                        decoded.value.items.zip(observed).forEach { (candidate, raw) ->
                            val source = raw.jsonObject
                            require(workId.matches(candidate.externalWorkId))
                            require(candidate.externalWorkId == source.string("externalWorkId"))
                            require(candidate.title == source.string("title"))
                            require(
                                candidate.authors == (source["authors"] as? JsonArray)
                                    ?.map { it.jsonPrimitive.content }.orEmpty(),
                            )
                        }
                        require(decoded.value.nextCursor?.value == response.string("nextCursor"))
                        lookupDecoded++
                    }
                } else {
                    val bindingObject = expectedRequest["binding"]!!.jsonObject
                    require(workId.matches(bindingObject.string("externalWorkId").orEmpty()))
                    val binding = ProviderBindingRef(
                        providerId = prepared.provider,
                        facetId = bindingObject.string("facetId"),
                        externalWorkId = bindingObject.string("externalWorkId")!!,
                    )
                    val decoded = gateway.chapters(
                        prepared.provider,
                        ProviderReadingChaptersRequest(
                            binding = binding,
                            cursor = expectedRequest.string("cursor")?.let(::ProviderCursor),
                        ),
                    )
                    if (tape.replayGapCount > 0) break
                    require(decoded is ProviderCallResult.Success) {
                        "Host failed real chapter decode"
                    }
                    val observed = response["items"]!!.jsonArray
                    require(decoded.value.items.size == observed.size)
                    decoded.value.items.zip(observed).forEach { (chapter, raw) ->
                        val source = raw.jsonObject
                        require(chapterId.matches(chapter.providerChapterId))
                        require(chapter.providerChapterId == source.string("providerChapterId"))
                        require(chapter.rawLabel == source.string("rawLabel"))
                        require(chapter.rawNumber == source.double("rawNumber"))
                        require(chapter.volume?.toLong() == source.number("volume"))
                        require(chapter.scanlationGroup == source.string("scanlationGroup"))
                        require(chapter.releaseDateMillis == source.number("releaseDateMillis"))
                    }
                    require(decoded.value.nextCursor?.value == response.string("nextCursor"))
                    chapterDecoded++
                }
            }
            val coverage = cursorCoverage(case.records)
            val sourceState = when {
                tape.replayGapCount > 0 -> "REPLAY_GAP"
                sourceError > 0 || typedFailure > 0 -> "INCONCLUSIVE_UPSTREAM"
                coverage.pendingLookupCursors > 0 || coverage.pendingChapterCursors > 0 ||
                    coverage.orphanContinuations > 0 || coverage.abandonedContinuations > 0 ->
                    "INCOMPLETE_CAPTURE_CURSOR"
                lookupDecoded + chapterDecoded == 0 -> "NO_REPLAYABLE_SOURCE_RESPONSE"
                else -> "REAL_RESPONSES_DECODED_NOT_CANONICAL"
            }
            Report(
                id = case.id,
                sourceState = sourceState,
                lookupDecoded = lookupDecoded,
                chapterPagesDecoded = chapterDecoded,
                typedSourceFailures = typedFailure,
                upstreamErrors = sourceError,
                pageMetadataOnly = pagesSkipped,
                pendingLookupCursors = coverage.pendingLookupCursors,
                pendingChapterCursors = coverage.pendingChapterCursors,
                orphanContinuations = coverage.orphanContinuations,
                abandonedContinuations = coverage.abandonedContinuations,
                replayGaps = tape.replayGapCount,
            )
        }
    }

    private fun verifySha(obj: JsonObject) {
        val actual = obj.string("sha256")
        require(actual != null && digest.matches(actual))
        val canonical = JsonObject(obj.filterKeys { it != "sha256" }).toString()
        require(sha256(canonical.encodeToByteArray()) == actual) {
            "Real source capture integrity mismatch"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun JsonObject.string(key: String): String? {
        val primitive = get(key)
        return if (primitive == null || primitive is JsonNull) {
            null
        } else {
            (primitive as? JsonPrimitive)?.content
        }
    }

    private fun JsonObject.number(key: String): Long? = string(key)?.toLongOrNull()

    private fun JsonObject.double(key: String): Double? = string(key)?.toDoubleOrNull()
}
