package tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

/**
 * Quarantines every legacy membership until a provider-issued remote account
 * identity is stored with each row and checked for every read and mutation.
 *
 * The previous table is keyed only by provider/external work/list. Applying a
 * login for account B to those rows would expose A's reading status and can
 * overwrite A's private lists. Do not adopt existing rows, reset the table,
 * or silently promote provider-only authority to owner authority.
 *
 * This deliberately makes connected external lists unavailable while the
 * versioned account-scoped migration is blocked by the parallel 43.sqm work.
 * Canonical local library titles and progress remain accessible separately.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExternalLibraryRepositoryImpl(
    @Suppress("unused") private val database: Database,
) : ExternalLibraryRepository {

    override val hasVerifiedOwnerScope: Boolean = false

    override fun observeAll(): Flow<List<ExternalLibraryMembership>> = flowOf(emptyList())

    override suspend fun replaceProvider(
        provider: String,
        memberships: List<ExternalLibraryMembership>,
    ): Unit = throw IllegalStateException(
        "Cannot replace external lists without verified account ownership",
    )

    override suspend fun clearProvider(provider: String) {
        // A disconnect or provider update is not authorization to delete
        // unowned legacy rows. Retain the original bytes for explicit recovery.
    }

    override suspend fun getProviderIds(): Set<String> = emptySet()
}
