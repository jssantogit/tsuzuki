package tachiyomi.core.provider.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

/**
 * NONSHIPPING, Host-private MangaALL capacity path.
 *
 * Acquires complete, explicitly source-scoped MangaDot arrays through the EXISTING
 * host-only, 16 MiB resource bridge, then writes a compact 512 KiB snapshot. No
 * method is bound into QuickJs and no signed Provider gains a permission.
 *
 * This is bounded acquisition, not incremental HTTP streaming. Author/edition
 * identity and Reader permissions must be checked separately by the canonical
 * engine. Single active snapshot/provider; concurrent requests are serialized
 * inside this process. Multi-process locking is NOT implemented.
 */
internal class MangaAllFutureHostChapterSnapshot(
    private val owner: ProviderResourceOwner,
    private val http: ProviderHttpHostService,
    private val resources: ProviderResourceStore,
    private val storage: FileProviderStorageHostService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val generation: () -> String = {
        UUID.randomUUID().toString().replace("-", "").take(16)
    },
) {
    init {
        require(owner.providerId == "app.tsuzuki.mangaall") {
            "Future chapter snapshots are source-scoped to MangaALL"
        }
    }

    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    suspend fun start(workId: String, language: String): MangaAllFutureChapterPage = lock.withLock {
        val id = requireScope(workId, language)
        val url = "https://mangadot.net/api/manga/" + id + "/chapters/list?lang=" + language
        // getResource uses the normal origin/DNS/redirect/cookie policy; its
        // bytes never cross the 192 KiB HTTP text or 256 KiB JS IPC boundary.
        val handle = http.getResource(url)
        val bytes = try {
            resources.read(owner, handle, ProviderResourceKind.HTML)
        } finally {
            resources.release(owner, handle)
        }
        if (bytes.size > MAX_SOURCE_BYTES) fail("SOURCE_SNAPSHOT_TOO_LARGE")
        val sourceText = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
            fail("INVALID_SOURCE_JSON_ARRAY")
        }
        val source = try {
            json.parseToJsonElement(sourceText) as? JsonArray
                ?: fail("INVALID_SOURCE_JSON_ARRAY")
        } catch (_: Exception) {
            fail("INVALID_SOURCE_JSON_ARRAY")
        }
        if (source.size > MAX_ROWS) fail("SOURCE_TOO_MANY_ROWS")
        val tuples = compact(source, language)
        val nonce = generation()
        if (!NONCE.matches(nonce)) fail("INVALID_SNAPSHOT_GENERATION")
        val now = clock()
        if (now < 0 || now > Long.MAX_VALUE - TTL_MS) fail("INVALID_SNAPSHOT_CLOCK")

        val parts = makeParts(tuples)
        val ranges = parts.map { it.size }
        val index = JsonObject(
            mapOf(
                "v" to JsonPrimitive(1),
                "workId" to JsonPrimitive(workId),
                "language" to JsonPrimitive(language),
                "generation" to JsonPrimitive(nonce),
                "expiresAt" to JsonPrimitive(now + TTL_MS),
                "count" to JsonPrimitive(tuples.size),
                "ranges" to JsonArray(ranges.map(::JsonPrimitive)),
            ),
        ).toString()
        val values = parts.map { p ->
            JsonObject(
                mapOf(
                    "generation" to JsonPrimitive(nonce),
                    "rows" to JsonArray(p),
                ),
            ).toString()
        }
        val allValues = values + index
        if (allValues.any { it.length > MAX_STORAGE_VALUE || byteSize(it) > MAX_STORAGE_VALUE } ||
            allValues.sumOf(String::length) > MAX_STORAGE_TOTAL ||
            allValues.sumOf(::byteSize) > MAX_STORAGE_TOTAL
        ) {
            fail("SNAPSHOT_EXCEEDS_PROVIDER_STORAGE_QUOTA")
        }

        // The typed Host-private storage operation checks the full provider
        // state (including unrelated keys) and atomically replaces all reserved
        // snapshot keys. Rejected new data cannot evict an older valid snapshot.
        try {
            storage.replaceFutureMangaAllChapterSnapshot(index, values)
        } catch (_: ProviderHostServiceException) {
            fail("SNAPSHOT_STORAGE_WRITE_FAILED")
        }
        page(workId, language, 0, readIndex())
    }

    suspend fun next(
        workId: String,
        language: String,
        cursor: String,
    ): MangaAllFutureChapterPage = lock.withLock {
        requireScope(workId, language)
        val index = readIndex()
        if (index.workId != workId || index.language != language) {
            fail("STALE_OR_FOREIGN_CHAPTER_CURSOR")
        }
        if (clock() > index.expiresAt) fail("EXPIRED_CHAPTER_SNAPSHOT")
        val match = CURSOR.matchEntire(cursor)
            ?: fail("STALE_OR_FOREIGN_CHAPTER_CURSOR")
        val position = match.groupValues[4].toIntOrNull()
            ?: fail("INVALID_CHAPTER_CURSOR")
        if (match.groupValues[1] != workId.removePrefix("mangadot:") ||
            match.groupValues[2] != language ||
            match.groupValues[3] != index.generation
        ) {
            fail("STALE_OR_FOREIGN_CHAPTER_CURSOR")
        }
        if (position !in 0 until index.count) fail("CHAPTER_CURSOR_OUT_OF_BOUNDS")
        page(workId, language, position, index)
    }

    private suspend fun page(
        workId: String,
        language: String,
        start: Int,
        index: Index,
    ): MangaAllFutureChapterPage {
        val items = mutableListOf<JsonObject>()
        val cached = mutableMapOf<Int, JsonArray>()
        var position = start
        var nextCursor: String? = null
        while (position < index.count && items.size < MAX_PAGE_ROWS) {
            val packed = readTuple(index, position, cached)
            val normalized = expand(packed, language)
            val following = position + 1
            val candidateCursor = if (following < index.count) {
                "m1:" + workId.removePrefix("mangadot:") + ":" + language + ":" +
                    index.generation + ":" + following
            } else {
                null
            }
            val payload = pageJson(items + normalized, candidateCursor)
            if (payload.length > MAX_OUTPUT || byteSize(payload) > MAX_OUTPUT) {
                if (items.isEmpty()) fail("CHAPTER_ROW_EXCEEDS_HOST_RESULT")
                break
            }
            items += normalized
            position = following
            nextCursor = candidateCursor
        }
        if (position < index.count && nextCursor == null) {
            nextCursor = "m1:" + workId.removePrefix("mangadot:") + ":" + language + ":" +
                index.generation + ":" + position
        }
        if (index.count > 0 && items.isEmpty()) fail("NO_CHAPTER_CURSOR_PROGRESS")
        return MangaAllFutureChapterPage(items, nextCursor)
    }

    private fun pageJson(items: List<JsonObject>, cursor: String?): String =
        JsonObject(
            mapOf(
                "items" to JsonArray(items),
                "nextCursor" to (cursor?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ).toString()

    private suspend fun readTuple(
        index: Index,
        offset: Int,
        cached: MutableMap<Int, JsonArray>,
    ): JsonArray {
        var base = 0
        for ((part, count) in index.ranges.withIndex()) {
            if (offset < base + count) {
                val rows = cached.getOrPut(part) {
                    // Cannot call a suspend function in getOrPut: this cache
                    // is populated immediately below when absent.
                    JsonArray(emptyList())
                }
                val actual = if (rows.isEmpty()) {
                    val raw = storage.get(PART_PREFIX + part)
                        ?: fail("MISSING_SNAPSHOT_PART")
                    if (raw.length > MAX_STORAGE_VALUE || byteSize(raw) > MAX_STORAGE_VALUE) {
                        fail("CORRUPT_SNAPSHOT_PART")
                    }
                    val obj = parseObject(raw, "CORRUPT_SNAPSHOT_PART")
                    if (obj["generation"]?.primitiveText() != index.generation) {
                        fail("STALE_SNAPSHOT_PART")
                    }
                    val loaded = obj["rows"] as? JsonArray
                        ?: fail("CORRUPT_SNAPSHOT_PART")
                    if (loaded.size != count) fail("CORRUPT_SNAPSHOT_PART")
                    cached[part] = loaded
                    loaded
                } else {
                    rows
                }
                return actual[offset - base] as? JsonArray
                    ?: fail("CORRUPT_SNAPSHOT_TUPLE")
            }
            base += count
        }
        fail("CORRUPT_SNAPSHOT_INDEX")
    }

    private suspend fun readIndex(): Index {
        val raw = storage.get(INDEX_KEY) ?: fail("MISSING_CHAPTER_SNAPSHOT")
        if (raw.length > MAX_STORAGE_VALUE || byteSize(raw) > MAX_STORAGE_VALUE) {
            fail("CORRUPT_SNAPSHOT_INDEX")
        }
        val obj = parseObject(raw, "CORRUPT_SNAPSHOT_INDEX")
        if (obj["v"]?.primitiveLong() != 1L) fail("CORRUPT_SNAPSHOT_INDEX")
        val workId = obj["workId"]?.primitiveText() ?: fail("CORRUPT_SNAPSHOT_INDEX")
        val language = obj["language"]?.primitiveText() ?: fail("CORRUPT_SNAPSHOT_INDEX")
        requireScope(workId, language)
        val nonce = obj["generation"]?.primitiveText() ?: fail("CORRUPT_SNAPSHOT_INDEX")
        if (!NONCE.matches(nonce)) fail("CORRUPT_SNAPSHOT_INDEX")
        val expiresAt = obj["expiresAt"]?.primitiveLong() ?: fail("CORRUPT_SNAPSHOT_INDEX")
        val countLong = obj["count"]?.primitiveLong() ?: fail("CORRUPT_SNAPSHOT_INDEX")
        val parts = obj["ranges"] as? JsonArray ?: fail("CORRUPT_SNAPSHOT_INDEX")
        if (expiresAt < 0 || countLong !in 0L..MAX_ROWS.toLong() || parts.size > MAX_PARTS ||
            (countLong > 0L && parts.isEmpty())
        ) {
            fail("CORRUPT_SNAPSHOT_INDEX")
        }
        val count = countLong.toInt()
        val ranges = parts.map { element ->
            val number = element.primitiveLong() ?: fail("CORRUPT_SNAPSHOT_INDEX")
            if (number !in 1L..MAX_ROWS.toLong()) fail("CORRUPT_SNAPSHOT_INDEX")
            number.toInt()
        }
        if (ranges.sum() != count) fail("CORRUPT_SNAPSHOT_INDEX")
        return Index(workId, language, nonce, expiresAt, count, ranges)
    }

    private fun compact(rows: JsonArray, language: String): List<JsonArray> {
        val seen = HashSet<String>()
        var rejectedFacet = false
        val result = mutableListOf<JsonArray>()
        for (entry in rows) {
            val row = entry as? JsonObject ?: continue
            val langValue = row["language"]
            val lang = when {
                langValue == null || langValue == JsonNull -> null
                langValue is JsonPrimitive && langValue.isString -> langValue.content.lowercase()
                else -> {
                    rejectedFacet = true
                    continue
                }
            }
            if (lang != null && lang != language) {
                rejectedFacet = true
                continue
            }
            val id = row["id"].sourceId() ?: continue
            val number = row["chapter_number"].sourceNumber() ?: continue
            val kind = if (row["source"] == null || row["source"] == JsonNull ||
                row["source"]?.primitiveText() == "user"
            ) {
                0
            } else {
                1
            }
            // Full source chapter identity includes the upload/chapter kind:
            // one numeric upstream ID can represent two separate chapters.
            if (!seen.add("$kind:$id")) continue
            val title = text(row["chapter_title"], 3900)
            val groups = row["groups"] as? JsonArray
            val firstGroup = groups?.firstOrNull() as? JsonObject
            val scanlator = text(firstGroup?.get("name"), 1024)
                ?: text(row["group_name"], 1024)
                ?: text(row["scanlator_name"], 1024)
            val date = row["date_added"]?.primitiveText()?.let { raw ->
                runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            }
            result += JsonArray(
                listOf(
                    JsonPrimitive(id),
                    JsonPrimitive(number),
                    JsonPrimitive(kind),
                    title?.let(::JsonPrimitive) ?: JsonNull,
                    lang?.let(::JsonPrimitive) ?: JsonNull,
                    scanlator?.let(::JsonPrimitive) ?: JsonNull,
                    date?.let(::JsonPrimitive) ?: JsonNull,
                ),
            )
        }
        if (rows.isNotEmpty() && result.isEmpty() && rejectedFacet) {
            fail("INCONCLUSIVE_SOURCE_LANGUAGE")
        }
        return result
    }

    private fun expand(tuple: JsonArray, language: String): JsonObject {
        if (tuple.size != 7) fail("CORRUPT_SNAPSHOT_TUPLE")
        val id = tuple[0].primitiveText() ?: fail("CORRUPT_SNAPSHOT_TUPLE")
        val number = tuple[1].primitiveLongOrDouble() ?: fail("CORRUPT_SNAPSHOT_TUPLE")
        val source = tuple[2].primitiveLong() ?: fail("CORRUPT_SNAPSHOT_TUPLE")
        val title = tuple[3].nullableString(3900)
        val lang = tuple[4].nullableString(16)
        val group = tuple[5].nullableString(1024)
        val date = if (tuple[6] == JsonNull) {
            null
        } else {
            tuple[6].primitiveLong()
                ?: fail("CORRUPT_SNAPSHOT_TUPLE")
        }
        if (!ID.matches(id) || source !in 0L..1L || number < 0.0 ||
            !number.isFinite() || (lang != null && lang != language)
        ) {
            fail("CORRUPT_SNAPSHOT_TUPLE")
        }
        val numberText = if (number == number.toLong().toDouble()) {
            number.toLong().toString()
        } else {
            number.toString()
        }
        val chapterId = "mangadot:" + (if (source == 0L) "uploads:" else "chapters:") + id
        return JsonObject(
            mapOf(
                "providerChapterId" to JsonPrimitive(chapterId),
                "rawLabel" to JsonPrimitive("Ch. " + numberText + (title?.let { " " + it } ?: "")),
                "rawNumber" to JsonPrimitive(number),
                "title" to (title?.let(::JsonPrimitive) ?: JsonNull),
                "language" to (lang?.let(::JsonPrimitive) ?: JsonNull),
                "scanlationGroup" to (group?.let(::JsonPrimitive) ?: JsonNull),
                "releaseDateMillis" to (date?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
    }

    private fun makeParts(rows: List<JsonArray>): List<List<JsonArray>> {
        val parts = mutableListOf<List<JsonArray>>()
        var current = mutableListOf<JsonArray>()
        var bodyBytes = 0
        for (row in rows) {
            val proposed = current + row
            val serialized = JsonArray(proposed).toString()
            if (serialized.length > MAX_PART || byteSize(serialized) > MAX_PART) {
                if (current.isEmpty()) fail("CHAPTER_ROW_EXCEEDS_STORAGE_PAGE")
                parts += current
                bodyBytes += byteSize(JsonArray(current).toString())
                current = mutableListOf(row)
                val single = JsonArray(current).toString()
                if (single.length > MAX_PART || byteSize(single) > MAX_PART) {
                    fail("CHAPTER_ROW_EXCEEDS_STORAGE_PAGE")
                }
            } else {
                current.add(row)
            }
        }
        if (current.isNotEmpty()) {
            parts += current
            bodyBytes += byteSize(JsonArray(current).toString())
        }
        if (parts.size > MAX_PARTS || bodyBytes > MAX_SNAPSHOT) {
            fail("SNAPSHOT_EXCEEDS_PROVIDER_STORAGE_QUOTA")
        }
        return parts
    }

    private fun requireScope(workId: String, language: String): String {
        val match = WORK.matchEntire(workId) ?: fail("INVALID_MANGAALL_WORK_SCOPE")
        if (language != "en" && language != "pt-br") fail("INVALID_MANGAALL_FACET")
        return match.groupValues[1]
    }

    private fun parseObject(raw: String, reason: String): JsonObject =
        try {
            json.parseToJsonElement(raw) as? JsonObject ?: fail(reason)
        } catch (_: Exception) {
            fail(reason)
        }

    private fun JsonElement?.primitiveText(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.primitiveLong(): Long? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonElement?.primitiveLongOrDouble(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonElement?.sourceId(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        val raw = if (primitive.isString) {
            primitive.content
        } else {
            primitive.longOrNull?.takeIf { it > 0 }?.toString() ?: return null
        }
        return raw.takeIf(ID::matches)
    }

    private fun JsonElement?.sourceNumber(): Double? {
        val primitive = this as? JsonPrimitive ?: return null
        val raw = primitive.content
        if (!NUMBER.matches(raw)) return null
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    private fun text(value: JsonElement?, max: Int): String? =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.take(max)?.takeIf(String::isNotEmpty)

    private fun JsonElement.nullableString(max: Int): String? {
        if (this == JsonNull) return null
        val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail("CORRUPT_SNAPSHOT_TUPLE")
        if (text.length > max) fail("CORRUPT_SNAPSHOT_TUPLE")
        return text
    }

    private fun byteSize(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private data class Index(
        val workId: String,
        val language: String,
        val generation: String,
        val expiresAt: Long,
        val count: Int,
        val ranges: List<Int>,
    )

    private companion object {
        val lock = Mutex()
        val WORK = Regex("mangadot:([1-9][0-9]{0,15})")
        val ID = Regex("[1-9][0-9]{0,15}")
        val NUMBER = Regex("[0-9]{1,6}(?:\\.[0-9]{1,3})?")
        val NONCE = Regex("[a-f0-9]{16}")
        val CURSOR = Regex("m1:([1-9][0-9]{0,15}):(en|pt-br):([a-f0-9]{16}):([0-9]{1,5})")
        const val INDEX_KEY = "mangaall_private_chapters_index"
        const val PART_PREFIX = "mangaall_private_chapters_part_"
        const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
        const val MAX_ROWS = 16000
        const val MAX_PART = 48 * 1024
        const val MAX_PARTS = 16
        const val MAX_SNAPSHOT = 440 * 1024
        const val MAX_STORAGE_VALUE = 64 * 1024
        const val MAX_STORAGE_TOTAL = 512 * 1024
        const val MAX_PAGE_ROWS = 256
        const val MAX_OUTPUT = 48 * 1024
        const val TTL_MS = 5 * 60 * 1000L

        fun fail(reason: String): Nothing = throw ProviderHostServiceException(reason)
    }
}

internal data class MangaAllFutureChapterPage(
    val items: List<JsonObject>,
    val nextCursor: String?,
)
