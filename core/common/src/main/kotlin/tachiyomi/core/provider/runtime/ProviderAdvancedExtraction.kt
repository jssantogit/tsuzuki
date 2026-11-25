package tachiyomi.core.provider.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Base64

/**
 * Pure, bounded extraction of inline data already available to a script.
 * No I/O, no resource ownership changes, no privileges and no schema inference.
 */
internal object ProviderAdvancedExtraction {
    private const val MAX_INPUT = 192 * 1024
    private const val MAX_OUTPUT = 48 * 1024
    private const val MAX_FIELDS = 512
    private const val MAX_DEPTH = 64

    fun jsonPointer(document: String, pointer: String, maxInput: Int = MAX_INPUT): String =
        bounded(resolveJson(document, pointer, maxInput).toString())

    private fun resolveJson(document: String, pointer: String, maxInput: Int): JsonElement {
        checkInput(document, maxInput)
        require(pointer.length <= 2048 && (pointer.isEmpty() || pointer.startsWith('/')))
        checkJsonDepth(document)
        var value = Json.parseToJsonElement(document)
        if (pointer.isNotEmpty()) {
            val tokens = pointer.substring(1).split('/')
            require(tokens.size <= MAX_DEPTH)
            for (encoded in tokens) {
                require(!Regex("~(?![01])").containsMatchIn(encoded))
                val token = encoded.replace("~1", "/").replace("~0", "~")
                value = when (val current = value) {
                    is JsonObject -> current[token]
                    is JsonArray -> {
                        val index = token.takeIf { Regex("0|[1-9][0-9]*").matches(it) }?.toIntOrNull()
                        index?.let { current.getOrNull(it) }
                    }
                    else -> null
                } ?: throw ProviderHostServiceException("JSON pointer does not resolve")
            }
        }
        return value
    }

    fun jsonArrayPage(document: String, pointer: String, offset: Int, limit: Int): String {
        val array = resolveJson(document, pointer, 2 * 1024 * 1024) as? JsonArray
            ?: throw ProviderHostServiceException("JSON pointer does not resolve to an array")
        require(offset in 0..array.size && limit in 1..256)
        val items = StringBuilder()
        var next = offset
        var bytes = 0
        while (next < array.size && next - offset < limit) {
            val item = array[next].toString()
            val itemBytes = item.encodeToByteArray().size + if (next == offset) 0 else 1
            if (bytes + itemBytes > MAX_OUTPUT - 128) break
            if (next != offset) items.append(',')
            items.append(item)
            bytes += itemBytes
            next++
        }
        require(next > offset || offset == array.size) { "One JSON item exceeds the result limit" }
        val nextOffset = if (next == array.size) "null" else next.toString()
        return bounded("{\"items\":[$items],\"nextOffset\":$nextOffset,\"total\":${array.size}}")
    }

    /**
     * Schema-neutral protobuf wire inspection; field numbers and wire types are
     * retained, repeated fields stay in order, and uint64 is represented exactly
     * as decimal text. Length-delimited data is opaque, not guessed as a message.
     * Only standard wire types 0, 1, 2 and 5 are supported.
     */
    fun protobufFields(base64: String): String {
        checkInput(base64)
        return protobufBytes(Base64.getDecoder().decode(base64))
    }

    fun protobufBytes(bytes: ByteArray): String {
        require(bytes.size <= 2 * 1024 * 1024)
        val reader = WireReader(bytes)
        val output = StringBuilder("[")
        var count = 0
        while (reader.remaining > 0) {
            require(++count <= MAX_FIELDS)
            val tag = reader.varint()
            val number = tag shr 3
            require(number in 1uL..536870911uL)
            val wire = (tag and 7uL).toInt()
            val value = when (wire) {
                0 -> reader.varint().toString()
                1 -> reader.fixed(8).toString()
                2 -> {
                    val length = reader.varint()
                    require(length <= reader.remaining.toULong() && length <= (MAX_OUTPUT / 2).toULong())
                    Base64.getEncoder().encodeToString(reader.take(length.toInt()))
                }
                5 -> reader.fixed(4).toString()
                else -> throw ProviderHostServiceException("Unsupported protobuf wire type")
            }
            if (count > 1) output.append(',')
            output.append("{\"field\":").append(number).append(",\"wire\":").append(wire)
                .append(",\"value\":\"").append(value).append("\"}")
            require(output.length < MAX_OUTPUT)
        }
        return bounded(output.append(']').toString())
    }

    /**
     * Extract text or a named attribute from bounded inline HTML/XML.
     * Restrict the selector subset to avoid regex and nested predicate abuse.
     * An empty result is distinct from a malformed request.
     */
    fun queryDocument(
        document: String,
        format: String,
        selector: String,
        attribute: String?,
        maxInput: Int = MAX_INPUT,
    ): String {
        checkInput(document, maxInput)
        require(format == "html" || format == "xml")
        require(selector.isNotBlank() && selector.length <= 256)
        require(':' !in selector && '(' !in selector && ')' !in selector && '\\' !in selector)
        require("~=" !in selector) // Jsoup interprets this operator as a regular expression.
        require(attribute == null || Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,63}").matches(attribute))
        val parsed = if (format == "xml") {
            // Jsoup parses XML without external entity resolution or network access.
            Jsoup.parse(document, "", Parser.xmlParser())
        } else {
            Jsoup.parse(document)
        }
        val nodes = parsed.select(selector)
        require(nodes.size <= 128)
        val result = StringBuilder("[")
        for ((index, node) in nodes.withIndex()) {
            if (index > 0) result.append(',')
            val value = if (attribute == null) {
                kotlinx.serialization.json.JsonPrimitive(node.text()).toString()
            } else if (node.hasAttr(attribute)) {
                kotlinx.serialization.json.JsonPrimitive(node.attr(attribute)).toString()
            } else {
                "null"
            }
            result.append(value)
            require(result.length < MAX_OUTPUT)
        }
        return bounded(result.append(']').toString())
    }

    private fun checkInput(value: String, maxInput: Int = MAX_INPUT) {
        require(value.length <= maxInput && value.encodeToByteArray().size <= maxInput)
    }

    private fun bounded(value: String): String {
        require(value.length <= MAX_OUTPUT && value.encodeToByteArray().size <= MAX_OUTPUT)
        return value
    }

    private fun checkJsonDepth(value: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in value) {
            if (quoted) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> require(++depth <= MAX_DEPTH)
                    '}', ']' -> require(--depth >= 0)
                }
            }
        }
        require(depth == 0 && !quoted && !escaped)
    }

    private class WireReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining: Int get() = bytes.size - position

        private fun next(): Int {
            require(remaining > 0)
            return bytes[position++].toInt() and 255
        }

        fun varint(): ULong {
            var result = 0uL
            repeat(10) { index ->
                val byte = next()
                require(index != 9 || byte <= 1)
                result = result or ((byte and 127).toULong() shl (index * 7))
                if (byte and 128 == 0) return result
            }
            throw ProviderHostServiceException("Invalid protobuf varint")
        }

        fun fixed(count: Int): ULong {
            var result = 0uL
            repeat(count) { index -> result = result or (next().toULong() shl (index * 8)) }
            return result
        }

        fun take(count: Int): ByteArray {
            require(count in 0..remaining)
            return bytes.copyOfRange(position, position + count).also { position += count }
        }
    }
}
