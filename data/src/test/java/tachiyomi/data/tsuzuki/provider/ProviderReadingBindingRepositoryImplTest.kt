package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
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
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import java.nio.file.Files

class ProviderReadingBindingRepositoryImplTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ProviderReadingBindingRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-provider-binding-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        ProviderReadingBindingRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())

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
            id = "title",
            displayTitle = "Dandadan",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository = ProviderReadingBindingRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `upsert get and unavailable state preserve provider neutral identity`() = runBlocking<Unit> {
        val original = binding()

        repository.upsert(original)

        repository.get("title", ProviderId("org.example.reader"), "en") shouldBe original
        repository.getByTitle("title") shouldBe listOf(original)

        repository.markUnavailable("binding", 200L)

        repository.get("title", ProviderId("org.example.reader"), "en") shouldBe
            original.copy(
                availability = ProviderBindingAvailability.UNAVAILABLE,
                updatedAt = 200L,
            )
    }

    @Test
    fun `null facet remains distinct from named facets without fake addon identity`() = runBlocking<Unit> {
        val root = binding().copy(
            id = "root",
            ref = ProviderBindingRef(
                providerId = ProviderId("org.example.reader"),
                facetId = null,
                externalWorkId = "work-root",
            ),
        )
        val en = binding().copy(id = "en")

        repository.upsert(root)
        repository.upsert(en)

        repository.get("title", ProviderId("org.example.reader"), null) shouldBe root
        repository.get("title", ProviderId("org.example.reader"), "en") shouldBe en
        repository.getByTitle("title").associateBy { it.id } shouldBe
            mapOf(
                "en" to en,
                "root" to root,
            )
    }

    private fun binding() = ProviderReadingBinding(
        id = "binding",
        canonicalTitleId = "title",
        ref = ProviderBindingRef(
            providerId = ProviderId("org.example.reader"),
            facetId = "en",
            externalWorkId = "work-42",
        ),
        verification = ProviderBindingVerification.EXACT,
        availability = ProviderBindingAvailability.AVAILABLE,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
