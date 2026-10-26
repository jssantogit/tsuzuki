package tachiyomi.domain.tsuzuki.chapter.inventory

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A review of locally stored selected-source evidence; never publisher authority. */
sealed interface UserAdoptionPreview {
    data class Blocked(val reason: UserAdoptionBlockReason) : UserAdoptionPreview

    data class Ready(
        val scope: InventoryScope,
        val units: List<UserAdoptionPreviewUnit>,
        /** Digest of the exact stored snapshot and ordered selection the user reviewed. */
        val fingerprint: String,
    ) : UserAdoptionPreview
}

enum class UserAdoptionBlockReason {
    EDITION_OR_LANGUAGE_MISSING,
    NOT_AN_EXPLICIT_INDIVIDUAL_CANDIDATE,
    SOURCE_SNAPSHOT_MISMATCH,
    UNSUPPORTED_NUMBERING,
    DUPLICATE_OR_COLLIDING_UNIT,
    CANONICAL_IDENTITY_COLLISION,
}

data class UserAdoptionPreviewUnit(
    val sourceRecordId: String,
    val label: String,
    val identity: CanonicalChapterIdentity,
    val volume: Int?,
    val provenance: InventoryCandidateProvenance,
    val observedAt: Long,
    val derivation: InventoryDerivation,
)

/**
 * No network, file access, authority escalation or write. A later commit MUST
 * rerun this exact preview against persisted claims inside the same transaction
 * as chapter + adoption-receipt creation. Do not trust a caller-provided preview.
 */
class PreviewUserAdoptedInventory {
    companion object {
        const val MAX_SELECTION = 128
        private val exactNumber = Regex("""^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,9})?$""")
        private val namedNumber = Regex(
            """(?i)^(?:chapter|ch\.?|episode|ep\.?)\s+((?:0|[1-9][0-9]*)(?:\.[0-9]{1,9})?)(?:\s*[:–—-]\s*[\p{L}][^\r\n]*)?$""",
        )
        private val volumeNumber = Regex("""^[1-9][0-9]*$""")
    }

