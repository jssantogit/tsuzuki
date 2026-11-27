package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.mergeCatalogItemsByVerifiedIdentity
import tachiyomi.domain.tsuzuki.identity.foldLatinIdentityDiacritics
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.SearchProvider

/**
 * Fail-closed identity bootstrap for arbitrary MangaAll CI input.
 *
 * Uses the actual enabled, independent Tsuzuki SearchProvider and MetadataProvider
 * implementations. A title match authorizes neither a MangaAll HID nor an
 * editorial edition. Only catalog-published provider IDs/crosswalks establish
 * catalog identity, and the owning metadata endpoint must corroborate that ID.
 *
 * The ordinary UI search can return partial results for responsiveness; unlike
 * it, this certification path must not select a candidate when any enabled
 * independent catalog failed or its pagination budget was exhausted.
 */
enum class MangaAllCatalogIdentityStatus {
    RESOLVED,
    CATALOG_AMBIGUOUS,
    CATALOG_NOT_FOUND,
    CATALOG_UNAVAILABLE,
    CATALOG_INCOMPLETE,
    CATALOG_UNVERIFIED,
    CATALOG_FORMAT_UNSUPPORTED,
}

data class MangaAllCatalogIdentityResult(
    val status: MangaAllCatalogIdentityStatus,
    val identity: CatalogItem? = null,
    val candidateCount: Int = 0,
    val catalogsQueried: Int = 0,
    val catalogsFailed: Int = 0,
    /** True only when the independent metadata detail endpoint supplied actual author/artist credits. */
    val creatorsAttested: Boolean = false,
) {
    init {
        require((status == MangaAllCatalogIdentityStatus.RESOLVED) == (identity != null))
    }
}

