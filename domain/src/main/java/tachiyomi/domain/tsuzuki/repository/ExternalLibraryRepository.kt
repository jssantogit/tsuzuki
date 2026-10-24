package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership

interface ExternalLibraryRepository {
    /**
     * A provider name or login flag is NOT a verified remote account owner.
     * Fail closed until every durable external membership is indexed and read
     * by a provider-issued, session-verified owner scope. Legacy rows must
     * remain quarantined rather than adopted or deleted.
     */
    val hasVerifiedOwnerScope: Boolean get() = false

    fun observeAll(): Flow<List<ExternalLibraryMembership>>

    suspend fun replaceProvider(
        provider: String,
        memberships: List<ExternalLibraryMembership>,
    )

    suspend fun clearProvider(provider: String)

    suspend fun getProviderIds(): Set<String>
}
