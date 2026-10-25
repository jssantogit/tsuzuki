package tachiyomi.data.tsuzuki

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import tachiyomi.domain.tsuzuki.capability.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationRequest
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files

class MangaDexWorkAssociationRepositoryImplTest {
    private lateinit var driver: SqlDriver
    private lateinit var mutationObserver: SqlDriverMutationObserver
    private lateinit var database: Database
    private lateinit var repository: CanonicalTitleRepository
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    private val workId = "12345678-1234-1234-1234-123456789abc"
    private val titleId = "title"
    private val crosswalk = ExternalIdentity(titleId, "myanimelist", "501", true, 10L)
    private val enabledSettings = IntegrationSettings(
        integrationId = IntegrationId("mangadex"),
        enabled = true,
        configJson = """{"chapter_inventory":true}""",
        updatedAt = 20L,
    )

    @BeforeEach
    fun setUp(): Unit = runBlocking<Unit> {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-mangadex-association")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        MangaDexWorkAssociationRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())
        mutationObserver = SqlDriverMutationObserver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        driver = mutationObserver.driver
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
        seedTitle(titleId)
        seedIdentity(crosswalk)
        repository = CanonicalTitleRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown(): Unit {
        if (::driver.isInitialized) driver.close()
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `explicitly enabled setting and exact crosswalk associate idempotently`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)

        repository.associateMangaDexWorkIfCurrent(request(), isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.ASSOCIATED
        repository.getByExternalIdentity("mangadex", workId)?.id shouldBe titleId
        val refreshed = request(
            crosswalks = repository.getExternalIdentities(titleId).filter { it.verified },
        )
        repository.associateMangaDexWorkIfCurrent(refreshed, isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.ALREADY_ASSOCIATED
    }

    @Test
    fun `missing integration setting is refused without inserting an identity`(): Unit = runBlocking<Unit> {
        repository.associateMangaDexWorkIfCurrent(request(), isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `legacy enabled integration with default off capability is refused`(): Unit = runBlocking<Unit> {
        val defaultOff = enabledSettings.copy(configJson = "{}")
        saveSettings(defaultOff)

        repository.associateMangaDexWorkIfCurrent(
            request(settings = defaultOff),
            isAuthorized = { true },
        ) shouldBe MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `disabled integration setting is refused without inserting an identity`(): Unit = runBlocking<Unit> {
        val disabled = enabledSettings.copy(enabled = false)
        saveSettings(disabled)

        repository.associateMangaDexWorkIfCurrent(
            request(settings = disabled),
            isAuthorized = { true },
        ) shouldBe MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `changed persisted settings after preview refuse association`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)
        saveSettings(enabledSettings.copy(updatedAt = 21L))

        repository.associateMangaDexWorkIfCurrent(request(), isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `changed verified crosswalk snapshot refuses association`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)
        seedIdentity(crosswalk.copy(externalId = "502", createdAt = 11L))

        repository.associateMangaDexWorkIfCurrent(request(), isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `existing MangaDex ownership by another title is never overwritten`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)
        seedTitle("other")
        seedIdentity(ExternalIdentity("other", "mangadex", workId, true, 30L))

        repository.associateMangaDexWorkIfCurrent(request(), isAuthorized = { true }) shouldBe
            MangaDexWorkAssociationResult.REFUSED
        repository.getByExternalIdentity("mangadex", workId)?.id shouldBe "other"
    }

    @Test
    fun `revocation during transaction rolls back the MangaDex identity insert`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)
        var authorized = true
        mutationObserver.afterMangaDexIdentityInsert = { authorized = false }

        repository.associateMangaDexWorkIfCurrent(
            request(),
            isAuthorized = { authorized },
        ) shouldBe MangaDexWorkAssociationResult.REFUSED

        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    @Test
    fun `cancellation during transaction rolls back the MangaDex identity insert`(): Unit = runBlocking<Unit> {
        saveSettings(enabledSettings)
        val associationJob = Job()
        mutationObserver.afterMangaDexIdentityInsert = { associationJob.cancel() }

        val result = runCatching {
            withContext(associationJob) {
                repository.associateMangaDexWorkIfCurrent(request()) {
                    true
                }
            }
        }

        (result.exceptionOrNull() is CancellationException) shouldBe true
        repository.getByExternalIdentity("mangadex", workId) shouldBe null
    }

    private fun request(
        settings: IntegrationSettings = enabledSettings,
        crosswalks: List<ExternalIdentity> = listOf(crosswalk),
    ) = MangaDexWorkAssociationRequest(
        canonicalTitleId = titleId,
        mangaDexWorkId = workId,
        expectedIntegrationSettings = settings,
        expectedVerifiedCrosswalks = crosswalks,
    )

    private suspend fun saveSettings(settings: IntegrationSettings) {
        database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
            integrationId = settings.integrationId.value,
            enabled = settings.enabled,
            configJson = settings.configJson,
            updatedAt = settings.updatedAt,
        )
    }

    private suspend fun seedTitle(id: String) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = id,
            displayTitle = id,
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private suspend fun seedIdentity(identity: ExternalIdentity) {
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = identity.canonicalTitleId,
            provider = identity.provider,
            externalId = identity.externalId,
            verified = identity.verified,
            createdAt = identity.createdAt,
        )
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    private class SqlDriverMutationObserver(delegate: SqlDriver) {
        var afterMangaDexIdentityInsert: (() -> Unit)? = null

        val driver = Proxy.newProxyInstance(
            SqlDriver::class.java.classLoader,
            arrayOf(SqlDriver::class.java),
        ) { _, method, arguments ->
            val result = try {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            val sql = arguments?.getOrNull(1) as? String
            if (
                method.name in setOf("execute", "executeQuery") &&
                sql?.contains("INSERT INTO tsuzuki_external_identities") == true
            ) {
                afterMangaDexIdentityInsert?.invoke()
            }
            result
        } as SqlDriver
    }
}