@Inject
class ResolveMangaAllIndependentCatalogIdentity(
    private val registry: IntegrationRegistry,
) {
    suspend fun execute(
        title: String,
        format: CatalogItemFormat? = null,
        searchTitle: String? = null,
        requiredCreatorNames: List<String> = emptyList(),
    ): MangaAllCatalogIdentityResult = coroutineScope {
        val query = title.trim()
        require(query.isNotBlank() && query.length <= 256)
        // A more selective native-title query is a retrieval hint, not an identity override.
        val retrievalQuery = searchTitle?.trim() ?: query
        require(retrievalQuery.isNotBlank() && retrievalQuery.length <= 256)
        require(requiredCreatorNames.size <= 8 && requiredCreatorNames.all { it.isNotBlank() && it.length <= 128 })
        val requiredCreators = requiredCreatorNames.map(::creatorKey).toSet()
        require(requiredCreators.none { it.isBlank() })
        registry.awaitReady()
        // A reading provider can never self-corroborate its own candidate.
        val sources = registry.searchProviders()
            .filterNot { it.integrationId.value.startsWith("app.tsuzuki.mangaall") }
            .sortedBy { it.integrationId.value }
        if (sources.size != sources.distinctBy { it.integrationId }.size) {
            return@coroutineScope MangaAllCatalogIdentityResult(
                status = MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE,
                catalogsQueried = sources.size,
                catalogsFailed = sources.size,
            )
        }
        if (sources.isEmpty()) {
            return@coroutineScope MangaAllCatalogIdentityResult(
                status = MangaAllCatalogIdentityStatus.CATALOG_UNAVAILABLE,
            )
        }

        val gate = Semaphore(MAX_CONCURRENT_CATALOGS)
        val results = sources.map { source ->
            async { gate.withPermit { searchFully(source, retrievalQuery, format) } }
        }.awaitAll()
        val failures = results.count { it.failed }
        val observed = results.flatMap(SourceSearchResult::items)
        if (failures == results.size) {
            return@coroutineScope MangaAllCatalogIdentityResult(
                status = MangaAllCatalogIdentityStatus.CATALOG_UNAVAILABLE,
                catalogsQueried = sources.size,
                catalogsFailed = failures,
            )
        }
        if (failures > 0 || results.any { !it.complete }) {
            return@coroutineScope MangaAllCatalogIdentityResult(
                status = MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE,
                catalogsQueried = sources.size,
                catalogsFailed = failures,
            )
        }

        // The UI merger is intentionally permissive for presentation. Before
        // using it as identity evidence, reject contradictions in any connected
        // component relevant to the requested title, including indirect links.
        val relevant = observed.filter { item ->
            (
                format == null || format == CatalogItemFormat.UNKNOWN ||
                    item.format == CatalogItemFormat.UNKNOWN || item.format == format
                ) &&
                (listOf(item.title) + item.titles.values).any { normalize(it) == normalize(query) }
        }
        if (relevant.any { hasConflictingIdentity(it, observed) }) {
            return@coroutineScope MangaAllCatalogIdentityResult(
                status = MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS,
                catalogsQueried = sources.size,
            )
        }
        // This is the same verified cross-provider identity merger used by
        // SearchIntegrations; it never merges by title similarity alone.
        val exact = mergeCatalogItemsByVerifiedIdentity(observed).filter { item ->
            (
                format == null || format == CatalogItemFormat.UNKNOWN ||
                    item.format == CatalogItemFormat.UNKNOWN || item.format == format
                ) &&
                (listOf(item.title) + item.titles.values).any { normalize(it) == normalize(query) }
        }
        val fields = MangaAllCatalogIdentityResult(
            status = MangaAllCatalogIdentityStatus.CATALOG_NOT_FOUND,
            candidateCount = exact.size,
            catalogsQueried = sources.size,
        )
        if (exact.isEmpty()) return@coroutineScope fields
        if (exact.size > 1 && (requiredCreators.isEmpty() || exact.size > MAX_CREATOR_CANDIDATES)) {
            return@coroutineScope fields.copy(status = MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS)
        }

        // Published creator constraints narrow a complete set of exact-title
        // matches; they never synthesize an owner ID, alias or creator credit.
        // Missing detail/credits, or partially matching credits, cannot rule
        // out an edition. Do not select the first available candidate.
        val verified = exact.map { candidate ->
            async {
                gate.withPermit { verifyDetail(candidate, query, retrievalQuery, format) }
            }
        }.awaitAll()
        if (verified.any { it == null }) {
            return@coroutineScope fields.copy(status = MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED)
        }
        val details = verified.filterNotNull()
        val selected = if (requiredCreators.isEmpty()) {
            details.single()
        } else {
            val candidates = details.map { item ->
                item to (item.authors + item.artists).map(::creatorKey).filter(String::isNotBlank).toSet()
            }
            val matches = candidates.filter { (_, names) -> names.containsAll(requiredCreators) }
            val uncertain = candidates.any { (_, names) ->
                names.isEmpty() || (!names.containsAll(requiredCreators) && names.any(requiredCreators::contains))
            }
            if (matches.size != 1 || uncertain) {
                return@coroutineScope fields.copy(
                    status = if (matches.size > 1 || (uncertain && exact.size > 1)) {
                        MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS
                    } else {
                        MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
                    },
                )
            }
            matches.single().first
        }
        fields.copy(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = selected,
            creatorsAttested = selected.authors.isNotEmpty() || selected.artists.isNotEmpty(),
        )
    }

    private suspend fun verifyDetail(
        selected: CatalogItem,
        query: String,
        retrievalQuery: String,
        format: CatalogItemFormat?,
    ): CatalogItem? {
        val owner = registry.metadataProviders().singleOrNull { it.integrationId.value == selected.provider }
            ?: return null
        val detail = try {
            withTimeoutOrNull(DETAIL_TIMEOUT_MILLIS) { owner.getDetails(selected.providerId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val detailError = detail?.exceptionOrNull()
        if (detailError is CancellationException) throw detailError
        return detail?.getOrNull()?.takeIf {
            it.provider == selected.provider &&
                it.providerId == selected.providerId &&
                (format == null || format == CatalogItemFormat.UNKNOWN || it.format == format) &&
                (selected.format == CatalogItemFormat.UNKNOWN || it.format == selected.format) &&
                (it.externalIds[it.provider] == null || it.externalIds[it.provider] == it.providerId) &&
                selected.externalIds.all { (provider, id) ->
                    it.externalIds[provider] == null || it.externalIds[provider] == id
                } &&
                (listOf(it.title) + it.titles.values).any { name ->
                    normalize(name) == normalize(query)
                } &&
                (listOf(it.title) + it.titles.values).any { name ->
                    normalize(name) == normalize(retrievalQuery)
                }
        }
    }

    private fun creatorKey(name: String): String = mangaAllCatalogCreatorKey(name)

    private fun hasConflictingIdentity(seed: CatalogItem, observed: List<CatalogItem>): Boolean {
        fun keys(item: CatalogItem): Set<Pair<String, String>> = buildSet {
            add(item.provider to item.providerId)
            addAll(item.externalIds.entries.map { it.key to it.value })
        }
        val component = mutableSetOf(seed)
        val known = keys(seed).toMutableSet()
        var changed: Boolean
        do {
            changed = false
            for (item in observed) {
                if (item !in component && keys(item).any(known::contains)) {
                    component += item
                    known += keys(item)
                    changed = true
                }
            }
        } while (changed)
        return known.groupBy({ it.first }, { it.second }).values.any { it.distinct().size > 1 } ||
            component.map { it.format }.filter { it != CatalogItemFormat.UNKNOWN }.distinct().size > 1
    }

    private data class SourceSearchResult(
        val items: List<CatalogItem> = emptyList(),
        val complete: Boolean = false,
        val failed: Boolean = false,
    )

    private suspend fun searchFully(
        source: SearchProvider,
        title: String,
        format: CatalogItemFormat?,
    ): SourceSearchResult {
        // Typed MangaUpdates and Japanese Shikimori searches can exceed
        // 100 candidates. Keep a bounded larger budget without treating
        // exhaustion as complete.
        val narrowed = (
            source.integrationId.value == "mangaupdates" &&
                format in setOf(CatalogItemFormat.MANHWA, CatalogItemFormat.MANGA)
            ) ||
            (source.integrationId.value == "shikimori" && format == CatalogItemFormat.MANGA)
        val maxPages = if (narrowed) MAX_NARROWED_PAGES_PER_CATALOG else MAX_PAGES_PER_CATALOG
        val timeout = if (narrowed) NARROWED_CATALOG_TIMEOUT_MILLIS else CATALOG_TIMEOUT_MILLIS
        // Kitsu JSON:API caps manga pages at twenty records.
        val pageSize = if (source.integrationId.value == "kitsu") 20 else PAGE_SIZE
        return withTimeoutOrNull(timeout) {
            val seen = mutableListOf<CatalogItem>()
            val seenIds = mutableSetOf<String>()
            var expectedTotal: Int? = null
            var offset = 0
            for (index in 0 until maxPages) {
                val result = try {
                    source.search(
                        CatalogQuery(query = title, format = format, offset = offset, limit = pageSize),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withTimeoutOrNull SourceSearchResult(failed = true)
                }
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                val page: CatalogPage = result.getOrNull()
                    ?: return@withTimeoutOrNull SourceSearchResult(failed = true)
                if (page.items.size > pageSize ||
                    page.items.any { it.provider != source.integrationId.value || it.providerId.isBlank() }
                ) {
                    return@withTimeoutOrNull SourceSearchResult(failed = true)
                }
                if (page.items.any { !seenIds.add(it.providerId) }) {
                    return@withTimeoutOrNull SourceSearchResult(seen)
                }
                seen += page.items
                val reportedTotal = page.totalCount
                if (expectedTotal != null && reportedTotal != expectedTotal) {
                    return@withTimeoutOrNull SourceSearchResult(seen)
                }
                if (reportedTotal != null) expectedTotal = reportedTotal
                if (reportedTotal != null && (
                        reportedTotal < seen.size ||
                            (!page.hasNextPage && reportedTotal > seen.size) ||
                            (page.hasNextPage && reportedTotal == seen.size)
                        )
                ) {
                    return@withTimeoutOrNull SourceSearchResult(seen)
                }
                // An advertised total beyond the remaining bounded capacity
                // can never be fully observed in this invocation. Avoid
                // wasting upstream requests, but preserve INCOMPLETE.
                if (reportedTotal != null &&
                    reportedTotal > seen.size + (maxPages - index - 1) * pageSize
                ) {
                    return@withTimeoutOrNull SourceSearchResult(seen)
                }
                if (!page.hasNextPage) return@withTimeoutOrNull SourceSearchResult(seen, complete = true)
                if (page.items.isEmpty()) return@withTimeoutOrNull SourceSearchResult(seen)
                offset += page.items.size
            }
            SourceSearchResult(seen, complete = false)
        } ?: SourceSearchResult(failed = true)
    }

    private fun normalize(value: String): String = normalizeMangaAllCatalogTitle(value)

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGES_PER_CATALOG = 4
        const val MAX_NARROWED_PAGES_PER_CATALOG = 12
        const val NARROWED_CATALOG_TIMEOUT_MILLIS = 24_000L
        const val MAX_CONCURRENT_CATALOGS = 3
        const val MAX_CREATOR_CANDIDATES = 8
        const val CATALOG_TIMEOUT_MILLIS = 15_000L
        const val DETAIL_TIMEOUT_MILLIS = 10_000L
    }
}

private val CATALOG_NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}\p{M}]+""")
private val CATALOG_WHITESPACE = Regex("""\s+""")

/** Fold Latin accents, not meaningful marks in other writing systems. */
private fun normalizeMangaAllCatalogTitle(value: String): String {
    return foldLatinIdentityDiacritics(value).lowercase()
        .replace(CATALOG_NON_ALPHANUMERIC, " ").trim().replace(CATALOG_WHITESPACE, " ")
}

/** Compare complete published names, permitting given/family name order. */
fun mangaAllCatalogCreatorKey(name: String): String =
    normalizeMangaAllCatalogTitle(name).split(' ').filter(String::isNotBlank).sorted().joinToString(" ")
