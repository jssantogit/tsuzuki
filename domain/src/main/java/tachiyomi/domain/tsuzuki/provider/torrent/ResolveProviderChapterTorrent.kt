package tachiyomi.domain.tsuzuki.provider.torrent

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderError
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

data class ProviderChapterTorrentOption(
    val canonicalChapterId: String,
    val providerId: ProviderId,
    val candidate: TorrentCandidate,
    val selectedFile: TorrentCandidateFile,
) {
    init {
        require(canonicalChapterId.isNotBlank()) { "Canonical chapter ID must not be blank" }
        require(candidate.files.orEmpty().any { it == selectedFile }) {
            "Selected torrent file must belong to the candidate"
        }
    }
}

fun ProviderChapterTorrentOption.toContentDelivery(): ContentDelivery.Torrent? {
    val infoHash = candidate.infoHash ?: return null
    return ContentDelivery.Torrent(
        infoHash = infoHash,
        magnetUri = candidate.magnetUri,
        fileIndex = selectedFile.index,
        filePath = selectedFile.path,
    )
}

private object EmptyTitleNameObservationRepository : TitleNameObservationRepository {
    override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> = emptyList()

    override suspend fun upsert(observation: TitleNameObservation) = Unit
}

/**
 * Discovers Provider torrent releases that can be mapped to one canonical chapter without guessing.
 *
 * Discovery is deliberately provider-neutral. A candidate is exposed only when the existing
 * canonical torrent mapper proves one exact readable archive file for the requested chapter.
 * Ambiguous packs, unnumbered chapters and candidates without a stable info hash remain unavailable.
 */
