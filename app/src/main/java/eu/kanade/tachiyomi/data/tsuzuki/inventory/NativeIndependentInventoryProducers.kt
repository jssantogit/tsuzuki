package eu.kanade.tachiyomi.data.tsuzuki.inventory

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventoryProducer
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryReleaseRecord
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.MangaDexReleaseEvidenceAdapter
import tachiyomi.domain.tsuzuki.chapter.inventory.MangaUpdatesReleaseEvidenceAdapter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Clock

internal val MANGADEX_WORK_UUID_REGEX = Regex(
    """^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$""",
)

/**
 * Read-only native metadata transport. Only these two API hosts/endpoints are allowed.
 * Requests are serialized, spaced at least 1.2 seconds apart, cached briefly, and
 * never retried automatically after 403/429. This is not a content/Reader client.
 */
data class InventoryHttpPayload(
    val content: String,
    val observedAt: Long,
)

interface InventoryReleaseTransport {
    /** A cache hit must retain the timestamp of the original HTTP fetch. */
    suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload

    /** Confirmed identity writes require an actual network revalidation, never cached evidence. */
    suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload

    /** Guard an explicitly authorized read before and after transport access. */
    suspend fun fetchAuthorized(
        url: String,
        postBody: String?,
        isEnabled: suspend () -> Boolean,
    ): InventoryHttpPayload {
        check(isEnabled()) { "Inventory integration is disabled" }
        val payload = fetch(url, postBody)
        check(isEnabled()) { "Inventory integration was disabled during the request" }
        return payload
    }

