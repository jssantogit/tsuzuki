package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestMangaAllChapterSnapshotPermission
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderHostModule

class MangaAllFutureHostBinderPolicyTest {

    private fun manifest(
        id: String = "app.tsuzuki.mangaall",
        code: Long = 11,
        api: Int = 2,
        origin: String = "https://mangadot.net",
        local: Boolean = false,
        grant: Boolean = true,
        storage: Boolean = false,
    ) = ProviderScriptManifest(
        manifestVersion = 1,
        id = id,
        name = "MangaALL future offline",
        version = ProviderManifestVersion("0.6.0-unsigned", code),
        minHostApi = api,
        entrypoint = "main.js",
        capabilities = listOf(ProviderManifestCapability("reading.chapters", 1)),
        permissions = ProviderManifestPermissions(
            network = ProviderManifestNetworkPermission(origins = setOf(origin), localNetwork = local),
            storage = tachiyomi.core.provider.packageformat.ProviderManifestStoragePermission(storage),
            mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(grant),
        ),
    )

    @Test
    fun future_code11_snapshot_module_requires_exact_manifest_scope() {
        val authorized = ProviderHostInvocationPolicy.fromManifest(manifest(), "future-allowed")
        authorized.mangaAllChapterSnapshotEnabled shouldBe true
        (ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in authorized.allowedHostModules()) shouldBe true
        authorized.storageEnabled shouldBe false
        (ProviderHostModule.STORAGE in authorized.allowedHostModules()) shouldBe false

        val denied = listOf(
            manifest(code = 10),
            manifest(id = "foreign.reader"),
            manifest(api = 1),
            manifest(origin = "https://other.example"),
            manifest(local = true),
            manifest(grant = false),
        )
        denied.forEachIndexed { index, candidate ->
            val policy = ProviderHostInvocationPolicy.fromManifest(candidate, "denied-$index")
            policy.mangaAllChapterSnapshotEnabled shouldBe false
            (ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in policy.allowedHostModules()) shouldBe false
        }
    }

    @Test
    fun future_snapshot_and_generic_storage_permissions_are_independent() {
        val normal = ProviderHostInvocationPolicy.fromManifest(
            manifest(code = 10, grant = false, storage = true),
            "storage-only",
        )
        (ProviderHostModule.STORAGE in normal.allowedHostModules()) shouldBe true
        (ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in normal.allowedHostModules()) shouldBe false

        val future = ProviderHostInvocationPolicy.fromManifest(
            manifest(storage = true),
            "future-storage",
        )
        (ProviderHostModule.STORAGE in future.allowedHostModules()) shouldBe true
        (ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in future.allowedHostModules()) shouldBe true
    }

    @Test
    fun hand_constructed_foreign_snapshot_policy_is_rejected() {
        val invalid = runCatching {
            ProviderHostInvocationPolicy(
                providerId = "foreign.reader",
                invocationId = "forged",
                networkOrigins = setOf("https://mangadot.net"),
                mangaAllChapterSnapshotEnabled = true,
            )
        }
        (invalid.exceptionOrNull() is IllegalArgumentException) shouldBe true
        val local = runCatching {
            ProviderHostInvocationPolicy(
                providerId = "app.tsuzuki.mangaall",
                invocationId = "local",
                networkOrigins = setOf("https://mangadot.net"),
                allowLocalNetwork = true,
                mangaAllChapterSnapshotEnabled = true,
            )
        }
        (local.exceptionOrNull() is IllegalArgumentException) shouldBe true
    }
}
