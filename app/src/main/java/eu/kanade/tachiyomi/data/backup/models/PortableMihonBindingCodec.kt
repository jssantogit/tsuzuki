package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayload
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Portable identity of a legacy Mihon content binding. Only the known v1
 * payload may cross the backup boundary; local Mihon manga IDs never do.
 *
 * These bindings are deliberately restored as UNAVAILABLE, requiring a
 * new on-device materialization before they may resolve chapters.
 */
@Serializable
data class BackupCoreContentBinding(
    val id: String,
    val canonicalTitleId: String,
    val addonId: String,
    val providerTitleKey: String,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val matchConfidence: Double,
    val verifiedByUser: Boolean,
    val sourceAvailability: String,
    val createdAt: Long,
    val updatedAt: Long,
    val codecVersion: Int = PortableMihonBindingCodec.VERSION,
)

internal object PortableMihonBindingCodec {

    const val VERSION = 1
    private const val MAX_SOURCE_URL_LENGTH = 4096
    private const val MAX_LANGUAGE_LENGTH = 64

    fun export(
        id: String,
        canonicalTitleId: String,
        addonId: String,
        providerTitleKey: String,
        matchConfidence: Double,
        verifiedByUser: Boolean,
        availability: String,
        runtimePayload: ByteArray,
        createdAt: Long,
        updatedAt: Long,
    ): BackupCoreContentBinding {
        val decoded = MihonContentBindingPayloadCodec.decode(runtimePayload)
        // A -1 placeholder is only valid for an already quarantined binding
        // from a prior restore, not for an allegedly live source.
        require(decoded.mihonMangaId > 0L || (decoded.mihonMangaId == -1L && availability == "UNAVAILABLE")) {
            "Mihon runtime binding must be materialized or safely quarantined"
        }
        return BackupCoreContentBinding(
            id = id,
            canonicalTitleId = canonicalTitleId,
            addonId = addonId,
            providerTitleKey = providerTitleKey,
            sourceId = decoded.sourceId,
            sourceUrl = decoded.sourceUrl,
            language = decoded.language,
            matchConfidence = matchConfidence,
            verifiedByUser = verifiedByUser,
            sourceAvailability = availability,
            createdAt = createdAt,
            updatedAt = updatedAt,
        ).also(::validate)
    }

    fun validate(binding: BackupCoreContentBinding) {
        require(binding.codecVersion == VERSION) { "Unsupported portable content binding version" }
        require(binding.id.isNotBlank() && binding.canonicalTitleId.isNotBlank() && binding.addonId.isNotBlank()) {
            "Invalid portable content binding identity"
        }
        // The binary v1 payload has no discriminator beyond its version.
        // Do not reinterpret an arbitrary SCRIPT provider's similarly shaped
        // bytes as a legacy Mihon extension binding.
        require(
            binding.addonId.startsWith("eu.kanade.tachiyomi.extension.") &&
                binding.addonId.substringAfterLast('.').isNotBlank(),
        ) { "Unrecognized legacy Mihon extension identity" }
        require(binding.providerTitleKey == "${binding.sourceId}:${binding.sourceUrl}") {
            "Content binding source identity does not match its provider title key"
        }
        require(binding.sourceAvailability in setOf("AVAILABLE", "UNAVAILABLE", "UNKNOWN")) {
            "Unknown content binding availability"
        }
        require(binding.matchConfidence.isFinite() && binding.matchConfidence in 0.0..1.0) {
            "Invalid content binding confidence"
        }
        require(binding.createdAt >= 0 && binding.updatedAt >= binding.createdAt) {
            "Invalid content binding timestamps"
        }
        require(binding.language.length <= MAX_LANGUAGE_LENGTH && binding.language.none { it.isISOControl() }) {
            "Nonportable content binding language"
        }
        // The runtime URL belongs to third-party code. Only accept a bounded,
        // queryless URL with no userinfo/fragment or executable scheme. No raw
        // runtime bytes or potentially credential-bearing query may be saved.
        val sourceUrl = binding.sourceUrl
        require(sourceUrl.isNotBlank() && sourceUrl.length <= MAX_SOURCE_URL_LENGTH) {
            "Invalid content binding URL size"
        }
        require(sourceUrl.none { it.isISOControl() } && '@' !in sourceUrl) {
            "Unsafe content binding URL characters"
        }
        val uri = try {
            URI(sourceUrl)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed content binding source URL", error)
        }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
            "Content binding URL may contain credentials or temporary tokens"
        }
        if (uri.isAbsolute) {
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "Only credential-free HTTPS absolute source URLs are portable"
            }
        } else {
            require(!sourceUrl.startsWith("//") && "://" !in sourceUrl) {
                "Ambiguous relative content binding URL"
            }
        }
    }

    fun restoreDormant(binding: BackupCoreContentBinding): ByteArray {
        validate(binding)
        return MihonContentBindingPayloadCodec.encode(
            MihonContentBindingPayload(
                sourceId = binding.sourceId,
                // Invalid on every installation until explicitly rematerialized.
                mihonMangaId = -1L,
                sourceUrl = binding.sourceUrl,
                language = binding.language,
            ),
        )
    }
}
