package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeLogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterFileMatch
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterMapper
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

fun interface ProviderTorrentMetadataInspector {
    suspend fun inspect(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate?
}

class ProviderTorrentMetadataSearchGateway internal constructor(
    private val delegate: TorrentSearchGateway,
    private val registry: ProviderRegistry,
    private val inspector: ProviderTorrentMetadataInspector,
    private val diagnosticRecorder: StructuredDiagnosticRecorder,
    private val logSink: ProviderRuntimeLogSink = ProviderRuntimeLogSink { _, _ -> },
    maxConcurrentInspections: Int = DEFAULT_MAX_CONCURRENT_INSPECTIONS,
) : TorrentSearchGateway {

    private val inspectionPermits = Semaphore(maxConcurrentInspections)
    private val parseChapterLabel = ParseCanonicalChapterLabel()
    private val parseChapterVolume = ParseCanonicalChapterVolume()
    private val chapterMapper = TorrentChapterMapper()

    init {
        require(maxConcurrentInspections in 1..MAX_CONCURRENT_INSPECTIONS) {
            "Provider torrent metadata concurrency is outside supported bounds"
        }
    }

    override suspend fun search(
        providerId: ProviderId,
        request: TorrentSearchRequest,
    ): ProviderCallResult<ProviderPage<TorrentCandidate>> {
        return when (val result = delegate.search(providerId, request)) {
            is ProviderCallResult.Failure -> result
            is ProviderCallResult.Success -> {
                val descriptor = registry.registration(providerId)?.descriptor
                    ?: return result
                val sourceItems = result.value.items
                val attempted = sourceItems.count { it.files == null }
                val startedAtNanos = System.nanoTime()
                val items = coroutineScope {
                    sourceItems.map { candidate ->
                        async {
                            if (candidate.files != null) {
                                candidate
                            } else {
                                inspectFailClosed(descriptor, candidate)
                            }
                        }
                    }.awaitAll()
                }
                val hydrated = sourceItems.zip(items).count { (source, resolved) ->
                    source.files == null && resolved.files != null
                }
                val elapsedMillis = ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND)
                    .coerceAtLeast(0L)
                emitHydrationSummary(
                    providerId = providerId,
                    total = sourceItems.size,
                    attempted = attempted,
                    hydrated = hydrated,
                    elapsedMillis = elapsedMillis,
                )
                emitMatchSummary(
                    providerId = providerId,
                    request = request,
                    items = items,
                )
                ProviderCallResult.Success(result.value.copy(items = items))
            }
        }
    }

    private suspend fun inspectFailClosed(
        descriptor: ProviderDescriptor,
        candidate: TorrentCandidate,
    ): TorrentCandidate = try {
        inspectionPermits.withPermit {
            inspector.inspect(descriptor, candidate)
        } ?: candidate
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        candidate
    }

    private fun emitHydrationSummary(
        providerId: ProviderId,
        total: Int,
        attempted: Int,
        hydrated: Int,
        elapsedMillis: Long,
    ) {
        val failed = attempted - hydrated
        runCatching {
            logSink.info(
                providerId.value,
                "host_torrent_metadata total=$total attempted=$attempted hydrated=$hydrated " +
                    "failed=$failed elapsedMs=$elapsedMillis",
            )
        }
        recordSummary(
            providerId = providerId,
            name = DiagnosticEventName.TORRENT_METADATA_SUMMARY,
            outcome = if (failed == 0) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.PARTIAL,
            durationMillis = elapsedMillis,
            attributes = mapOf(
                "candidate_count" to total.numberValue(),
                "torrent_hydration_attempted_count" to attempted.numberValue(),
                "torrent_hydrated_count" to hydrated.numberValue(),
                "torrent_hydration_failed_count" to failed.numberValue(),
            ),
        )
    }

    private fun emitMatchSummary(
        providerId: ProviderId,
        request: TorrentSearchRequest,
        items: List<TorrentCandidate>,
    ) {
        val chapterNumber = request.chapterNumber ?: return
        runCatching {
            val identity = parseChapterLabel(chapterNumber).identity
            if (!identity.isSpecific || !identity.isNumbered) return@runCatching

            val volumeRequest = TorrentChapterRequest(
                identity = identity,
                volume = request.volume,
            )
            val identityRequest = if (request.volume != null) {
                TorrentChapterRequest(identity = identity, volume = null)
            } else {
                volumeRequest
            }

            var readable = 0
            var parsed = 0
            var embedded = 0
            var identityMatches = 0
            var volumeMatches = 0
            var exactMatches = 0
            var ambiguousMatches = 0

            items.forEach { candidate ->
                val readableFiles = candidate.files.orEmpty().filter(::isSupportedReadableFile)
                if (readableFiles.isNotEmpty()) {
                    readable += 1
                }
                if (
                    readableFiles.any { file ->
                        parseChapterLabel(chapterLabel(file.path)).identity.let { parsedIdentity ->
                            parsedIdentity.isSpecific && parsedIdentity.isNumbered
                        }
                    }
                ) {
                    parsed += 1
                }

                var currentIdentityMatched = false
                when (chapterMapper.map(volumeRequest, candidate)) {
                    TorrentChapterFileMatch.None -> {
                        if (
                            request.volume != null &&
                            chapterMapper.map(identityRequest, candidate) !is TorrentChapterFileMatch.None
                        ) {
                            identityMatches += 1
                            currentIdentityMatched = true
                        }
                    }
                    is TorrentChapterFileMatch.Exact -> {
                        identityMatches += 1
                        volumeMatches += 1
                        exactMatches += 1
                        currentIdentityMatched = true
                    }
                    is TorrentChapterFileMatch.Ambiguous -> {
                        identityMatches += 1
                        volumeMatches += 1
                        ambiguousMatches += 1
                        currentIdentityMatched = true
                    }
                }

                if (
                    !currentIdentityMatched &&
                    readableFiles.any { file ->
                        val explicitMarker = EMBEDDED_CHAPTER_MARKER.find(chapterLabel(file.path))?.value
                        explicitMarker != null && parseChapterLabel(explicitMarker).identity == identity
                    }
                ) {
                    embedded += 1
                }
            }

            logSink.info(
                providerId.value,
                "host_torrent_match total=${items.size} readable=$readable parsed=$parsed embedded=$embedded " +
                    "identity=$identityMatches volume=$volumeMatches exact=$exactMatches ambiguous=$ambiguousMatches",
            )
            recordSummary(
                providerId = providerId,
                name = DiagnosticEventName.TORRENT_MATCH_SUMMARY,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    "candidate_count" to items.size.numberValue(),
                    "torrent_readable_count" to readable.numberValue(),
                    "torrent_parsed_count" to parsed.numberValue(),
                    "torrent_embedded_count" to embedded.numberValue(),
                    "torrent_identity_match_count" to identityMatches.numberValue(),
                    "torrent_volume_match_count" to volumeMatches.numberValue(),
                    "torrent_exact_match_count" to exactMatches.numberValue(),
                    "torrent_ambiguous_match_count" to ambiguousMatches.numberValue(),
                ),
            )

            var releaseExplicit = 0
            var releaseExact = 0
            var singleReadable = 0
            var releaseExactSingle = 0
            var releaseExactReadable = 0
            var releaseExactMultiReadable = 0
            var fileToken = 0
            var fileTokenSingle = 0
            var fileTitleToken = 0
            var fileTitleTokenSingle = 0
            val chapterToken = Regex(
                "(?i)(?<![\\p{L}\\p{N}])${Regex.escape(chapterNumber.trim())}(?![\\p{L}\\p{N}])",
            )

            items.forEach { candidate ->
                val readableFiles = candidate.files.orEmpty().filter(::isSupportedReadableFile)
                val releaseChapterMatches = EMBEDDED_CHAPTER_MARKER
                    .findAll(candidate.displayName)
                    .any { marker -> parseChapterLabel(marker.value).identity == identity }
                val releaseVolume = EMBEDDED_VOLUME_CHAPTER_MARKER
                    .findAll(candidate.displayName)
                    .firstOrNull { marker -> parseChapterLabel(marker.value).identity == identity }
                    ?.value
                    ?.let { marker -> parseChapterVolume(marker) }
                val releaseRequestMatches = releaseChapterMatches &&
                    (request.volume == null || releaseVolume == request.volume)
                val hasFileToken = readableFiles.any { file ->
                    chapterToken.containsMatchIn(chapterLabel(file.path))
                }
                val hasFileTitleToken = readableFiles.any { file ->
                    hasTitleAwareChapterToken(
                        label = chapterLabel(file.path),
                        titles = request.titles,
                        chapterNumber = chapterNumber,
                    )
                }

                if (releaseChapterMatches) releaseExplicit += 1
                if (releaseRequestMatches) releaseExact += 1
                if (readableFiles.size == 1) singleReadable += 1
                if (releaseRequestMatches && readableFiles.size == 1) releaseExactSingle += 1
                if (releaseRequestMatches && readableFiles.isNotEmpty()) releaseExactReadable += 1
                if (releaseRequestMatches && readableFiles.size > 1) releaseExactMultiReadable += 1
                if (hasFileToken) fileToken += 1
                if (hasFileToken && readableFiles.size == 1) fileTokenSingle += 1
                if (hasFileTitleToken) fileTitleToken += 1
                if (hasFileTitleToken && readableFiles.size == 1) fileTitleTokenSingle += 1
            }

            logSink.info(
                providerId.value,
                "host_torrent_release total=${items.size} releaseExplicit=$releaseExplicit " +
                    "releaseExact=$releaseExact singleReadable=$singleReadable " +
                    "releaseExactSingle=$releaseExactSingle releaseExactReadable=$releaseExactReadable " +
                    "releaseExactMultiReadable=$releaseExactMultiReadable fileToken=$fileToken " +
                    "fileTokenSingle=$fileTokenSingle fileTitleToken=$fileTitleToken " +
                    "fileTitleTokenSingle=$fileTitleTokenSingle " +
                    "volumeRequested=${if (request.volume == null) 0 else 1}",
            )
            recordSummary(
                providerId = providerId,
                name = DiagnosticEventName.TORRENT_RELEASE_EVIDENCE_SUMMARY,
                outcome = DiagnosticOutcome.SUCCEEDED,
                attributes = mapOf(
                    "candidate_count" to items.size.numberValue(),
                    "torrent_release_explicit_count" to releaseExplicit.numberValue(),
                    "torrent_release_exact_count" to releaseExact.numberValue(),
                    "torrent_single_readable_count" to singleReadable.numberValue(),
                    "torrent_release_exact_single_count" to releaseExactSingle.numberValue(),
                    "torrent_release_exact_readable_count" to releaseExactReadable.numberValue(),
                    "torrent_release_exact_multi_readable_count" to releaseExactMultiReadable.numberValue(),
                    "torrent_file_token_count" to fileToken.numberValue(),
                    "torrent_file_token_single_count" to fileTokenSingle.numberValue(),
                    "torrent_file_title_token_count" to fileTitleToken.numberValue(),
                    "torrent_file_title_token_single_count" to fileTitleTokenSingle.numberValue(),
                    "torrent_volume_requested" to DiagnosticAttributeValue.Flag(request.volume != null),
                ),
            )
        }
    }

    private fun hasTitleAwareChapterToken(
        label: String,
        titles: List<String>,
        chapterNumber: String,
    ): Boolean {
        val chapterToken = Regex(
            "(?i)(?<![\\p{L}\\p{N}])${Regex.escape(chapterNumber.trim())}(?![\\p{L}\\p{N}])",
        )
        return titles.asSequence()
            .map { title -> TITLE_WORD.findAll(title).map { word -> Regex.escape(word.value) }.toList() }
            .filter { words -> words.isNotEmpty() }
            .any { words ->
                val titlePattern = Regex(
                    "(?i)(?<![\\p{L}\\p{N}])${words.joinToString("[^\\p{L}\\p{N}]+")}(?![\\p{L}\\p{N}])",
                )
                titlePattern.findAll(label).any { titleMatch ->
                    val withoutTitle = label.removeRange(titleMatch.range)
                    chapterToken.containsMatchIn(withoutTitle)
                }
            }
    }

    private fun recordSummary(
        providerId: ProviderId,
        name: DiagnosticEventName,
        outcome: DiagnosticOutcome,
        attributes: Map<String, DiagnosticAttributeValue>,
        durationMillis: Long? = null,
    ) {
        runCatching {
            diagnosticRecorder.record(
                StructuredDiagnosticEvent(
                    timestampMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.INFO,
                    subsystem = DiagnosticSubsystem.CONTENT,
                    name = name,
                    sessionId = diagnosticRecorder.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.SUMMARY,
                    outcome = outcome,
                    durationMillis = durationMillis,
                    attributes = attributes +
                        ("provider_id" to DiagnosticAttributeValue.Text(providerId.value)),
                ),
            )
        }
    }

    private fun Int.numberValue(): DiagnosticAttributeValue.Number = DiagnosticAttributeValue.Number(toLong())

    private fun isSupportedReadableFile(file: TorrentCandidateFile): Boolean {
        val lower = file.path.lowercase()
        return lower.endsWith(".cbz") || lower.endsWith(".zip")
    }

    private fun chapterLabel(path: String): String {
        val basename = path.substringAfterLast('/')
        val stem = basename.substringBeforeLast('.', missingDelimiterValue = basename)
        return stem
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_INSPECTIONS = 4
        const val MAX_CONCURRENT_INSPECTIONS = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val TITLE_WORD = Regex("[\\p{L}\\p{N}]+")
        val EMBEDDED_VOLUME_CHAPTER_MARKER = Regex(
            "(?i)(?<![\\p{L}\\p{N}])vol(?:ume)?\\.?\\s*\\d+\\s*(?:[-:|/]\\s*|\\s+)" +
                "(?:ch(?:apter)?|cap(?:i|í)tulo)\\s*\\.?\\s*\\d+(?:\\.\\d+|[a-z])?",
        )
        val EMBEDDED_CHAPTER_MARKER = Regex(
            "(?i)(?<![\\p{L}\\p{N}])(?:ch(?:apter)?|cap(?:i|í)tulo)\\s*\\.?\\s*\\d+(?:\\.\\d+|[a-z])?",
        )
    }
}
