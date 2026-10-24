package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.VerifiedExternalLibraryOwner
import java.nio.file.Files

class ExternalLibraryOwnershipGateTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ExternalLibraryRepositoryImpl
    private var originalLibraryPath: String? = null
    private var nativeDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeDirectory = Files.createTempDirectory("tsuzuki-external-account-jdbc")
        val library = nativeDirectory!!.resolve("libsqlitejdbc.so")
        ExternalLibraryOwnershipGateTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { Files.copy(it, library) }
        library.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeDirectory.toString())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "A-title",
            displayTitle = "A private work",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        database.tsuzuki_external_library_membershipsQueries.upsertTsuzukiExternalLibraryMembership(
            canonicalTitleId = "A-title",
            provider = "mal",
            externalId = "123",
            listKey = "mal:reading",
            listTitle = "A's private list",
            selectionGroup = null,
            status = "READING",
            remoteStatus = "reading",
            progress = 3.0,
            score = null,
            listedAt = null,
            syncedAt = 100L,
            coverUrl = null,
        )
        repository = ExternalLibraryRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalLibraryPath)
        }
        nativeDirectory?.toFile()?.deleteRecursively()
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    @Test
    fun `unowned legacy memberships are invisible and remain unchanged after account switch`() = runBlocking {
        repository.hasVerifiedOwnerScope shouldBe true
        repository.observeAll().first() shouldBe emptyList()
        repository.getProviderIds() shouldBe emptySet()
        repository.clearProvider("mal")
        val stored = database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships().awaitAsList()
        stored.single().canonical_title_id shouldBe "A-title"
        stored.single().list_title shouldBe "A's private list"
    }

    @Test
    fun `empty remote response cannot erase unknown owner membership`() = runBlocking {
        shouldThrow<IllegalStateException> {
            repository.replaceProvider("mal", emptyList())
        }
        repository.clearProvider("mal")
        repository.clearProvider("other")
        val stored = database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships().awaitAsList()
        stored.size shouldBe 1
        stored.single().external_id shouldBe "123"
        stored.single().synced_at shouldBe 100L
    }

    @Test
    fun `A and B memberships coexist and replacement only clears its own account`() = runBlocking {
        val accountA = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "owner-A")
        val accountB = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "owner-B")
        val source = ExternalLibraryMembership(
            canonicalTitleId = "A-title",
            provider = "mal",
            externalId = "123",
            listKey = "mal:reading",
            status = null,
            remoteStatus = null,
            progress = null,
            score = null,
            syncedAt = 300L,
        )
        repository.replaceOwned(accountA, listOf(source.copy(listTitle = "A list")))
        repository.replaceOwned(accountB, listOf(source.copy(listTitle = "B list")))
        val queries = database.tsuzuki_external_library_membershipsQueries
        queries.getTsuzukiScopedExternalLibraryMemberships("mal", accountA.ownerKey)
            .awaitAsList().single().list_title shouldBe "A list"
        queries.getTsuzukiScopedExternalLibraryMemberships("mal", accountB.ownerKey)
            .awaitAsList().single().list_title shouldBe "B list"

        repository.replaceOwned(accountB, emptyList())
        queries.getTsuzukiScopedExternalLibraryMemberships("mal", accountB.ownerKey)
            .awaitAsList() shouldBe emptyList()
        queries.getTsuzukiScopedExternalLibraryMemberships("mal", accountA.ownerKey)
            .awaitAsList().single().list_title shouldBe "A list"
        queries.getAllTsuzukiExternalLibraryMemberships().awaitAsList().single()
            .list_title shouldBe "A's private list"
        repository.observeAll().first() shouldBe emptyList()
    }

    @Test
    fun `observed library switches accounts without leaking another account or legacy rows`() = runBlocking {
        val active = MutableStateFlow("account-A")
        val provider = object : UserListProvider {
            override val integrationId = IntegrationId("mal")
            override val connection: Flow<Boolean> = active.map { true }
            override suspend fun authenticatedOwnerSubject() = Result.success(active.value)
            override suspend fun fetchLibrary() =
                Result.success(UserLibrarySnapshot(emptyList(), emptyList()))
        }
        val registry = object : IntegrationRegistry {
            override fun searchProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.SearchProvider>()
            override fun discoveryProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.DiscoveryProvider>()
            override fun metadataProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.MetadataProvider>()
            override fun chapterEvidenceProviders() =
                emptyList<tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider>()
            override fun ratingsProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.RatingsProvider>()
            override fun trackingProviders() = emptyList<tachiyomi.domain.tsuzuki.integration.TrackingProvider>()
            override fun userListProviders() = listOf(provider)
        }
        val scoped = ExternalLibraryRepositoryImpl(database, registry)
        val a = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "account-A")
        val b = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "account-B")
        val template = ExternalLibraryMembership(
            canonicalTitleId = "A-title",
            provider = "mal",
            externalId = "123",
            listKey = "mal:reading",
            status = null,
            remoteStatus = null,
            progress = null,
            score = null,
            syncedAt = 300L,
        )
        scoped.replaceOwned(a, listOf(template.copy(listTitle = "A's list")))
        scoped.replaceOwned(b, listOf(template.copy(listTitle = "B's list")))

        scoped.observeAll().first().single().listTitle shouldBe "A's list"
        active.value = "account-B"
        scoped.observeAll().first().single().listTitle shouldBe "B's list"
        active.value = "account-A"
        scoped.observeAll().first().single().listTitle shouldBe "A's list"
        scoped.clearProvider("mal")
        scoped.observeAll().first().single().listTitle shouldBe "A's list"
    }

    @Test
    fun `invalid foreign owner snapshot is transactional and preserves original account rows`() = runBlocking {
        val account = VerifiedExternalLibraryOwner.fromAuthenticatedIdentity("mal", "owner-A")
        val source = ExternalLibraryMembership(
            canonicalTitleId = "A-title",
            provider = "mal",
            externalId = "123",
            listKey = "mal:reading",
            status = null,
            remoteStatus = null,
            progress = null,
            score = null,
            syncedAt = 300L,
        )
        repository.replaceOwned(account, listOf(source))
        shouldThrow<IllegalArgumentException> {
            repository.replaceOwned(account, listOf(source.copy(provider = "kitsu")))
        }
        database.tsuzuki_external_library_membershipsQueries
            .getTsuzukiScopedExternalLibraryMemberships("mal", account.ownerKey)
            .awaitAsList().single().external_id shouldBe "123"
    }

    @Test
    fun `unverified B refresh cannot overwrite A private membership`() = runBlocking {
        shouldThrow<IllegalStateException> {
            repository.replaceProvider(
                "mal",
                listOf(
                    ExternalLibraryMembership(
                        canonicalTitleId = "A-title",
                        provider = "mal",
                        externalId = "777",
                        listKey = "mal:reading",
                        status = null,
                        remoteStatus = null,
                        progress = null,
                        score = null,
                        syncedAt = 200L,
                    ),
                ),
            )
        }
        val stored = database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships().awaitAsList()
        stored.single().external_id shouldBe "123"
        stored.single().synced_at shouldBe 100L
    }
}
