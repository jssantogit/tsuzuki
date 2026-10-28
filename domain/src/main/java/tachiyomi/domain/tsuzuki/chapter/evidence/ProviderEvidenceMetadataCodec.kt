package tachiyomi.domain.tsuzuki.chapter.evidence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Optional, untrusted Provider release attributes. These are audit hints,
 * never canonical edition identity, publication authority or Reader access.
 */
data class ProviderEvidenceMetadata(
    val language: String? = null,
    val scanlationGroup: String? = null,
    val releaseDateMillis: Long? = null,
) {
    init {
        require(language == null || (language.isNotBlank() && language.length <= 32)) {
            "Invalid Provider observation language"
        }
        require(scanlationGroup == null || scanlationGroup.length <= 4096) {
            "Invalid Provider scanlation group"
        }
        require(releaseDateMillis == null || releaseDateMillis >= 0L) {
            "Invalid Provider release timestamp"
        }
    }

    val hasValues: Boolean
        get() = language != null || scanlationGroup != null || releaseDateMillis != null
}

/**
 * Versioned, bounded bytes stored *only* for recognized Provider observations.
 * An unknown BLOB is always opaque: never decoded, modified, or exported.
 * The portable backup explicitly carries the three safe fields, not this BLOB.
 */
object ProviderEvidenceMetadataCodec {
    private val signature = "TSUZUKI:PROVIDER:OBS:".encodeToByteArray()
    private const val VERSION = 1
    private const val MAX_BYTES = 16_384

    fun encode(value: ProviderEvidenceMetadata): ByteArray {
        if (!value.hasValues) return byteArrayOf()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(signature)
            writer.writeByte(VERSION)
            writer.writeBoolean(value.language != null)
            value.language?.let(writer::writeUTF)
            writer.writeBoolean(value.scanlationGroup != null)
            value.scanlationGroup?.let(writer::writeUTF)
            writer.writeBoolean(value.releaseDateMillis != null)
            value.releaseDateMillis?.let(writer::writeLong)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_BYTES) { "Provider metadata envelope exceeded byte budget" }
        }
    }

    fun decode(raw: ByteArray): ProviderEvidenceMetadata? {
        if (raw.size <= signature.size + 1 || raw.size > MAX_BYTES) return null
        if (!raw.copyOfRange(0, signature.size).contentEquals(signature)) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(raw)).use { reader ->
                val prefix = ByteArray(signature.size)
                reader.readFully(prefix)
                require(reader.readUnsignedByte() == VERSION) { "Unsupported Provider metadata version" }
                val language = if (reader.readBoolean()) reader.readUTF() else null
                val group = if (reader.readBoolean()) reader.readUTF() else null
                val date = if (reader.readBoolean()) reader.readLong() else null
                require(reader.available() == 0) { "Unexpected Provider metadata trailing bytes" }
                ProviderEvidenceMetadata(language, group, date)
                    .takeIf { it.hasValues && encode(it).contentEquals(raw) }
            }
        }.getOrNull()
    }

    fun isPortable(raw: ByteArray): Boolean = raw.isEmpty() || decode(raw) != null
}
