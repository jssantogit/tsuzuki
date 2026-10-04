package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion

class DefaultIntegrationRegistryTest {

    @Test
    fun `registry excludes providers whose integration is disabled`() = runTest {
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry excludes providers without a persisted setting`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
            searchProviders = setOf(FakeSearchProvider("kitsu")),
        )

        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry does not infer enabled state from provider presence`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val malSearch = FakeSearchProvider("mal")
        val malDiscovery = FakeDiscoveryProvider("mal")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("kitsu" to false, "mal" to true)),
            searchProviders = setOf(kitsuSearch, malSearch),
            discoveryProviders = setOf(FakeDiscoveryProvider("kitsu"), malDiscovery),
            metadataProviders = setOf(FakeMetadataProvider("kitsu"), FakeMetadataProvider("mal")),
            chapterEvidenceProviders = setOf(FakeChapterEvidenceProvider("kitsu"), FakeChapterEvidenceProvider("mal")),
            ratingsProviders = setOf(FakeRatingsProvider("kitsu"), FakeRatingsProvider("mal")),
            trackingProviders = setOf(FakeTrackingProvider("kitsu"), FakeTrackingProvider("mal")),
        )

        registry.searchProviders() shouldContainExactly listOf(malSearch)
        registry.discoveryProviders() shouldContainExactly listOf(malDiscovery)
        registry.metadataProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.chapterEvidenceProviders().map { it.producerId } shouldContainExactly listOf("mal")
        registry.ratingsProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
        registry.trackingProviders().map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `registry exposes enabled user list providers`() = runTest {
        val mal = FakeUserListProvider("mal")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("mal" to true)),
            userListProviders = setOf(mal),
        )

        registry.userListProviders() shouldContainExactly listOf(mal)
    }

    @Test
    fun `personal server metadata stays server scoped`() = runTest {
        val komga = FakeMetadataProvider("komga")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("komga" to true)),
            metadataProviders = setOf(komga),
        )

        registry.metadataProviders() shouldBe emptyList()
        registry.isGlobalCapabilityActive(
            IntegrationId("komga"),
            IntegrationCapability.METADATA_BASIC,
        ) shouldBe false
    }

    @Test
    fun `registry exposes unified integration manifests`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        registry.manifests().map { it.integrationId.value }.toSet() shouldBe setOf(
            "tsuzuki",
            "kitsu",
            "mal",
            "mangaupdates",
            "bangumi",
            "shikimori",
            "hikka",
            "komga",
            "kavita",
            "suwayomi",
        )
    }

    @Test
    fun `Tsuzuki ratings capability follows its own global and capability switches`() = runTest {
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("tsuzuki"),
                    enabled = true,
                    configJson = """{"ratings":true}""",
                ),
            ),
        )
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
        )

        registry.isGlobalCapabilityActive(
            IntegrationId("tsuzuki"),
            IntegrationCapability.RATINGS,
        ) shouldBe true

        settings.value = listOf(
            IntegrationSettings(
                integrationId = IntegrationId("tsuzuki"),
                enabled = true,
                configJson = """{"ratings":false}""",
            ),
        )
        registry.isGlobalCapabilityActive(
            IntegrationId("tsuzuki"),
            IntegrationCapability.RATINGS,
        ) shouldBe false

        settings.value = fakeSettings("tsuzuki" to false)
        registry.isGlobalCapabilityActive(
            IntegrationId("tsuzuki"),
            IntegrationCapability.RATINGS,
        ) shouldBe false
    }

    @Test
    fun `catalog providers with ranked feeds declare discovery capability`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        listOf("mangaupdates", "bangumi").forEach { integrationId ->
            val manifest = registry.manifests().single { it.integrationId.value == integrationId }

            (IntegrationCapability.DISCOVERY in manifest.capabilities) shouldBe true
        }
    }

    @Test
    fun `staff metadata is exposed only by manifests that declare it`() = runTest {
        val kitsu = FakeMetadataProvider("kitsu")
        val mal = FakeMetadataProvider("mal")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("kitsu" to true, "mal" to true)),
            metadataProviders = setOf(kitsu, mal),
        )

        registry.metadataProviders(IntegrationCapability.METADATA_STAFF)
            .map { it.integrationId.value } shouldContainExactly listOf("mal")
    }

    @Test
    fun `metadata capability switches are independent`() = runTest {
        val kitsu = FakeMetadataProvider("kitsu")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(
                listOf(
                    IntegrationSettings(
                        integrationId = IntegrationId("kitsu"),
                        enabled = true,
                        configJson = """{"metadata":true,"metadata_artwork":false}""",
                    ),
                ),
            ),
            metadataProviders = setOf(kitsu),
        )

        registry.metadataProviders() shouldContainExactly listOf(kitsu)
        registry.metadataProviders(IntegrationCapability.METADATA_ARTWORK) shouldBe emptyList()
        registry.isGlobalCapabilityActive(
            IntegrationId("kitsu"),
            IntegrationCapability.METADATA_BASIC,
        ) shouldBe true
        registry.isGlobalCapabilityActive(
            IntegrationId("kitsu"),
            IntegrationCapability.METADATA_ARTWORK,
        ) shouldBe false
    }

    @Test
    fun `unified manifests preserve legacy tracker ids`() = runTest {
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(emptyList()),
        )

        registry.manifests().associate { it.integrationId.value to it.legacyTrackerId } shouldBe mapOf(
            "tsuzuki" to null,
            "kitsu" to 3L,
            "mal" to 1L,
            "mangaupdates" to 7L,
            "bangumi" to 5L,
            "shikimori" to 4L,
            "hikka" to 10L,
            "komga" to 6L,
            "kavita" to 8L,
            "suwayomi" to 9L,
        )
    }

    @Test
    fun `shikimori and hikka catalog policies are globally allowed when enabled`() = runTest {
        val shikimoriRating = FakeRatingsProvider("shikimori")
        val hikkaRating = FakeRatingsProvider("hikka")
        val shikimoriSearch = FakeSearchProvider("shikimori")
        val hikkaSearch = FakeSearchProvider("hikka")
        val shikimoriMetadata = FakeMetadataProvider("shikimori")
        val hikkaMetadata = FakeMetadataProvider("hikka")
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = MutableStateFlow(fakeSettings("shikimori" to true, "hikka" to true)),
            ratingsProviders = setOf(shikimoriRating, hikkaRating),
            searchProviders = setOf(shikimoriSearch, hikkaSearch),
            metadataProviders = setOf(shikimoriMetadata, hikkaMetadata),
        )

        registry.ratingsProviders().map { it.integrationId.value }.toSet() shouldBe setOf("shikimori", "hikka")
        registry.searchProviders().map { it.integrationId.value }.toSet() shouldBe setOf("shikimori", "hikka")
        registry.metadataProviders().map { it.integrationId.value }.toSet() shouldBe setOf("shikimori", "hikka")

        listOf("shikimori", "hikka").forEach { id ->
            registry.isGlobalCapabilityActive(
                IntegrationId(id),
                IntegrationCapability.RATINGS,
            ) shouldBe true
            registry.isGlobalCapabilityActive(
                IntegrationId(id),
                IntegrationCapability.SEARCH,
            ) shouldBe true
            registry.isGlobalCapabilityActive(
                IntegrationId(id),
                IntegrationCapability.METADATA_BASIC,
            ) shouldBe true
        }
    }

    @Test
    fun `registry reflects settings changes without being reconstructed`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val settings = MutableStateFlow(fakeSettings("kitsu" to false))
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(kitsuSearch),
        )

        registry.searchProviders() shouldBe emptyList()

        settings.value = fakeSettings("kitsu" to true)
        registry.searchProviders() shouldContainExactly listOf(kitsuSearch)

        settings.value = fakeSettings("kitsu" to false)
        registry.searchProviders() shouldBe emptyList()
    }

    @Test
    fun `registry honors disabled capability without disabling provider entirely`() = runTest {
        val kitsuSearch = FakeSearchProvider("kitsu")
        val kitsuDiscovery = FakeDiscoveryProvider("kitsu")
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("kitsu"),
                    enabled = true,
                    configJson = """{"search":true,"discovery":false}""",
                ),
            ),
        )
        val registry = registry(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            settings = settings,
            searchProviders = setOf(kitsuSearch),
            discoveryProviders = setOf(kitsuDiscovery),
        )

        registry.searchProviders() shouldContainExactly listOf(kitsuSearch)
        registry.discoveryProviders() shouldBe emptyList()
    }

    @Test
    fun `builtin Provider registry preserves integration identities and capabilities`() = runTest {
        val settings = MutableStateFlow(
            fakeSettings(
                "tsuzuki" to true,
                "kitsu" to true,
                "mal" to true,
                "mangaupdates" to true,
                "bangumi" to true,
                "shikimori" to true,
                "hikka" to true,
                "komga" to true,
                "kavita" to true,
                "suwayomi" to true,
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        val registrations = providers.providers()
        registrations.map { it.descriptor.id.value }.toSet() shouldBe setOf(
            "tsuzuki",
            "kitsu",
            "mal",
            "mangaupdates",
            "bangumi",
            "shikimori",
            "hikka",
            "komga",
            "kavita",
            "suwayomi",
        )
        registrations.forEach { registration ->
            registration.descriptor.origin shouldBe ProviderOrigin.Builtin
            registration.descriptor.runtime shouldBe ProviderRuntimeKind.BUILTIN
            registration.descriptor.version shouldBe ProviderVersion("test-app", 42)
            registration.lifecycleStatus shouldBe ProviderLifecycleStatus.ENABLED
        }

        registrations.single { it.descriptor.id.value == "kitsu" }
            .descriptor.capabilities shouldBe setOf(
            ProviderCapabilities.CatalogSearchV1,
            ProviderCapabilities.CatalogDiscoverV1,
            ProviderCapabilities.MetadataBasicV1,
            ProviderCapabilities.MetadataArtworkV1,
            ProviderCapabilities.MetadataEditorialV1,
            ProviderCapabilities.RatingsReadV1,
            ProviderCapabilities.AccountTrackingV1,
            ProviderCapabilities.AccountListsV1,
        )
    }

    @Test
    fun `builtin capability switches do not erase declared capabilities`() = runTest {
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("kitsu"),
                    enabled = true,
                    configJson = """{"search":false,"discovery":true}""",
                    updatedAt = 10,
                ),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        val kitsu = providers.providers().single { it.descriptor.id.value == "kitsu" }
        ProviderCapabilities.CatalogSearchV1 in kitsu.descriptor.capabilities shouldBe true
        ProviderCapabilities.CatalogDiscoverV1 in kitsu.descriptor.capabilities shouldBe true
        ProviderCapabilities.CatalogSearchV1 in kitsu.enabledCapabilities shouldBe false
        ProviderCapabilities.CatalogDiscoverV1 in kitsu.enabledCapabilities shouldBe true
        providers.enabled(ProviderCapabilities.CatalogSearchV1)
            .none { it.id.value == "kitsu" } shouldBe true
        providers.enabled(ProviderCapabilities.CatalogDiscoverV1)
            .any { it.id.value == "kitsu" } shouldBe true
    }

    @Test
    fun `personal server metadata remains declared but server scoped`() = runTest {
        val settings = MutableStateFlow(fakeSettings("komga" to true))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        val komga = providers.providers().single { it.descriptor.id.value == "komga" }
        ProviderCapabilities.MetadataBasicV1 in komga.descriptor.capabilities shouldBe true
        ProviderCapabilities.MetadataArtworkV1 in komga.descriptor.capabilities shouldBe true
        ProviderCapabilities.LibraryRemoteV1 in komga.descriptor.capabilities shouldBe true
        ProviderCapabilities.MetadataBasicV1 in komga.enabledCapabilities shouldBe false
        ProviderCapabilities.MetadataArtworkV1 in komga.enabledCapabilities shouldBe false
        ProviderCapabilities.LibraryRemoteV1 in komga.enabledCapabilities shouldBe true
        providers.enabled(ProviderCapabilities.MetadataBasicV1)
            .none { it.id.value == "komga" } shouldBe true
    }

    @Test
    fun `suwayomi preserves scoped reading and download capabilities`() = runTest {
        val settings = MutableStateFlow(fakeSettings("suwayomi" to true))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        val suwayomi = providers.providers().single { it.descriptor.id.value == "suwayomi" }
        setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
            ProviderCapabilities.DownloadsManageV1,
        ).all { it in suwayomi.descriptor.capabilities } shouldBe true
        setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
            ProviderCapabilities.DownloadsManageV1,
        ).all { it in suwayomi.enabledCapabilities } shouldBe true
    }

    @Test
    fun `builtin enablement reuses integration settings without erasing capability config`() = runTest {
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(
                    integrationId = IntegrationId("kitsu"),
                    enabled = true,
                    configJson = """{"search":false,"ratings":true}""",
                    updatedAt = 10,
                ),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        providers.setEnabled(
            providerId = tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"),
            enabled = false,
        )

        val persisted = repository.get(IntegrationId("kitsu"))
        persisted?.enabled shouldBe false
        persisted?.configJson shouldBe """{"search":false,"ratings":true}"""
        providers.registration(tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"))
            ?.lifecycleStatus shouldBe ProviderLifecycleStatus.DISABLED
    }

    @Test
    fun `builtin configuration fingerprint changes only for the edited Provider`() = runTest {
        val settings = MutableStateFlow(
            listOf(
                IntegrationSettings(IntegrationId("kitsu"), true, "{}", 10),
                IntegrationSettings(IntegrationId("mal"), true, "{}", 10),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val repository = FakeIntegrationSettingsRepository(settings)
        val integrations = DefaultIntegrationRegistry(
            settingsRepository = repository,
            scope = scope,
        )
        val providers = BuiltinIntegrationProviderRegistry(
            integrationRegistry = integrations,
            settingsRepository = repository,
            providerVersion = ProviderVersion("test-app", 42),
            scope = scope,
        )

        providers.awaitReady()
        val kitsuBefore = providers.configurationFingerprint(
            tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"),
        )
        val malBefore = providers.configurationFingerprint(
            tachiyomi.domain.tsuzuki.provider.ProviderId("mal"),
        )

        repository.upsert(
            IntegrationSettings(
                integrationId = IntegrationId("kitsu"),
                enabled = true,
                configJson = """{"search":false}""",
                updatedAt = 11,
            ),
        )

        providers.configurationFingerprint(
            tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"),
        ) shouldBe providers.registration(
            tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"),
        )?.configurationFingerprint
        (providers.configurationFingerprint(
            tachiyomi.domain.tsuzuki.provider.ProviderId("kitsu"),
        ) != kitsuBefore) shouldBe true
        providers.configurationFingerprint(
            tachiyomi.domain.tsuzuki.provider.ProviderId("mal"),
        ) shouldBe malBefore
    }

    private fun registry(
        scope: CoroutineScope,
        settings: MutableStateFlow<List<IntegrationSettings>>,
        searchProviders: Set<SearchProvider> = emptySet(),
        discoveryProviders: Set<DiscoveryProvider> = emptySet(),
        metadataProviders: Set<MetadataProvider> = emptySet(),
        chapterEvidenceProviders: Set<ChapterEvidenceProvider> = emptySet(),
        ratingsProviders: Set<RatingsProvider> = emptySet(),
        trackingProviders: Set<TrackingProvider> = emptySet(),
        userListProviders: Set<UserListProvider> = emptySet(),
    ) = DefaultIntegrationRegistry(
        settingsRepository = FakeIntegrationSettingsRepository(settings),
        searchProviders = searchProviders,
        discoveryProviders = discoveryProviders,
        metadataProviders = metadataProviders,
        chapterEvidenceProviders = chapterEvidenceProviders,
        ratingsProviders = ratingsProviders,
        trackingProviders = trackingProviders,
        userListProviders = userListProviders,
        scope = scope,
    )

    private fun fakeSettings(vararg settings: Pair<String, Boolean>): List<IntegrationSettings> =
        settings.map { (id, enabled) ->
            IntegrationSettings(integrationId = IntegrationId(id), enabled = enabled)
        }

    private class FakeIntegrationSettingsRepository(
        private val settings: MutableStateFlow<List<IntegrationSettings>>,
    ) : IntegrationSettingsRepository {
        override suspend fun get(id: IntegrationId): IntegrationSettings? =
            settings.value.firstOrNull { it.integrationId == id }

        override fun observeAll(): Flow<List<IntegrationSettings>> = settings

        override suspend fun upsert(settings: IntegrationSettings) {
            this.settings.value = this.settings.value
                .filterNot { it.integrationId == settings.integrationId } + settings
        }
    }

    private class FakeSearchProvider(id: String) : SearchProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
            Result.success(CatalogPage(emptyList(), hasNextPage = false))
    }

    private class FakeDiscoveryProvider(id: String) : DiscoveryProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> = emptyPage()

        private fun emptyPage() = Result.success(CatalogPage(emptyList(), hasNextPage = false))
    }

    private class FakeMetadataProvider(id: String) : MetadataProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun getDetails(externalId: String): Result<CatalogItem> =
            Result.failure(UnsupportedOperationException())
    }

    private class FakeChapterEvidenceProvider(override val producerId: String) : ChapterEvidenceProvider {
        override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> =
            Result.success(emptyList())
    }

    private class FakeRatingsProvider(id: String) : RatingsProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
            Result.success(emptyList())
    }

    private class FakeUserListProvider(id: String) : UserListProvider {
        override val integrationId = IntegrationId(id)
        override val connection: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun fetchLibrary(): Result<UserLibrarySnapshot> =
            Result.success(UserLibrarySnapshot(emptyList(), emptyList()))
    }

    private class FakeTrackingProvider(id: String) : TrackingProvider {
        override val integrationId = IntegrationId(id)

        override suspend fun isConnected(): Boolean = false

        override suspend fun update(update: TrackingUpdate): Result<Unit> = Result.success(Unit)
    }
}
