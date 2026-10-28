package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationResult
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationResult

interface CanonicalTitleRepository {
    suspend fun getById(id: String): CanonicalTitle?
    fun getByIdAsFlow(id: String): Flow<CanonicalTitle?>
    fun getAllAsFlow(): Flow<List<CanonicalTitle>> = flowOf(emptyList())
    suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle?
    suspend fun getExternalIdentities(canonicalTitleId: String): List<ExternalIdentity> = emptyList()
    suspend fun getOrCreateByExternalIdentity(
        title: CanonicalTitle,
        identity: ExternalIdentity,
    ): CanonicalTitle
    suspend fun insert(title: CanonicalTitle)

    suspend fun upsert(title: CanonicalTitle) {
        val existing = getById(title.id)
        if (existing == null) {
            insert(title)
        } else {
            require(existing == title) {
                "Repository does not support updating an existing canonical title"
            }
        }
    }

    suspend fun addExternalIdentity(identity: ExternalIdentity)

    /**
     * Associates one verified MangaDex work only when persisted integration permission and the
     * exact verified crosswalk snapshot are still current. Repositories without an atomic
     * implementation fail closed instead of falling back to [addExternalIdentity].
     */
    /**
     * Transactional work-only association shared by inventory producers.
     * The default remains fail-closed for repositories without an implementation.
     */
    suspend fun associateVerifiedInventoryWorkIfCurrent(
        request: VerifiedInventoryWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ): VerifiedInventoryWorkAssociationResult =
        if (isAuthorized()) {
            VerifiedInventoryWorkAssociationResult.UNSUPPORTED
        } else {
            VerifiedInventoryWorkAssociationResult.REFUSED
        }

    suspend fun associateMangaDexWorkIfCurrent(
        request: MangaDexWorkAssociationRequest,
        isAuthorized: () -> Boolean,
    ): MangaDexWorkAssociationResult =
        if (isAuthorized()) MangaDexWorkAssociationResult.UNSUPPORTED else MangaDexWorkAssociationResult.REFUSED
}
