package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.security.MessageDigest

/**
 * Connects enabled SCRIPT reading Providers to Tsuzuki's canonical chapter-evidence pipeline.
 *
 * Bindings are deliberately conservative: a Provider work is auto-bound only when exactly one
 * candidate is an exact normalized title/alias match for the canonical title and active facet.
 * Ambiguous results remain unbound rather than leaking fuzzy Provider identity into canonical state.
 */
data class ProviderReadingEvidenceCollection(
    val evidence: List<ChapterEvidence> = emptyList(),
    val bindingCount: Int = 0,
    val complete: Boolean = true,
)

class CollectProviderReadingEvidence private constructor(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val providerRegistry: ProviderRegistry,
    private val gateway: ProviderReadingGateway,
    private val bindingRepository: ProviderReadingBindingRepository,
    private val evidenceAdapter: ProviderChapterEvidenceAdapter,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalTitleRepository: CanonicalTitleRepository,
        providerRegistry: ProviderRegistry,
        gateway: ProviderReadingGateway,
        bindingRepository: ProviderReadingBindingRepository,
    ) : this(
        canonicalTitleRepository = canonicalTitleRepository,
        providerRegistry = providerRegistry,
        gateway = gateway,
        bindingRepository = bindingRepository,
        evidenceAdapter = ProviderChapterEvidenceAdapter(),
        clock = System::currentTimeMillis,
    )

    constructor(
        canonicalTitleRepository: CanonicalTitleRepository,
        providerRegistry: ProviderRegistry,
        gateway: ProviderReadingGateway,
        bindingRepository: ProviderReadingBindingRepository,
        evidenceAdapter: ProviderChapterEvidenceAdapter,
        clock: () -> Long,
    ) : this(
        canonicalTitleRepository = canonicalTitleRepository,
        providerRegistry = providerRegistry,
        gateway = gateway,
        bindingRepository = bindingRepository,
        evidenceAdapter = evidenceAdapter,
        clock = clock,
    )

    suspend fun execute(canonicalTitleId: String): ProviderReadingEvidenceCollection {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        providerRegistry.awaitReady()
        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: return ProviderReadingEvidenceCollection(complete = false)
        val targets = readingTargets()
        if (targets.isEmpty()) return ProviderReadingEvidenceCollection()

        val results = coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_PROVIDERS)
            targets.map { target ->
                async {
                    gate.withPermit {
                        collectTarget(
                            canonicalTitleId = canonicalTitleId,
                            title = canonicalTitle.displayTitle,
                            target = target,
                        )
                    }
                }
            }.awaitAll()
        }

        val evidenceById = linkedMapOf<String, ChapterEvidence>()
        var bindingCount = 0
        var complete = true
        results.forEach { result ->
            result.evidence.forEach { evidence -> evidenceById.putIfAbsent(evidence.id, evidence) }
            bindingCount += result.bindingCount
            complete = complete && result.complete
        }
        return ProviderReadingEvidenceCollection(
            evidence = evidenceById.values.toList(),
            bindingCount = bindingCount,
            complete = complete,
        )
    }

    fun configurationTokens(): List<String> = readingTargets().map { target ->
        buildString {
            append("provider:")
            append(target.registration.descriptor.id.value)
            append(':')
            append(target.registration.descriptor.version.code)
            append(':')
            append(target.facetId.orEmpty())
            append(':')
            append(target.registration.configurationFingerprint)
        }
    }

    private suspend fun collectTarget(
        canonicalTitleId: String,
        title: String,
        target: ReadingTarget,
    ): ProviderReadingEvidenceCollection {
        return try {
            val existing = bindingRepository.get(
                canonicalTitleId = canonicalTitleId,
                providerId = target.registration.descriptor.id,
                facetId = target.facetId,
            )?.takeIf { it.availability == ProviderBindingAvailability.AVAILABLE }
            val binding = existing ?: discoverBinding(
                canonicalTitleId = canonicalTitleId,
                title = title,
                target = target,
            ) ?: return ProviderReadingEvidenceCollection()

            val observedAt = clock()
            when (val observations = collectChapters(binding)) {
                is ProviderCallResult.Failure -> ProviderReadingEvidenceCollection(
                    bindingCount = 1,
                    complete = false,
                )
                is ProviderCallResult.Success -> ProviderReadingEvidenceCollection(
                    evidence = evidenceAdapter.toEvidence(
                        canonicalTitleId = canonicalTitleId,
                        binding = binding.ref,
                        observations = observations.value,
                        observedAt = observedAt,
                    ),
                    bindingCount = 1,
                    complete = true,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ProviderReadingEvidenceCollection(complete = false)
        }
    }

    private suspend fun discoverBinding(
        canonicalTitleId: String,
        title: String,
        target: ReadingTarget,
    ): ProviderReadingBinding? {
        val candidates = when (val result = collectLookup(target.registration.descriptor.id, title)) {
            is ProviderCallResult.Failure -> return null
            is ProviderCallResult.Success -> result.value
        }
        val normalizedTitle = normalize(title)
        val matches = candidates
            .asSequence()
            .filter { candidate ->
                target.facetId == null ||
                    candidate.language == null ||
                    candidate.language.equals(target.facetId, ignoreCase = true)
            }
            .filter { candidate ->
                sequenceOf(candidate.title)
                    .plus(candidate.aliases.asSequence())
                    .any { normalize(it) == normalizedTitle }
            }
            .distinctBy(ProviderWorkCandidate::externalWorkId)
            .toList()
        if (matches.size != 1) return null

        val now = clock()
        val candidate = matches.single()
        return ProviderReadingBinding(
            id = bindingId(
                canonicalTitleId = canonicalTitleId,
                providerId = target.registration.descriptor.id,
                facetId = target.facetId,
                externalWorkId = candidate.externalWorkId,
            ),
            canonicalTitleId = canonicalTitleId,
            ref = ProviderBindingRef(
                providerId = target.registration.descriptor.id,
                facetId = target.facetId,
                externalWorkId = candidate.externalWorkId,
            ),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = now,
            updatedAt = now,
        ).also { bindingRepository.upsert(it) }
    }

    private suspend fun collectLookup(
        providerId: ProviderId,
        title: String,
    ): ProviderCallResult<List<ProviderWorkCandidate>> {
        val items = mutableListOf<ProviderWorkCandidate>()
        val seenCursors = mutableSetOf<String>()
        var cursor: ProviderCursor? = null
        var pageCount = 0
        while (true) {
            if (++pageCount > MAX_LOOKUP_PAGES) return malformed()
            val page = when (
                val result = gateway.lookup(
                    providerId = providerId,
                    request = ProviderReadingLookupRequest(
                        titles = listOf(title),
                        cursor = cursor,
                    ),
                )
            ) {
                is ProviderCallResult.Failure -> return result
                is ProviderCallResult.Success -> result.value
            }
            items += page.items
            if (items.size > MAX_LOOKUP_ITEMS) return malformed()
            val next = page.nextCursor ?: break
            if (!seenCursors.add(next.value)) return malformed()
            cursor = next
        }
        return ProviderCallResult.Success(items)
    }

    private suspend fun collectChapters(
        binding: ProviderReadingBinding,
    ): ProviderCallResult<List<ProviderChapterObservation>> {
        val items = mutableListOf<ProviderChapterObservation>()
        val chapterIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor: ProviderCursor? = null
        var pageCount = 0
        while (true) {
            if (++pageCount > MAX_CHAPTER_PAGES) return malformed()
            val page = when (
                val result = gateway.chapters(
                    providerId = binding.ref.providerId,
                    request = ProviderReadingChaptersRequest(
                        binding = binding.ref,
                        cursor = cursor,
                    ),
                )
            ) {
                is ProviderCallResult.Failure -> return result
                is ProviderCallResult.Success -> result.value
            }
            for (observation in page.items) {
                if (!chapterIds.add(observation.providerChapterId)) return malformed()
                items += observation
            }
            if (items.size > MAX_CHAPTER_ITEMS) return malformed()
            val next = page.nextCursor ?: break
            if (!seenCursors.add(next.value)) return malformed()
            cursor = next
        }
        return ProviderCallResult.Success(items)
    }

    private fun readingTargets(): List<ReadingTarget> = providerRegistry.providers()
        .asSequence()
        .filter { registration -> registration.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
        .filter { registration -> registration.descriptor.runtime == ProviderRuntimeKind.SCRIPT }
        .filter { registration -> REQUIRED_CAPABILITIES.all { it in registration.enabledCapabilities } }
        .flatMap { registration ->
            val facets = registration.facets.map { it.facetId }.ifEmpty { listOf(null) }
            facets.asSequence().map { facetId -> ReadingTarget(registration, facetId) }
        }
        .sortedWith(
            compareBy<ReadingTarget>(
                { it.registration.descriptor.id.value },
                { it.facetId.orEmpty() },
            ),
        )
        .toList()

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    private fun bindingId(
        canonicalTitleId: String,
        providerId: ProviderId,
        facetId: String?,
        externalWorkId: String,
    ): String {
        val material = listOf(
            canonicalTitleId,
            providerId.value,
            facetId.orEmpty(),
            externalWorkId,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "provider-binding:$digest"
    }

    private fun <T> malformed(): ProviderCallResult<T> = ProviderCallResult.Failure(
        ProviderError(
            code = ProviderErrorCode.MALFORMED_RESULT,
            retryable = false,
        ),
    )

    private data class ReadingTarget(
        val registration: ProviderRegistration,
        val facetId: String?,
    )

    private companion object {
        val REQUIRED_CAPABILITIES = setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        )
        val WHITESPACE = Regex("\\s+")
        const val MAX_CONCURRENT_PROVIDERS = 4
        const val MAX_LOOKUP_PAGES = 8
        const val MAX_LOOKUP_ITEMS = 2_000
        const val MAX_CHAPTER_PAGES = 64
        const val MAX_CHAPTER_ITEMS = 20_000
    }
}
