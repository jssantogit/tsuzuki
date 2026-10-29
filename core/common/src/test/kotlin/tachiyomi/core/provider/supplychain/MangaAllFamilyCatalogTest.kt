package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllFamilyCatalogTest {
    private fun entry(id: String, repository: String = "repo.one", status: ProviderRepositoryEntryStatus = ProviderRepositoryEntryStatus.AVAILABLE) =
        ProviderRepositoryCatalogEntry(
            repositoryId = repository,
            descriptor = ProviderArtifactDescriptor(
                providerId = id,
                versionName = "1.0", versionCode = 1L,
                artifactUrl = "https://repo.example/$id.tsz",
                sha256 = "a".repeat(64),
                minHostApi = 1,
            ),
            installedVersionCode = null,
            status = status,
        )

    @Test
    fun `MangaAll groups only three exact source identities from one repository`() {
        val entries = MangaAllFamilyCatalog.sourceProviderIds.map { entry(it) } +
            entry("app.tsuzuki.mangafire")
        val groups = MangaAllFamilyCatalog.discoverGroups(entries)
        groups.size shouldBe 1
        val family = groups.single()
        family.familyId shouldBe "app.tsuzuki.mangaall"
        family.repositoryId shouldBe "repo.one"
        family.entries.map { it.descriptor.providerId } shouldBe MangaAllFamilyCatalog.sourceProviderIds
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
        val revoked = MangaAllFamilyCatalog.discoverGroups(ids.mapIndexed { index, id ->
            entry(id, status = if (index == 1) ProviderRepositoryEntryStatus.REVOKED else ProviderRepositoryEntryStatus.AVAILABLE)
        }).single()
        revoked.isComplete shouldBe true
        revoked.canInstall shouldBe false
        revoked.entries.size shouldBe 3
    }

    @Test
    fun `trusted family cannot mix repository origins or accept prefix impersonators`() {
        val ids = MangaAllFamilyCatalog.sourceProviderIds
        val groups = MangaAllFamilyCatalog.discoverGroups(
            listOf(entry(ids[0], "repo.one"), entry(ids[1], "repo.two"),
                entry(ids[2], "repo.one"), entry("app.tsuzuki.mangaall.evil", "repo.one")),
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
        val groups = MangaAllFamilyCatalog.discoverGroups(ids.mapIndexed { index, id ->
            entry(id, status = if (index == 1) ProviderRepositoryEntryStatus.UPDATE_AVAILABLE else ProviderRepositoryEntryStatus.INSTALLED)
        })
        val family = groups.single()
        family.canInstall shouldBe true
        family.installableIds shouldBe listOf(ids[1])
        val complete = MangaAllFamilyCatalog.discoverGroups(ids.map {
            entry(it, status = ProviderRepositoryEntryStatus.INSTALLED)
        }).single()
        complete.canInstall shouldBe false
        complete.isComplete shouldBe true
    }
}
