package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllFamilyCatalogTest {
    private fun entry(
        id: String,
        repository: String = "repo.one",
        status: ProviderRepositoryEntryStatus = ProviderRepositoryEntryStatus.AVAILABLE,
    ) =
        ProviderRepositoryCatalogEntry(
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

    @Test
    fun `one MangaAll install action requires exactly both active identities`() {
        val active = MangaAllFamilyCatalog.activeSourceProviderIds
        MangaAllFamilyCatalog.isActiveInstallSet(active) shouldBe true
        MangaAllFamilyCatalog.isActiveInstallSet(active.reversed()) shouldBe true
        MangaAllFamilyCatalog.isActiveInstallSet(active.take(1)) shouldBe false
        MangaAllFamilyCatalog.isActiveInstallSet(active + active.first()) shouldBe false
        MangaAllFamilyCatalog.isActiveInstallSet(
            listOf("app.tsuzuki.mangaall.mangadex", "app.tsuzuki.mangaall.mangaball"),
        ) shouldBe false
        MangaAllFamilyCatalog.isActiveInstallSet(emptyList()) shouldBe false
    }

    @Test
    fun `paused MangaBall must not block the active MangaDex and MangaDot family installation`() {
        val active = listOf(
            "app.tsuzuki.mangaall.mangadex",
            "app.tsuzuki.mangaall.mangadot",
        )
        MangaAllFamilyCatalog.requireExactInstallSet(active)

        val onlyActive = MangaAllFamilyCatalog.discoverGroups(active.map { entry(it) }).single()
        onlyActive.isComplete shouldBe true
        onlyActive.canInstall shouldBe true
        onlyActive.installableIds shouldBe active

        // A repository may still carry a previously reviewed but paused source.
        // Never install it as part of the active family.
        val withPaused = MangaAllFamilyCatalog.discoverGroups(
            MangaAllFamilyCatalog.sourceProviderIds.map { entry(it) },
        ).single()
        withPaused.isComplete shouldBe true
        withPaused.installableIds shouldBe active

        // Paused MangaBall must not substitute for either active source.
        val missingDot = MangaAllFamilyCatalog.discoverGroups(
            listOf("app.tsuzuki.mangaall.mangadex", "app.tsuzuki.mangaall.mangaball")
                .map { entry(it) },
        ).single()
        missingDot.isComplete shouldBe false
        missingDot.canInstall shouldBe false
    }

    @Test
    fun `MangaAll groups only active source identities from one repository`() {
        val entries = MangaAllFamilyCatalog.sourceProviderIds.map { entry(it) } +
            entry("app.tsuzuki.mangafire")
        val groups = MangaAllFamilyCatalog.discoverGroups(entries)
        groups.size shouldBe 1
        val family = groups.single()
        family.familyId shouldBe "app.tsuzuki.mangaall"
        family.repositoryId shouldBe "repo.one"
        family.entries.map { it.descriptor.providerId } shouldBe MangaAllFamilyCatalog.activeSourceProviderIds
        family.canInstall shouldBe true
        family.isComplete shouldBe true
        MangaAllFamilyCatalog.ordinaryEntries(entries).map { it.descriptor.providerId } shouldBe listOf(
            "app.tsuzuki.mangafire",
        )
    }

    @Test
    fun `missing or revoked MangaAll source fails closed without pretending complete installation`() {
        val ids = MangaAllFamilyCatalog.sourceProviderIds
        val incomplete = MangaAllFamilyCatalog.discoverGroups(ids.take(2).map { entry(it) }).single()
        incomplete.isComplete shouldBe false
        incomplete.canInstall shouldBe false
        val revoked = MangaAllFamilyCatalog.discoverGroups(
            ids.mapIndexed { index, id ->
                val status = if (index == 2) {
                    ProviderRepositoryEntryStatus.REVOKED
                } else {
                    ProviderRepositoryEntryStatus.AVAILABLE
                }
                entry(id, status = status)
            },
        ).single()
        revoked.isComplete shouldBe true
        revoked.canInstall shouldBe false
        revoked.entries.size shouldBe 2
    }

    @Test
    fun `trusted family cannot mix repository origins or accept prefix impersonators`() {
        val ids = MangaAllFamilyCatalog.sourceProviderIds
        val groups = MangaAllFamilyCatalog.discoverGroups(
            listOf(
                entry(ids[0], "repo.one"),
                entry(ids[1], "repo.one"),
                entry(ids[2], "repo.two"),
                entry("app.tsuzuki.mangaall.evil", "repo.one"),
            ),
        )
        groups.size shouldBe 2
        groups.none { it.isComplete } shouldBe true
        MangaAllFamilyCatalog.ordinaryEntries(listOf(entry("app.tsuzuki.mangaall.evil"))).size shouldBe 1
        shouldThrow<IllegalArgumentException> {
            MangaAllFamilyCatalog.requireExactInstallSet(ids.take(2))
        }
        shouldThrow<IllegalArgumentException> {
            MangaAllFamilyCatalog.requireExactInstallSet(ids + "app.tsuzuki.mangaall.evil")
        }
    }

    @Test
    fun `already installed sources don't trigger redundant installs but upgrades remain visible`() {
        val ids = MangaAllFamilyCatalog.sourceProviderIds
        val groups = MangaAllFamilyCatalog.discoverGroups(
            ids.mapIndexed { index, id ->
                val status = if (index == 2) {
                    ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
                } else {
                    ProviderRepositoryEntryStatus.INSTALLED
                }
                entry(id, status = status)
            },
        )
        val family = groups.single()
        family.canInstall shouldBe true
        family.installableIds shouldBe listOf(ids[2])
        val complete = MangaAllFamilyCatalog.discoverGroups(
            ids.map { entry(it, status = ProviderRepositoryEntryStatus.INSTALLED) },
        ).single()
        complete.canInstall shouldBe false
        complete.isComplete shouldBe true
    }
}
