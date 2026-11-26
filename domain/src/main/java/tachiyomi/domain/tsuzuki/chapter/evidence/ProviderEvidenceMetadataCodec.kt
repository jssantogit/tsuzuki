package tachiyomi.domain.tsuzuki.chapter.evidence

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Optional, untrusted Provider release attributes and a separate local
 * work attestation. Neither grants editorial authority or Reader access.
 */
data class ProviderEvidenceMetadata(
    val language: String? = null,
    val scanlationGroup: String? = null,
    val releaseDateMillis: Long? = null,
    /** Local Host attestation, never populated from script output or portable backup. */
    val catalogWorkFingerprint: String? = null,
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
        if (catalogWorkFingerprint != null) {
            require(CATALOG_WORK_FINGERPRINT.matches(catalogWorkFingerprint)) {
                "Invalid local catalog work attestation"
            }
        }
    }

    val hasValues: Boolean
        get() = language != null || scanlationGroup != null ||
            releaseDateMillis != null || catalogWorkFingerprint != null

    private companion object {
        val CATALOG_WORK_FINGERPRINT = Regex("catalog-work-v1:[0-9a-f]{64}")
    }
}

/**
 * Versioned, bounded bytes stored *only* for recognized Provider observations.
 * An unknown BLOB is always opaque: never decoded, modified, or exported.
 * The portable backup explicitly carries only the three release fields, not
 * this BLOB or any local work attestation. V1 bytes are unchanged.
 */
object ProviderEvidenceMetadataCodec {
    private val signature = "TSUZUKI:PROVIDER:OBS:".encodeToByteArray()
    private const val RELEASE_VERSION = 1
    private const val LOCAL_ATTESTATION_VERSION = 2
    private const val MAX_BYTES = 16_384

    fun encode(value: ProviderEvidenceMetadata): ByteArray {
        if (!value.hasValues) return byteArrayOf()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { writer ->
            writer.write(signature)
            writer.writeByte(if (value.catalogWorkFingerprint == null) RELEASE_VERSION else LOCAL_ATTESTATION_VERSION)
            writer.writeBoolean(value.language != null)
            value.language?.let(writer::writeUTF)
            writer.writeBoolean(value.scanlationGroup != null)
            value.scanlationGroup?.let(writer::writeUTF)
            writer.writeBoolean(value.releaseDateMillis != null)
            value.releaseDateMillis?.let(writer::writeLong)
            value.catalogWorkFingerprint?.let(writer::writeUTF)
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
                val version = reader.readUnsignedByte()
                require(version == RELEASE_VERSION || version == LOCAL_ATTESTATION_VERSION) {
                    "Unsupported Provider metadata version"
                }
                val language = if (reader.readBoolean()) reader.readUTF() else null
                val group = if (reader.readBoolean()) reader.readUTF() else null
                val date = if (reader.readBoolean()) reader.readLong() else null
                val proof = if (version == LOCAL_ATTESTATION_VERSION) reader.readUTF() else null
                require(reader.available() == 0) { "Unexpected Provider metadata trailing bytes" }
                ProviderEvidenceMetadata(language, group, date, proof)
                    .takeIf { it.hasValues && encode(it).contentEquals(raw) }
            }
        }.getOrNull()
    }

    fun isPortable(raw: ByteArray): Boolean = raw.isEmpty() || decode(raw) != null
}
