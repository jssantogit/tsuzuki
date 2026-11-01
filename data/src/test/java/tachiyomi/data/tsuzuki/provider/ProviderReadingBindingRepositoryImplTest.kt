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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderSourceEditionQualification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourceAssociation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification
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

    @Test
    fun `edition selection cannot insert a second work into the same title provider facet`() = runBlocking<Unit> {
        val original = binding()
        val competing = original.copy(
            id = "competing-edition",
            ref = original.ref.copy(externalWorkId = "work-colored"),
            verification = ProviderBindingVerification.USER_CONFIRMED,
            updatedAt = 101L,
        )

        repository.insertIfAbsent(original) shouldBe true
        repository.insertIfAbsent(competing) shouldBe false
        repository.get("title", ProviderId("org.example.reader"), "en") shouldBe original
        repository.getByTitle("title") shouldBe listOf(original)
    }

    @Test
    fun `multi HID observations do not replace the preferred edition`() = runBlocking<Unit> {
        val portfolio = ProviderWorkSourceAssociationRepositoryImpl(database)
        val preferred = binding()
        repository.insertIfAbsent(preferred) shouldBe true
        val first = workSource("work-42")
        val second = workSource("work-232")

        portfolio.observe(first) shouldBe true
        portfolio.observe(second) shouldBe true

        portfolio.getByTitle("title").associateBy { it.ref.externalWorkId } shouldBe
            mapOf("work-42" to first, "work-232" to second)
        repository.getByTitle("title") shouldBe listOf(preferred)
        repository.insertIfAbsent(preferred.copy(
            id = "different",
            ref = preferred.ref.copy(externalWorkId = "work-232"),
        )) shouldBe false
    }

    @Test
    fun `same provider HID cannot be stolen by another canonical work or stale refresh`() = runBlocking<Unit> {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "different-title",
            displayTitle = "Unrelated",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val portfolio = ProviderWorkSourceAssociationRepositoryImpl(database)
        val source = workSource("same-hid")
        portfolio.observe(source) shouldBe true

        portfolio.observe(source.copy(canonicalTitleId = "different-title", observedAt = 300L)) shouldBe false
        portfolio.observe(source.copy(
            workVerification = ProviderWorkVerification.AUTHOR_CONFLICT,
            observedAt = 99L,
        )) shouldBe true
        portfolio.get(source.ref) shouldBe source

        val changed = source.copy(
            workVerification = ProviderWorkVerification.SOURCE_DETAIL_UNVERIFIED,
            observedAt = 301L,
        )
        portfolio.observe(changed) shouldBe true
        portfolio.get(source.ref) shouldBe changed
        portfolio.getByTitle("different-title") shouldBe emptyList()
    }

    @Test
    fun `shadow association cannot take a HID already preferred by another canonical work`() = runBlocking<Unit> {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "other-title",
            displayTitle = "Different Work",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        val foreign = binding().copy(
            id = "foreign-preferred",
            canonicalTitleId = "other-title",
            ref = binding().ref.copy(externalWorkId = "foreign-hid"),
        )
        repository.insertIfAbsent(foreign) shouldBe true
        val portfolio = ProviderWorkSourceAssociationRepositoryImpl(database)

        portfolio.observe(workSource("foreign-hid")) shouldBe false
        portfolio.get(foreign.ref) shouldBe null
        portfolio.getByTitle("title") shouldBe emptyList()
        repository.getByTitle("other-title") shouldBe listOf(foreign)
    }

    private fun workSource(hid: String) = ProviderWorkSourceAssociation(
        canonicalTitleId = "title",
        ref = ProviderBindingRef(
            providerId = ProviderId("org.example.reader"),
            facetId = "en",
            externalWorkId = hid,
        ),
        workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
        editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
        observedAt = 100L,
    )

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