    /** Guard a fresh read before and after transport access. */
    suspend fun fetchFreshAuthorized(
        url: String,
        postBody: String?,
        isEnabled: suspend () -> Boolean,
    ): InventoryHttpPayload {
        check(isEnabled()) { "Inventory integration is disabled" }
        val payload = fetchFresh(url, postBody)
        check(isEnabled()) { "Inventory integration was disabled during the request" }
        return payload
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class NativeInventoryReleaseTransport(
    private val networkHelper: NetworkHelper,
) : InventoryReleaseTransport {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Pair<Long, InventoryHttpPayload>>()
    private val blockedUntilByHost = mutableMapOf<String, Long>()
    private val metadataClient by lazy {
        // The public metadata client must never inherit tracker session cookies,
        // Cloudflare WebView challenge bypasses or automatic off-host redirects.
        networkHelper.client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .apply { interceptors().removeAll { it is CloudflareInterceptor } }
            .build()
    }
    private var lastRequestAtNanos: Long = 0L

    override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload =
        fetchWithCachePolicy(url, postBody, forceNetwork = false)

    override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
        fetchWithCachePolicy(url, postBody, forceNetwork = true)

    override suspend fun fetchAuthorized(
        url: String,
        postBody: String?,
        isEnabled: suspend () -> Boolean,
    ): InventoryHttpPayload = fetchWithCachePolicy(
        url,
        postBody,
        forceNetwork = false,
        isEnabled = isEnabled,
    )

    override suspend fun fetchFreshAuthorized(
        url: String,
        postBody: String?,
        isEnabled: suspend () -> Boolean,
    ): InventoryHttpPayload = fetchWithCachePolicy(
        url,
        postBody,
        forceNetwork = true,
        isEnabled = isEnabled,
    )

    private suspend fun fetchWithCachePolicy(
        url: String,
        postBody: String?,
        forceNetwork: Boolean,
        isEnabled: (suspend () -> Boolean)? = null,
    ): InventoryHttpPayload = mutex.withLock {
        suspend fun checkEnabled() {
            check(isEnabled?.invoke() != false) { "Inventory integration is disabled" }
        }

        currentCoroutineContext().ensureActive()
        checkEnabled()
        val parsed = url.toHttpUrlOrFail()
        val allowMu = parsed.host == "api.mangaupdates.com" &&
            parsed.encodedPath == "/v1/releases/search" && postBody != null
        // Title discovery stays bounded and read-only. Never permit arbitrary
        // API methods, pages or filters through the metadata transport.
        val muSearch = postBody?.takeIf { it.length <= 1024 }?.let { body ->
            runCatching { strictJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        }
        val allowMuDiscovery = parsed.host == "api.mangaupdates.com" &&
            parsed.encodedPath == "/v1/series/search" &&
            muSearch != null &&
            muSearch.keys == setOf("search", "stype", "page", "perpage") &&
            (muSearch.optionalString("search")?.length ?: 0) in 2..160 &&
            muSearch.optionalString("stype") == "title" &&
            (muSearch["page"] as? JsonPrimitive)?.intOrNull == 1 &&
            (muSearch["perpage"] as? JsonPrimitive)?.intOrNull == 10
        val allowMdDiscovery = parsed.host == "api.mangadex.org" &&
            parsed.encodedPath == "/manga" && postBody == null &&
            parsed.queryParameterNames == setOf("title", "limit") &&
            parsed.queryParameterValues("title").size == 1 &&
            (parsed.queryParameter("title")?.trim()?.length ?: 0) in 2..160 &&
            parsed.queryParameterValues("limit") == listOf("10")
        val mangaDexPath = parsed.encodedPath.split('/')
        val validMangaDexShape = when (mangaDexPath.size) {
            3 -> true
            4 -> mangaDexPath[3] == "feed"
            else -> false
        }
        val allowMd = parsed.host == "api.mangadex.org" &&
            validMangaDexShape &&
            mangaDexPath[1] == "manga" &&
            mangaDexPath[2].matches(MANGADEX_WORK_UUID_REGEX) &&
            postBody == null
        require(
            parsed.scheme == "https" && parsed.port == 443 &&
                parsed.username.isEmpty() && parsed.password.isEmpty() &&
                (allowMu || allowMd || allowMuDiscovery || allowMdDiscovery),
        ) {
            "Only allowlisted read-only metadata endpoints are permitted"
        }
        val now = System.nanoTime()
        blockedUntilByHost[parsed.host]?.let { blockedUntil ->
            if (now < blockedUntil) throw IOException("Inventory source is temporarily blocked")
            blockedUntilByHost.remove(parsed.host)
        }
        val key = url + "\n" + postBody.orEmpty()
        if (!forceNetwork) {
            cache[key]?.takeIf { now - it.first in 0 until CACHE_TTL_NANOS }?.let {
                checkEnabled()
                return@withLock it.second
            }
        }

        if (lastRequestAtNanos != 0L) {
            val elapsed = (now - lastRequestAtNanos) / 1_000_000L
            if (elapsed < MIN_REQUEST_SPACING_MS) delay(MIN_REQUEST_SPACING_MS - elapsed)
        }
        currentCoroutineContext().ensureActive()
        checkEnabled()
        lastRequestAtNanos = System.nanoTime()

        val request = Request.Builder()
            .url(parsed)
            .header("Accept", "application/json")
            .header("User-Agent", "Tsuzuki-Inventory/1.0 (https://github.com/jssantogit/tsuzuki)")
            .apply {
                if (postBody == null) {
                    get()
                } else {
                    post(postBody.toRequestBody("application/json".toMediaType()))
                }
            }
            .build()
        val response = metadataClient.newCall(request).await()
        response.use {
            currentCoroutineContext().ensureActive()
            checkEnabled()
            when (it.code) {
                403 -> blockedUntilByHost[parsed.host] = Long.MAX_VALUE
                429 -> {
                    val durationMs = retryAfterMilliseconds(it.header("Retry-After"))
                    blockedUntilByHost[parsed.host] = System.nanoTime() + durationMs * 1_000_000L
                }
            }
            if (!it.isSuccessful) {
                throw IOException("Independent inventory endpoint returned HTTP " + it.code)
            }
            val responseBody = it.body ?: throw IOException("Empty inventory response")
            if (responseBody.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("Inventory response exceeded the byte budget")
            }
            val bytes = withContext(Dispatchers.IO) {
                responseBody.byteStream().use { stream ->
                    val result = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val size = stream.read(chunk)
                        if (size < 0) break
                        if (result.size() + size > MAX_RESPONSE_BYTES) {
                            throw IOException("Inventory response exceeded the byte budget")
                        }
                        result.write(chunk, 0, size)
                    }
                    result.toString(Charsets.UTF_8.name())
                }
            }
            val payload = InventoryHttpPayload(
                content = bytes,
                observedAt = Clock.System.now().toEpochMilliseconds(),
            )
            currentCoroutineContext().ensureActive()
            checkEnabled()
            if (cache.size >= MAX_CACHE_ENTRIES) cache.remove(cache.keys.first())
            cache[key] = System.nanoTime() to payload
            payload
        }
    }

    private fun String.toHttpUrlOrFail(): HttpUrl = toHttpUrl()

    private fun retryAfterMilliseconds(value: String?): Long {
        val numericSeconds = value?.trim()?.toLongOrNull()
        if (numericSeconds != null) return (numericSeconds.coerceIn(1L, 3600L) * 1000L)
        val httpDateDelay = runCatching {
            ZonedDateTime.parse(value.orEmpty(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() -
                System.currentTimeMillis()
        }.getOrNull()
        return (httpDateDelay ?: DEFAULT_RETRY_AFTER_MS).coerceIn(DEFAULT_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
    }

    private companion object {
        const val MIN_REQUEST_SPACING_MS = 1200L
        const val CACHE_TTL_NANOS = 300_000_000_000L
        const val MAX_CACHE_ENTRIES = 32
        const val MAX_RESPONSE_BYTES = 512_000L
        const val DEFAULT_RETRY_AFTER_MS = 60_000L
        const val MAX_RETRY_AFTER_MS = 3_600_000L
    }
}

/** MangaUpdates produces release observations, never primary editorial claims. */
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<IndependentInventoryProducer>())
class MangaUpdatesInventoryProducer(
    private val transport: InventoryReleaseTransport,
) : IndependentInventoryProducer {
    override val producerId = "mangaupdates"

    override suspend fun fetchPage(scope: InventoryScope, pageKey: String): Result<InventoryEvidenceBatch> =
        catchingInventory {
            require(scope.producerId == producerId && scope.workIdentityVerified)
            require(scope.externalWorkId.matches(Regex("""^[0-9]{1,18}$""")))
            val page = when {
                pageKey == "start" -> 1
                pageKey.matches(Regex("""page:[1-9][0-9]{0,2}""")) ->
                    pageKey.removePrefix("page:").toInt()
                else -> error("Invalid MangaUpdates page cursor")
            }
            require(page in 1..100)
            val payload = buildJsonObject {
                put("search", scope.externalWorkId)
                put("search_type", "series")
                put("page", page)
                put("perpage", 25)
                put("include_metadata", true)
            }.toString()
            val response = transport.fetch("https://api.mangaupdates.com/v1/releases/search", payload)
            val root = parseObject(response.content)
            val reportedPage = root.requiredInt("page")
            val perPage = root.requiredInt("per_page")
            val totalReleases = root.requiredInt("total_hits")
            require(reportedPage == page && perPage in 1..25 && totalReleases >= 0)
            val records = root.requiredArray("results").map { item ->
                val result = item as? JsonObject ?: error("Invalid release result")
                val record = result.requiredObject("record")
                val sourceId = result.requiredObject("metadata").requiredObject("series")
                    .requiredString("series_id")
                require(sourceId == scope.externalWorkId) { "MangaUpdates returned a different series ID" }
                InventoryReleaseRecord(
                    releaseId = record.requiredString("id"),
                    chapterLabel = record.optionalString("chapter"),
                    volumeLabel = record.optionalString("volume"),
                    language = null,
                    upstreamSourceId = null,
                    externalWorkId = sourceId,
                )
            }
            val hasNextPage = page.toLong() * perPage.toLong() < totalReleases
            require(!hasNextPage || records.isNotEmpty()) { "Unexpected empty MangaUpdates page" }
            MangaUpdatesReleaseEvidenceAdapter.adapt(
                scope = scope,
                pageKey = pageKey,
                observedAt = response.observedAt,
                releases = records,
                hasNextPage = hasNextPage,
                nextCursor = if (hasNextPage && page < 100) "page:" + (page + 1) else null,
            )
        }
}

/** MangaDex relationships must prove the exact requested work for every record. */
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<IndependentInventoryProducer>())
class MangaDexInventoryProducer(
    private val transport: InventoryReleaseTransport,
) : IndependentInventoryProducer {
    override val producerId = "mangadex"

    override suspend fun fetchPage(scope: InventoryScope, pageKey: String): Result<InventoryEvidenceBatch> =
        catchingInventory {
            require(scope.producerId == producerId && scope.workIdentityVerified)
            require(scope.externalWorkId.matches(MANGADEX_WORK_UUID_REGEX))
            val offset = when {
                pageKey == "start" -> 0
                pageKey.matches(Regex("""offset:[1-9][0-9]{0,4}""")) ->
                    pageKey.removePrefix("offset:").toInt()
                else -> error("Invalid MangaDex offset cursor")
            }
            require(offset in 0..9900 && offset % 100 == 0)
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.mangadex.org")
                .addPathSegments("manga/" + scope.externalWorkId + "/feed")
                .addQueryParameter("limit", "100")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("contentRating[]", "safe")
                .addQueryParameter("contentRating[]", "suggestive")
                .addQueryParameter("includeFutureUpdates", "0")
                .build().toString()
            val response = transport.fetch(url, null)
            val root = parseObject(response.content)
            require(root.requiredString("result") == "ok")
            val totalReleases = root.requiredInt("total")
            val returnedOffset = root.requiredInt("offset")
            val limit = root.requiredInt("limit")
            require(returnedOffset == offset && limit in 1..100 && totalReleases >= 0)
            val data = root.requiredArray("data")
            require(data.size <= limit)
            val releases = data.map { item ->
                val release = item as? JsonObject ?: error("Invalid MangaDex release")
                val mangas = release.requiredArray("relationships").mapNotNull { link ->
                    val relationship = link as? JsonObject ?: error("Invalid MangaDex relationship")
                    if (relationship.optionalString("type") == "manga") relationship.requiredString("id") else null
                }
                require(mangas.size == 1 && mangas.single() == scope.externalWorkId) {
                    "MangaDex release did not verify the requested manga relationship"
                }
                val attributes = release.requiredObject("attributes")
                InventoryReleaseRecord(
                    releaseId = release.requiredString("id"),
                    chapterLabel = attributes.optionalString("chapter"),
                    volumeLabel = attributes.optionalString("volume"),
                    language = attributes.optionalString("translatedLanguage"),
                    upstreamSourceId = null,
                    externalWorkId = scope.externalWorkId,
                )
            }
            val hasNext = offset.toLong() + limit < totalReleases
            require(!hasNext || releases.isNotEmpty()) { "Unexpected empty MangaDex page" }
            MangaDexReleaseEvidenceAdapter.adapt(
                scope = scope,
                pageKey = pageKey,
                observedAt = response.observedAt,
                releases = releases,
                hasNextPage = hasNext,
                nextCursor = if (hasNext && offset + limit <= 9900) {
                    "offset:" + (offset + limit)
                } else {
                    null
                },
            )
        }
}

private suspend inline fun <T> catchingInventory(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

private val strictJson = Json { ignoreUnknownKeys = true }
private fun parseObject(text: String): JsonObject =
    strictJson.parseToJsonElement(text) as? JsonObject ?: error("Expected JSON object")

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject ?: error("Missing JSON object: " + name)

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name] as? JsonArray ?: error("Missing JSON array: " + name)

private fun JsonObject.requiredInt(name: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull ?: error("Invalid integer: " + name)

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: error("Missing string: " + name)

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
