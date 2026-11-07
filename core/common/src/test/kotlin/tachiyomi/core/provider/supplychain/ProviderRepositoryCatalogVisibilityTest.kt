package tachiyomi.core.provider.supplychain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderRepositoryCatalogVisibilityTest {

    @Test
    fun `legacy MangaAll adapters remain discoverable before the unified provider is signed`() {
        val dex = entry("app.tsuzuki.providers", "app.tsuzuki.mangaall.mangadex")
        val dot = entry("app.tsuzuki.providers", "app.tsuzuki.mangaall.mangadot")
        val fire = entry("app.tsuzuki.providers", "app.tsuzuki.mangafire")

        visibleProviderRepositoryEntries(listOf(dex, dot, fire))
            .shouldContainExactly(dex, dot, fire)
    }

    @Test
    fun `one signed MangaAll hides only legacy discovery choices in the same repository`() {
        val repo = "app.tsuzuki.providers"
        val unified = entry(repo, "app.tsuzuki.mangaall")
        val dex = entry(repo, "app.tsuzuki.mangaall.mangadex")
        val dot = entry(repo, "app.tsuzuki.mangaall.mangadot")
        val fire = entry(repo, "app.tsuzuki.mangafire")
        val external = entry("independent.repo", "app.tsuzuki.mangaall.mangadex")

        visibleProviderRepositoryEntries(listOf(dex, fire, unified, external, dot))
            .shouldContainExactly(fire, unified, external)
        // Display-only migration: never mutate the signed index or installed packages.
        listOf(dex, fire, unified, external, dot).size shouldBe 5
    }

    @Test
    fun `uninstallable legacy packages are not hidden if the unified release is unavailable`() {
        val repo = "app.tsuzuki.providers"
        val dex = entry(repo, "app.tsuzuki.mangaall.mangadex")
        for (status in listOf(
            ProviderRepositoryEntryStatus.REVOKED,
            ProviderRepositoryEntryStatus.INCOMPATIBLE,
            ProviderRepositoryEntryStatus.ORIGIN_CONFLICT,
        )) {
            val unified = entry(repo, "app.tsuzuki.mangaall", status)
            visibleProviderRepositoryEntries(listOf(dex, unified))
                .shouldContainExactly(dex, unified)
        }
    }

    @Test
    fun `signed MangaAll updates still suppress obsolete source adapters`() {
        val repo = "app.tsuzuki.providers"
        val dex = entry(repo, "app.tsuzuki.mangaall.mangadex")
        val unified = entry(repo, "app.tsuzuki.mangaall", ProviderRepositoryEntryStatus.UPDATE_AVAILABLE)
        visibleProviderRepositoryEntries(listOf(dex, unified))
            .shouldContainExactly(unified)
    }

    private fun entry(
        repositoryId: String,
        providerId: String,
        status: ProviderRepositoryEntryStatus = ProviderRepositoryEntryStatus.AVAILABLE,
    ) = ProviderRepositoryCatalogEntry(
        repositoryId = repositoryId,
        descriptor = ProviderArtifactDescriptor(
            providerId = providerId,
            versionName = "1.0.0",
            versionCode = 1L,
            artifactUrl = "https://example.org/$providerId.tsz",
            sha256 = "a".repeat(64),
            minHostApi = 1,
        ),
        installedVersionCode = null,
        status = status,
    )
}
