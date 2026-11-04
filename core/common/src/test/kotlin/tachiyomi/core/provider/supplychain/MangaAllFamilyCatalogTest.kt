package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllFamilyCatalogTest {

    private fun entry(
        id: String,
        repository: String = "repo.one",
        status: ProviderRepositoryEntryStatus = ProviderRepositoryEntryStatus.AVAILABLE,
    ): ProviderRepositoryCatalogEntry = ProviderRepositoryCatalogEntry(
        repositoryId = repository,
        descriptor = ProviderArtifactDescriptor(
            providerId = id,
            versionName = "1.0",
            versionCode = 1L,
            artifactUrl = "https://repo.example/$id.tsz",
            sha256 = "a".repeat(64),
            minHostApi = 1,
        ),
        installedVersionCode = null,
        status = status,
    )

    private val active = listOf(
        "app.tsuzuki.mangaall.mangadex",
        "app.tsuzuki.mangaall.mangadot",
        "app.tsuzuki.mangafire",
    )
    private val paused = "app.tsuzuki.mangaall.mangaball"

    @Test
    fun `MangaAll includes working standalone MangaFire without changing its provider ID`() {
        MangaAllFamilyCatalog.activeSourceProviderIds shouldBe active
        MangaAllFamilyCatalog.contains("app.tsuzuki.mangafire") shouldBe true
        MangaAllFamilyCatalog.contains("app.tsuzuki.mangaall.mangafire") shouldBe false
        MangaAllFamilyCatalog.contains("app.tsuzuki.mangafire.evil") shouldBe false
        MangaAllFamilyCatalog.isActiveInstallSet(active.reversed()) shouldBe true
        MangaAllFamilyCatalog.isActiveInstallSet(active.take(2)) shouldBe false
        MangaAllFamilyCatalog.isActiveInstallSet(active + active.first()) shouldBe false
    }

    @Test
    fun `paused MangaBall is never required or installed by MangaAll`() {
        val family = MangaAllFamilyCatalog.discoverGroups((active + paused).map(::entry)).single()
        family.isComplete shouldBe true
        family.canInstall shouldBe true
        family.entries.map { it.descriptor.providerId } shouldBe active
        family.installableIds shouldBe active
        MangaAllFamilyCatalog.isActiveInstallSet(listOf(active[0], active[1], paused)) shouldBe false
        shouldThrow<IllegalArgumentException> {
            MangaAllFamilyCatalog.requireExactInstallSet(active + paused)
        }
    }

    @Test
    fun `installed MangaFire is reused while missing MangaAll sources remain installable`() {
        val entries = active.map { id ->
            entry(
                id,
                status = if (id == "app.tsuzuki.mangafire") {
                    ProviderRepositoryEntryStatus.INSTALLED
                } else {
                    ProviderRepositoryEntryStatus.AVAILABLE
                },
            )
        }
        val family = MangaAllFamilyCatalog.discoverGroups(entries).single()
        family.isComplete shouldBe true
        family.canInstall shouldBe true
        family.installableIds shouldBe active.take(2)
        MangaAllFamilyCatalog.discoverGroups(
            active.map { entry(it, status = ProviderRepositoryEntryStatus.INSTALLED) },
        ).single().canInstall shouldBe false
    }

    @Test
    fun `missing or revoked sources never look like a complete installable family`() {
        val incomplete = MangaAllFamilyCatalog.discoverGroups(active.take(2).map(::entry)).single()
        incomplete.isComplete shouldBe false
        incomplete.canInstall shouldBe false
        val revoked = MangaAllFamilyCatalog.discoverGroups(
            active.map { id ->
                entry(
                    id,
                    status = if (id == "app.tsuzuki.mangafire") {
                        ProviderRepositoryEntryStatus.REVOKED
                    } else {
                        ProviderRepositoryEntryStatus.AVAILABLE
                    },
                )
            },
        ).single()
        revoked.isComplete shouldBe true
        revoked.canInstall shouldBe false
    }

    @Test
    fun `family never mixes repository trust origins or includes prefix impersonators`() {
        val entries = listOf(
            entry(active[0], "repo.one"),
            entry(active[1], "repo.one"),
            entry(active[2], "repo.two"),
            entry(paused, "repo.one"),
            entry("app.tsuzuki.mangaall.evil", "repo.one"),
        )
        val groups = MangaAllFamilyCatalog.discoverGroups(entries)
        groups.size shouldBe 2
        groups.none { it.isComplete } shouldBe true
        MangaAllFamilyCatalog.ordinaryEntries(entries).map { it.descriptor.providerId } shouldBe
            listOf("app.tsuzuki.mangaall.evil")
        shouldThrow<IllegalArgumentException> {
            MangaAllFamilyCatalog.requireExactInstallSet(active.take(2))
        }
    }
}
