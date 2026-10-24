package tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.VerifiedExternalLibraryOwner
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

/**
 * Scope every live read and write to a fresh, verified provider-issued
 * account identity. The original provider-only table is preserved unchanged
 * and is never displayed, adopted, erased, or backed up implicitly.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExternalLibraryRepositoryImpl(
    private val database: Database,
    private val registry: IntegrationRegistry,
) : ExternalLibraryRepository {

    internal constructor(database: Database) : this(database, EmptyExternalLibraryRegistry)

    override val hasVerifiedOwnerScope: Boolean = true

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<ExternalLibraryMembership>> {
        val providers = registry.userListProviders()
        if (providers.isEmpty()) return flowOf(emptyList())
        val ownerStreams = providers.map(::observeVerifiedProvider)
        return combine(ownerStreams) { values -> values.flatMap { it } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeVerifiedProvider(provider: UserListProvider): Flow<List<ExternalLibraryMembership>> =
        // Deliberately do not distinctUntilChanged the Boolean: the tracker
        // emits a new event when credentials change even if both logins are true.
        provider.connection.flatMapLatest { connected ->
            if (!connected) {
                flowOf(emptyList())
            } else {
                flow {
                    val remote = try {
                        provider.authenticatedOwnerSubject().getOrNull()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val owner = remote?.let {
                        runCatching {
                            VerifiedExternalLibraryOwner.fromAuthenticatedIdentity(provider.integrationId.value, it)
                        }.getOrNull()
                    }
                    if (owner == null) {
                        emit(emptyList())
                    } else {
                        emitAll(
                            database.tsuzuki_external_library_membershipsQueries
                                .getTsuzukiScopedExternalLibraryMemberships(owner.provider, owner.ownerKey)
                                .subscribeToList()
                                .map { rows ->
                                    rows.map {
                                        ExternalLibraryMembership(
                                            canonicalTitleId = it.canonical_title_id,
                                            provider = it.provider,
                                            externalId = it.external_id,
                                            listKey = it.list_key,
                                            listTitle = it.list_title,
                                            selectionGroup = it.selection_group,
                                            status = it.status?.let(LibraryStatus::valueOf),
                                            remoteStatus = it.remote_status,
                                            progress = it.progress,
                                            score = it.score,
                                            listedAt = it.listed_at,
                                            syncedAt = it.synced_at,
                                            coverUrl = it.cover_url,
                                        )
                                    }
                                },
                        )
                    }
                }
            }
        }

    override suspend fun replaceOwned(
        owner: VerifiedExternalLibraryOwner,
        memberships: List<ExternalLibraryMembership>,
    ) {
        require(memberships.all { it.provider == owner.provider }) {
            "Provider mismatch in verified account snapshot"
        }
        database.transactionWithResult {
            val queries = database.tsuzuki_external_library_membershipsQueries
            queries.deleteTsuzukiScopedExternalLibraryMembershipsByOwner(owner.provider, owner.ownerKey)
            memberships.forEach { membership ->
                queries.upsertTsuzukiScopedExternalLibraryMembership(
                    canonicalTitleId = membership.canonicalTitleId,
                    provider = owner.provider,
                    ownerKey = owner.ownerKey,
                    externalId = membership.externalId,
                    listKey = membership.listKey,
                    listTitle = membership.listTitle,
                    selectionGroup = membership.selectionGroup,
                    status = membership.status?.name,
                    remoteStatus = membership.remoteStatus,
                    progress = membership.progress,
                    score = membership.score,
                    listedAt = membership.listedAt,
                    syncedAt = membership.syncedAt,
                    coverUrl = membership.coverUrl,
                )
            }
        }
    }

    override suspend fun replaceProvider(
        provider: String,
        memberships: List<ExternalLibraryMembership>,
    ): Unit = throw IllegalStateException("Provider-only library replacement is not account-safe")

    override suspend fun clearProvider(provider: String) {
        // Logout disables the owner-scoped observation; it must not destroy any
        // other account's snapshots or unowned legacy rows.
    }

    override suspend fun getProviderIds(): Set<String> = emptySet()
}

/** Unit-test-only registry: no authenticated owner can be inferred. */
private object EmptyExternalLibraryRegistry : IntegrationRegistry {
    override fun searchProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.SearchProvider>()
    override fun discoveryProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.DiscoveryProvider>()
    override fun metadataProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.MetadataProvider>()
    override fun chapterEvidenceProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider>()
    override fun ratingsProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.RatingsProvider>()
    override fun trackingProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.TrackingProvider>()
}
