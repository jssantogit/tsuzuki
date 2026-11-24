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
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import java.text.Normalizer

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
    ): MangaAllCatalogIdentityResult = coroutineScope {
        val query = title.trim()
        require(query.isNotBlank() && query.length <= 256)
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
            async { gate.withPermit { searchFully(source, query, format) } }
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
        when (exact.size) {
            0 -> return@coroutineScope fields
            1 -> Unit
            else -> return@coroutineScope fields.copy(
                status = MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS,
            )
        }

        val selected = exact.single()
        val owner = registry.metadataProviders().singleOrNull { it.integrationId.value == selected.provider }
            ?: return@coroutineScope fields.copy(
                status = MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED,
            )
        val detail = try {
            withTimeoutOrNull(DETAIL_TIMEOUT_MILLIS) { owner.getDetails(selected.providerId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        val detailError = detail?.exceptionOrNull()
        if (detailError is CancellationException) throw detailError
        val verified = detail?.getOrNull()?.takeIf {
            it.provider == selected.provider &&
                it.providerId == selected.providerId &&
                selected.externalIds.all { (provider, id) ->
                    it.externalIds[provider] == null || it.externalIds[provider] == id
                } &&
                (listOf(it.title) + it.titles.values).any { name ->
                    normalize(name) == normalize(query) || normalize(name) == normalize(selected.title)
                }
        } ?: return@coroutineScope fields.copy(status = MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED)

        fields.copy(
            status = MangaAllCatalogIdentityStatus.RESOLVED,
            identity = verified,
            creatorsAttested = verified.authors.isNotEmpty() || verified.artists.isNotEmpty(),
        )
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
        return withTimeoutOrNull(CATALOG_TIMEOUT_MILLIS) {
            val seen = mutableListOf<CatalogItem>()
            val seenIds = mutableSetOf<String>()
            var expectedTotal: Int? = null
            var offset = 0
            for (index in 0 until MAX_PAGES_PER_CATALOG) {
                val result = try {
                    source.search(
                        CatalogQuery(query = title, format = format, offset = offset, limit = PAGE_SIZE),
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
                if (page.items.size > PAGE_SIZE ||
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
                if (!page.hasNextPage) return@withTimeoutOrNull SourceSearchResult(seen, complete = true)
                if (page.items.isEmpty()) return@withTimeoutOrNull SourceSearchResult(seen)
                offset += page.items.size
            }
            SourceSearchResult(seen, complete = false)
        } ?: SourceSearchResult(failed = true)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(WHITESPACE, " ")

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGES_PER_CATALOG = 4
        const val MAX_CONCURRENT_CATALOGS = 3
        const val CATALOG_TIMEOUT_MILLIS = 15_000L
        const val DETAIL_TIMEOUT_MILLIS = 10_000L
        val COMBINING_MARKS = Regex("""\p{M}+""")
        val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
        val WHITESPACE = Regex("""\s+""")
    }
}
