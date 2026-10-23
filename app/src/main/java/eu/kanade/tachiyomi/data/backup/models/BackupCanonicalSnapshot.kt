package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoNumber
import java.security.MessageDigest

/**
 * Optional, versioned extension to the legacy Mihon backup wire format.
 *
 * This is an integrity envelope only. Canonical backup is NOT complete until
 * every necessary section has a verified writer and a tested importer.
 * A legacy archive with no snapshot must remain decodable.
 */
@Serializable
data class BackupCanonicalSnapshot(
    @ProtoNumber(1) val formatVersion: Int = FORMAT_VERSION,
    @ProtoNumber(2) val sections: List<BackupCanonicalSection> = emptyList(),
) {
    fun validate() {
        require(formatVersion == FORMAT_VERSION) { "Unsupported Tsuzuki backup version" }
        require(sections.distinctBy { it.key }.size == sections.size) {
            "Duplicate canonical backup section"
        }
        require(sections.size <= MAX_SECTIONS) { "Too many canonical backup sections" }
        sections.forEach(BackupCanonicalSection::validate)
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val MAX_SECTIONS = 64
    }
}

/**
 * Stable section IDs are versioned individually to allow staged migrations.
 * Checksums detect damaged archives but do not authenticate the sender.
 */
@Serializable
data class BackupCanonicalSection(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val payloadJson: String,
    @ProtoNumber(3) val sha256: String,
) {
    fun validate() {
        require(key.matches(Regex("[a-z0-9._-]{1,96}"))) {
            "Invalid canonical backup section ID"
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Malformed canonical section hash"
        }
        require(payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_SECTION_BYTES) {
            "Canonical backup section exceeds size limit"
        }
        require(sha256 == digest(payloadJson)) {
            "Canonical backup section checksum mismatch"
        }
        Json.parseToJsonElement(payloadJson)
    }

    companion object {
        private const val MAX_SECTION_BYTES = 128 * 1024 * 1024

        fun fromJson(key: String, json: String): BackupCanonicalSection =
            BackupCanonicalSection(
                key = key,
                payloadJson = json,
                sha256 = digest(json),
            ).also(BackupCanonicalSection::validate)

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