@Inject
class ResolveProviderChapterTorrent(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val providerRegistry: ProviderRegistry,
    private val gateway: TorrentSearchGateway,
    private val titleNameObservationRepository: TitleNameObservationRepository = EmptyTitleNameObservationRepository,
    private val metadataGateway: TorrentCandidateMetadataGateway = TorrentCandidateMetadataGateway.Passthrough,
) {

    suspend fun options(
        canonicalChapterId: String,
        trace: DiagnosticTrace? = null,
    ): List<ProviderChapterTorrentOption> {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return emptyList()
        if (!chapter.identity.isSpecific || !chapter.identity.isNumbered) return emptyList()
        val title = canonicalTitleRepository.getById(chapter.canonicalTitleId) ?: return emptyList()
        val observations = try {
            titleNameObservationRepository.getByTitle(title.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val searchTitles = buildSearchTitles(
            displayTitle = title.displayTitle,
            observations = observations,
        )

        providerRegistry.awaitReady()
        val targets = providerRegistry.providers()
            .asSequence()
            .filter { it.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
            .filter { it.descriptor.runtime == ProviderRuntimeKind.SCRIPT }
            .filter { ProviderCapabilities.TorrentSearchV1 in it.enabledCapabilities }
            .map { it.descriptor }
            .sortedBy { it.id.value }
            .toList()
        if (targets.isEmpty()) return emptyList()

        val request = TorrentChapterRequest(
            identity = chapter.identity,
            volume = chapter.volume,
        )
        val mapper = TorrentChapterMapper()

        return targets
            .flatMap { provider ->
                val providerId = provider.id
                val providerTrace = trace?.child()
                val providerAttributes = mapOf(
                    DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(providerId.value),
                    DiagnosticAttribute.PROVIDER_VERSION_NAME to
                        DiagnosticAttributeValue.Text(provider.version.name),
                    DiagnosticAttribute.PROVIDER_VERSION_CODE to
                        DiagnosticAttributeValue.Number(provider.version.code),
                )

                when (
                    val discovery = discoverMatches(
                        canonicalChapterId = canonicalChapterId,
                        providerId = providerId,
                        titles = searchTitles,
                        chapterNumber = chapter.displayNumber,
                        volume = chapter.volume,
                        request = request,
                        mapper = mapper,
                    )
                ) {
                    is TorrentDiscoveryResult.Failure -> {
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.SEARCH,
                            outcome = DiagnosticOutcome.TYPED_FAILURE,
                            attributes = providerAttributes + mapOf(
                                DiagnosticAttribute.PROVIDER_ERROR_CODE to
                                    DiagnosticAttributeValue.Text(discovery.error.code.name),
                                DiagnosticAttribute.PROVIDER_RETRYABLE to
                                    DiagnosticAttributeValue.Flag(discovery.error.retryable),
                            ),
                        )
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.MATCH,
                            outcome = DiagnosticOutcome.SKIPPED,
                            attributes = providerAttributes,
                        )
                        emptyList()
                    }

                    TorrentDiscoveryResult.Threw -> {
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.SEARCH,
                            outcome = DiagnosticOutcome.THREW,
                            attributes = providerAttributes,
                        )
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.MATCH,
                            outcome = DiagnosticOutcome.SKIPPED,
                            attributes = providerAttributes,
                        )
                        emptyList()
                    }

                    is TorrentDiscoveryResult.Success -> {
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.SEARCH,
                            outcome = if (discovery.candidateCount == 0) {
                                DiagnosticOutcome.EMPTY
                            } else {
                                DiagnosticOutcome.CANDIDATES
                            },
                            attributes = providerAttributes + mapOf(
                                DiagnosticAttribute.CANDIDATE_COUNT to
                                    DiagnosticAttributeValue.Number(discovery.candidateCount.toLong()),
                            ),
                        )
                        providerTrace?.event(
                            subsystem = DiagnosticSubsystem.CONTENT,
                            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                            stage = DiagnosticStage.MATCH,
                            outcome = if (discovery.matches.isEmpty()) {
                                DiagnosticOutcome.EMPTY
                            } else {
                                DiagnosticOutcome.CANDIDATES
                            },
                            attributes = providerAttributes + mapOf(
                                DiagnosticAttribute.CANDIDATE_COUNT to
                                    DiagnosticAttributeValue.Number(discovery.matches.size.toLong()),
                            ),
                        )
                        discovery.matches
                    }
                }
            }
            .distinct()
            .sortedWith(
                compareBy<ProviderChapterTorrentOption>(
                    { it.providerId.value },
                    { it.candidate.infoHash.orEmpty() },
                    { it.selectedFile.index },
                    { it.selectedFile.path },
                ),
            )
    }

    private suspend fun discoverMatches(
        canonicalChapterId: String,
        providerId: ProviderId,
        titles: List<String>,
        chapterNumber: String,
        volume: Int?,
        request: TorrentChapterRequest,
        mapper: TorrentChapterMapper,
    ): TorrentDiscoveryResult {
        return try {
            val matches = linkedSetOf<ProviderChapterTorrentOption>()
            val seenCursors = mutableSetOf<String>()
            var candidateCount = 0
            var cursor: ProviderCursor? = null
            var pageCount = 0
            while (true) {
                if (++pageCount > MAX_SEARCH_PAGES) {
                    return TorrentDiscoveryResult.Success(
                        candidateCount = 0,
                        matches = emptyList(),
                    )
                }
                val page = when (
                    val result = gateway.search(
                        providerId = providerId,
                        request = TorrentSearchRequest(
                            titles = titles,
                            chapterNumber = chapterNumber,
                            volume = volume,
                            cursor = cursor,
                        ),
                    )
                ) {
                    is ProviderCallResult.Failure -> return TorrentDiscoveryResult.Failure(result.error)
                    is ProviderCallResult.Success -> result.value
                }
                candidateCount += page.items.size
                if (candidateCount > MAX_SEARCH_ITEMS) {
                    return TorrentDiscoveryResult.Success(
                        candidateCount = 0,
                        matches = emptyList(),
                    )
                }

                hydratePage(providerId, page.items).forEach { candidate ->
                    if (candidate.infoHash == null) return@forEach
                    val file = when (val match = mapper.map(request, candidate)) {
                        is TorrentChapterFileMatch.Exact -> match.file
                        TorrentChapterFileMatch.None,
                        is TorrentChapterFileMatch.Ambiguous,
                        -> return@forEach
                    }
                    matches += ProviderChapterTorrentOption(
                        canonicalChapterId = canonicalChapterId,
                        providerId = providerId,
                        candidate = candidate,
                        selectedFile = file,
                    )
                }

                if (matches.size > 1) {
                    return TorrentDiscoveryResult.Success(
                        candidateCount = candidateCount,
                        matches = matches.toList(),
                    )
                }

                val next = page.nextCursor ?: break
                if (!seenCursors.add(next.value)) {
                    return TorrentDiscoveryResult.Success(
                        candidateCount = 0,
                        matches = emptyList(),
                    )
                }
                cursor = next
            }
            TorrentDiscoveryResult.Success(
                candidateCount = candidateCount,
                matches = matches.toList(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            TorrentDiscoveryResult.Threw
        }
    }

    private suspend fun hydratePage(
        providerId: ProviderId,
        candidates: List<TorrentCandidate>,
    ): List<TorrentCandidate> = coroutineScope {
        candidates.map { candidate ->
            async {
                if (candidate.files.orEmpty().isNotEmpty()) {
                    candidate
                } else {
                    when (val result = metadataGateway.hydrate(providerId, candidate)) {
                        is ProviderCallResult.Failure -> null
                        is ProviderCallResult.Success -> result.value
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun buildSearchTitles(
        displayTitle: String,
        observations: List<TitleNameObservation>,
    ): List<String> {
        val ordered = buildList {
            add(displayTitle)
            observations
                .sortedWith(
                    compareByDescending<TitleNameObservation> { it.updatedAt }
                        .thenBy { it.provider }
                        .thenBy { it.sourceKey }
                        .thenBy { it.value },
                )
                .forEach { add(it.value) }
        }
        val seen = mutableSetOf<String>()
        return ordered.map(String::trim)
            .filter(String::isNotEmpty)
            .filter { seen.add(it.lowercase()) }
            .take(MAX_SEARCH_TITLES)
    }

    private sealed interface TorrentDiscoveryResult {
        data class Success(
            val candidateCount: Int,
            val matches: List<ProviderChapterTorrentOption>,
        ) : TorrentDiscoveryResult

        data class Failure(val error: ProviderError) : TorrentDiscoveryResult

        data object Threw : TorrentDiscoveryResult
    }

    private companion object {
        const val MAX_SEARCH_PAGES = 8
        const val MAX_SEARCH_ITEMS = 2_000
        const val MAX_SEARCH_TITLES = 16
    }
}
