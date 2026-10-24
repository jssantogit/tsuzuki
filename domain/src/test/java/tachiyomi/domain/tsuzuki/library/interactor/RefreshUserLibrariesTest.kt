package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.CapabilityPolicy
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy
import tachiyomi.domain.tsuzuki.integration.model.UserLibraryEntry
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot
import tachiyomi.domain.tsuzuki.integration.model.UserListDefinition
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

class RefreshUserLibrariesTest {

    @Test
    fun `successful refresh replaces provider snapshot using canonical identity`() = runTest {
        val titleRepository = FakeTitleRepository()
        val externalRepository = FakeExternalLibraryRepository()
        val provider = FakeUserListProvider(
            result = Result.success(
                UserLibrarySnapshot(
                    lists = listOf(
                        UserListDefinition(
                            key = "mal:status:plan_to_read",
                            title = "Plan to Read",
                            status = LibraryStatus.PLANNING,
                            selectionGroup = "mal:status",
                        ),
                    ),
                    entries = listOf(
                        UserLibraryEntry(
                            item = CatalogItem(
                                provider = "mal",
                                providerId = "42",
                                title = "Monster",
                                coverUrl = "https://cdn.example/monster.jpg",
                            ),
                            listKeys = setOf("mal:status:plan_to_read"),
                            status = LibraryStatus.PLANNING,
                            remoteStatus = "plan_to_read",
                        ),
                    ),
                ),
            ),
        )
        val materialize = MaterializeCanonicalTitleFromCatalog(
            MaterializeCanonicalTitle(
                repository = titleRepository,
                idFactory = { "canonical-42" },
                clock = { 100L },
            ),
        )
        val interactor = RefreshUserLibraries(
            registry = FakeRegistry(provider),
            materializeCanonicalTitleFromCatalog = materialize,
            externalLibraryRepository = externalRepository,
            clock = { 500L },
        )

        interactor.refreshProvider(IntegrationId("mal")).getOrThrow() shouldBe 1

        externalRepository.memberships.value.single() shouldBe ExternalLibraryMembership(
            canonicalTitleId = "canonical-42",
            provider = "mal",
            externalId = "42",
            listKey = "mal:status:plan_to_read",
            listTitle = "Plan to Read",
            selectionGroup = "mal:status",
            status = LibraryStatus.PLANNING,
            remoteStatus = "plan_to_read",
            progress = null,
            score = null,
            syncedAt = 500L,
            coverUrl = "https://cdn.example/monster.jpg",
        )
    }

    @Test
    fun `legacy tracker identity refreshes and clears its user library provider`() = runTest {
        val externalRepository = FakeExternalLibraryRepository()
        val materialize = MaterializeCanonicalTitleFromCatalog(
            MaterializeCanonicalTitle(
                repository = FakeTitleRepository(),
                idFactory = { "canonical-42" },
                clock = { 100L },
            ),
        )
        val provider = FakeUserListProvider(
            result = Result.success(
                UserLibrarySnapshot(
                    lists = emptyList(),
                    entries = listOf(
                        UserLibraryEntry(
                            item = CatalogItem(provider = "mal", providerId = "42", title = "Monster"),
                            listKeys = setOf("mal:status:reading"),
                            status = LibraryStatus.READING,
                            remoteStatus = "reading",
                        ),
                    ),
                ),
            ),
        )
        val interactor = RefreshUserLibraries(
            registry = FakeRegistry(provider),
            materializeCanonicalTitleFromCatalog = materialize,
            externalLibraryRepository = externalRepository,
            clock = { 500L },
        )

        interactor.refreshForLegacyTracker(1L)?.getOrThrow() shouldBe 1
        externalRepository.memberships.value.size shouldBe 1

        interactor.clearForLegacyTracker(1L)
        externalRepository.memberships.value shouldBe emptyList()
    }

    @Test
    fun `failed refresh preserves the previous provider snapshot`() = runTest {
        val previous = ExternalLibraryMembership(
            canonicalTitleId = "existing",
            provider = "mal",
            externalId = "7",
            listKey = "mal:status:reading",
            status = LibraryStatus.READING,
            remoteStatus = "reading",
            progress = 4.0,
            score = null,
            syncedAt = 100L,
        )
        val externalRepository = FakeExternalLibraryRepository(listOf(previous))
        val materialize = MaterializeCanonicalTitleFromCatalog(
            MaterializeCanonicalTitle(
                repository = FakeTitleRepository(),
                idFactory = { "unused" },
                clock = { 100L },
            ),
        )
        val interactor = RefreshUserLibraries(
            registry = FakeRegistry(
                FakeUserListProvider(Result.failure(IllegalStateException("offline"))),
            ),
            materializeCanonicalTitleFromCatalog = materialize,
            externalLibraryRepository = externalRepository,
            clock = { 500L },
        )

        interactor.refreshProvider(IntegrationId("mal")).isFailure shouldBe true
        externalRepository.memberships.value shouldBe listOf(previous)
    }

