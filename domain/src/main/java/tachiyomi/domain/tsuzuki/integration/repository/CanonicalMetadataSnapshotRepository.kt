package tachiyomi.domain.tsuzuki.integration.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

data class CanonicalMetadataSnapshot(
    val canonicalTitleId: String,
    val configurationFingerprint: String,
    val metadata: ResolvedMetadata,
    val refreshedAt: Long,
)

interface CanonicalMetadataSnapshotRepository {
    suspend fun get(canonicalTitleId: String): CanonicalMetadataSnapshot?

    suspend fun getMany(canonicalTitleIds: Collection<String>): Map<String, CanonicalMetadataSnapshot>

    /** Observe local snapshots only. Implementations with persistence supply table notifications. */
    fun observeMany(canonicalTitleIds: Collection<String>): Flow<Map<String, CanonicalMetadataSnapshot>> =
        flow { emit(getMany(canonicalTitleIds)) }

    suspend fun upsertIfNewer(snapshot: CanonicalMetadataSnapshot): CanonicalMetadataSnapshot

    suspend fun invalidateTitle(canonicalTitleId: String)
}