    fun execute(
        scope: InventoryScope,
        storedClaims: List<InventoryStoredClaim>,
        selectedSourceRecordIds: List<String>,
        existingChapters: List<CanonicalChapter>,
    ): UserAdoptionPreview {
        if (scope.editionKey.isNullOrBlank() || scope.language.isNullOrBlank()) {
            return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.EDITION_OR_LANGUAGE_MISSING)
        }
        if (
            selectedSourceRecordIds.isEmpty() ||
            selectedSourceRecordIds.size > MAX_SELECTION ||
            selectedSourceRecordIds.any(String::isBlank) ||
            selectedSourceRecordIds.toSet().size != selectedSourceRecordIds.size ||
            existingChapters.any { it.canonicalTitleId != scope.canonicalTitleId }
        ) {
            return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.DUPLICATE_OR_COLLIDING_UNIT)
        }
        val allById = storedClaims.filter { it.scope == scope }.groupBy { it.claim.sourceRecordId }
        val selected = selectedSourceRecordIds.map { id ->
            allById[id]?.singleOrNull()
                ?: return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.SOURCE_SNAPSHOT_MISMATCH)
        }
        val revisionScopes = hashSetOf<Triple<InventoryCandidateSourceKind, String, String>>()
        val identities = hashSetOf<Pair<CanonicalChapterIdentity, Int?>>()
        val sourceUnits = hashSetOf<String>()
        val units = mutableListOf<UserAdoptionPreviewUnit>()
        selected.forEach { stored ->
            val claim = stored.claim
            val provenance = claim.provenance
                ?: return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.NOT_AN_EXPLICIT_INDIVIDUAL_CANDIDATE)
            if (
                !InventoryClaimPolicy.isNonEditorialCandidate(claim) ||
                claim.language != scope.language ||
                claim.label.isNullOrBlank() ||
                (provenance.sourceKind == InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION &&
                    (provenance.attributionReference.isNullOrBlank() || provenance.licenseReference.isNullOrBlank())) ||
                (provenance.sourceKind == InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC &&
                    provenance.structuralCategory != "toc-chapter") ||
                (provenance.sourceKind == InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION &&
                    provenance.structuralCategory !in setOf("chapter-list-entry", "chapter-table-entry"))
            ) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.NOT_AN_EXPLICIT_INDIVIDUAL_CANDIDATE)
            }
            revisionScopes += Triple(provenance.sourceKind, provenance.exactRevision, provenance.snapshotDigest)
            if (!sourceUnits.add(provenance.sourceUnitId)) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.DUPLICATE_OR_COLLIDING_UNIT)
            }
            val normalizedNumber = exactNumber.matchEntire(claim.label.trim())?.value
                ?: namedNumber.matchEntire(claim.label.trim())?.groupValues?.get(1)
                ?: return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.UNSUPPORTED_NUMBERING)
            val baseNumber = normalizedNumber.substringBefore('.').toIntOrNull()
                ?: return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.UNSUPPORTED_NUMBERING)
            val fraction = normalizedNumber.substringAfter('.', "").takeIf(String::isNotBlank)
                ?.let(CanonicalChapterDecimalFraction::parse)
            if ('.' in normalizedNumber && fraction == null) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.UNSUPPORTED_NUMBERING)
            }
            val volume = claim.volumeLabel?.takeIf(volumeNumber::matches)?.toIntOrNull()
            if (claim.volumeLabel != null && volume == null) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.UNSUPPORTED_NUMBERING)
            }
            val identity = CanonicalChapterIdentity(
                type = CanonicalChapterType.REGULAR,
                baseNumber = baseNumber,
                part = fraction?.numerator,
                numberingKind = if (fraction == null) null else CanonicalChapterNumberingKind.DECIMAL,
                decimalFraction = fraction,
            )
            if (!identities.add(identity to volume)) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.DUPLICATE_OR_COLLIDING_UNIT)
            }
            // A legacy row may carry progress/history/downloads. Never rekey or
            // infer that it belongs to this user-selected publication.
            if (existingChapters.any { it.baseNumber == baseNumber }) {
                return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.CANONICAL_IDENTITY_COLLISION)
            }
            units += UserAdoptionPreviewUnit(
                sourceRecordId = claim.sourceRecordId,
                label = buildString {
                    append(baseNumber)
                    fraction?.let { append('.').append(it.asNormalizedDigits()) }
                },
                identity = identity,
                volume = volume,
                provenance = provenance,
                observedAt = claim.observedAt,
                derivation = claim.derivation,
            )
        }
        if (revisionScopes.size != 1) {
            return UserAdoptionPreview.Blocked(UserAdoptionBlockReason.SOURCE_SNAPSHOT_MISMATCH)
        }
        val fingerprint = fingerprint(scope, units)
        return UserAdoptionPreview.Ready(scope, units, fingerprint)
    }

    private fun fingerprint(scope: InventoryScope, units: List<UserAdoptionPreviewUnit>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(raw: String) {
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        field("USER_ADOPTED:v1")
        listOf(scope.canonicalTitleId, scope.producerId, scope.externalWorkId,
            requireNotNull(scope.editionKey), requireNotNull(scope.language)).forEach(::field)
        units.forEach { unit ->
            field(unit.sourceRecordId)
            field(unit.label)
            field(unit.identity.sortKey)
            field(unit.volume?.toString().orEmpty())
            field(unit.observedAt.toString())
            field(unit.derivation.name)
            with(unit.provenance) {
                listOf(sourceKind.name, originReference, exactRevision, attributionReference.orEmpty(),
                    licenseReference.orEmpty(), snapshotDigest, sourceUnitId, structuralCategory).forEach(::field)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}