    @Test
    fun `disconnected ownerless provider cannot delete legacy private snapshot`() = runTest {
        val legacy = ExternalLibraryMembership(
            canonicalTitleId = "A-canonical",
            provider = "mal",
            externalId = "123",
            listKey = "mal:status:reading",
            status = LibraryStatus.READING,
            remoteStatus = "reading",
            progress = 4.0,
            score = null,
            syncedAt = 100L,
        )
        val repository = FakeExternalLibraryRepository(listOf(legacy), ownerScoped = false)
        val provider = FakeUserListProvider(
            Result.success(UserLibrarySnapshot(emptyList(), emptyList())),
            connected = false,
        )
        val interactor = RefreshUserLibraries(
            registry = FakeRegistry(provider),
            materializeCanonicalTitleFromCatalog = MaterializeCanonicalTitleFromCatalog(
                MaterializeCanonicalTitle(
                    repository = FakeTitleRepository(),
                    idFactory = { "must-not-materialize" },
                    clock = { 200L },
                ),
            ),
            externalLibraryRepository = repository,
            clock = { 300L },
        )

        interactor.refreshProvider(IntegrationId("mal")).isFailure shouldBe true
        interactor.refreshConnected()[IntegrationId("mal")]?.isFailure shouldBe true
        interactor.clearForLegacyTracker(1L)
        repository.memberships.value shouldBe listOf(legacy)
        provider.fetchCount shouldBe 0
    }

    @Test
    fun `missing verified account scope blocks remote fetch and preserves legacy snapshot`() = runTest {
        val legacy = ExternalLibraryMembership(
            canonicalTitleId = "previous-A",
            provider = "mal",
            externalId = "7",
            listKey = "mal:status:reading",
            status = LibraryStatus.READING,
            remoteStatus = "reading",
            progress = 2.0,
            score = null,
            syncedAt = 100L,
        )
        val repository = FakeExternalLibraryRepository(listOf(legacy), ownerScoped = false)
        val provider = FakeUserListProvider(Result.success(UserLibrarySnapshot(emptyList(), emptyList())))
        val interactor = RefreshUserLibraries(
            registry = FakeRegistry(provider),
            materializeCanonicalTitleFromCatalog = MaterializeCanonicalTitleFromCatalog(
                MaterializeCanonicalTitle(
                    repository = FakeTitleRepository(),
                    idFactory = { "unexpected-new-title" },
                    clock = { 200L },
                ),
            ),
            externalLibraryRepository = repository,
            clock = { 300L },
        )

        interactor.refreshProvider(IntegrationId("mal")).isFailure shouldBe true
        provider.fetchCount shouldBe 0
        repository.memberships.value shouldBe listOf(legacy)
        val connected = interactor.refreshConnected()
        connected[IntegrationId("mal")]?.isFailure shouldBe true
        provider.fetchCount shouldBe 0
        repository.memberships.value shouldBe listOf(legacy)

        // Logout cannot authorize wiping a private A snapshot either.
        interactor.clearForLegacyTracker(1L)
        interactor.clearProvider(IntegrationId("mal"))
        repository.memberships.value shouldBe listOf(legacy)
    }

    private class FakeUserListProvider(
        private val result: Result<UserLibrarySnapshot>,
        private val connected: Boolean = true,
    ) : UserListProvider {
        override val integrationId = IntegrationId("mal")
        override val connection: Flow<Boolean> = flowOf(connected)
        var fetchCount = 0
        override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> {
            fetchCount++
            return result
        }
    }

    private class FakeRegistry(
        private val provider: UserListProvider,
    ) : IntegrationRegistry {
        override fun manifests() = listOf(
            IntegrationManifest(
                integrationId = provider.integrationId,
                displayName = "MyAnimeList",
                category = IntegrationCategory.METADATA_SERVICE,
                capabilities = mapOf(
                    IntegrationCapability.USER_LISTS to CapabilityPolicy(IntegrationPolicy.ALLOWED),
                ),
                legacyTrackerId = 1L,
            ),
        )

        override fun searchProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.SearchProvider>()
        override fun discoveryProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.DiscoveryProvider>()
        override fun metadataProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.MetadataProvider>()
        override fun chapterEvidenceProviders() =
            emptyList<tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider>()
        override fun ratingsProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.RatingsProvider>()
        override fun trackingProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.TrackingProvider>()
        override fun userListProviders() = listOf(provider)
    }

    private class FakeExternalLibraryRepository(
        initial: List<ExternalLibraryMembership> = emptyList(),
        private val ownerScoped: Boolean = true,
    ) : ExternalLibraryRepository {
        override val hasVerifiedOwnerScope: Boolean get() = ownerScoped
        val memberships = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<ExternalLibraryMembership>> =
            memberships

        override suspend fun replaceProvider(
            provider: String,
            memberships: List<ExternalLibraryMembership>,
        ) {
            this.memberships.value = this.memberships.value.filterNot { it.provider == provider } + memberships
        }

        override suspend fun clearProvider(provider: String) {
            memberships.value = memberships.value.filterNot { it.provider == provider }
        }

        override suspend fun getProviderIds(): Set<String> = memberships.value.mapTo(mutableSetOf()) { it.provider }
    }

    private class FakeTitleRepository : CanonicalTitleRepository {
        private val titles = mutableMapOf<String, CanonicalTitle>()
        private val identities = mutableListOf<ExternalIdentity>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(titles[id])

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val id = identities.firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return id?.let(titles::get)
        }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            titles[title.id] = title
            identities += identity
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
    }
}
