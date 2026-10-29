package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class RefreshProviderTorrentInventoryTest {
    private val providerId = ProviderId("app.tsuzuki.nyaa")
    private val title = CanonicalTitle("tokyo", "Tokyo Ghoul", CanonicalIdentityState.RESOLVED, 1, 1)

    @Test
    fun enabledTorrentSourceSuppliesOnlyShadowReleaseEvidence() = runTest {
        val titles = mockk<CanonicalTitleRepository>()
        coEvery { titles.getById("tokyo") } returns title
        val ledger = mockk<InventoryEvidenceRepository>(relaxed = true)
        val provider = registration()
        val registry = DefaultProviderRegistry(registrations = { listOf(provider) })
        var searches = 0
        val search = TorrentSearchGateway { id, request ->
            id shouldBe providerId
            request.titles shouldBe listOf("Tokyo Ghoul")
            request.chapterNumber shouldBe null
            searches++
            ProviderCallResult.Success(
                ProviderPage(
                    items = listOf(
                        TorrentCandidate(
                            infoHash = "0123456789abcdef0123456789abcdef01234567",
                            magnetUri = null,
                            torrentUrl = null,
                            displayName = "Tokyo Ghoul Manga - Complete",
                            files = listOf(
                                TorrentCandidateFile(0, "Tokyo Ghoul Manga/Chapter 01/001.jpg", 1024),
                                TorrentCandidateFile(1, "Tokyo Ghoul Manga/Chapter 01/002.png", 1024),
                            ),
                        ),
                    ),
                    nextCursor = null,
                ),
            )
        }
        val refresh = RefreshProviderTorrentInventory(titles, registry, search, ledger)

        refresh.hasEnabledSources() shouldBe true
        val result = refresh.execute("tokyo", observedAt = 1000)
        result shouldBe InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, 1, 1)
        searches shouldBe 1
        coVerify(exactly = 1) {
            ledger.saveBatch(
                match {
                    it.claims.size == 1 &&
                        it.claims.single().derivation == InventoryDerivation.RELEASE_RECORD &&
                        !it.scope.editionIdentityVerified &&
                        !it.scope.workIdentityVerified
                },
            )
        }
    }

    @Test
    fun revokedSourceDiscardsLateResultsBeforeWritingEvidence() = runTest {
        val titles = mockk<CanonicalTitleRepository>()
        coEvery { titles.getById("tokyo") } returns title
        val ledger = mockk<InventoryEvidenceRepository>(relaxed = true)
        val provider = registration()
        val registry = DefaultProviderRegistry(registrations = { listOf(provider) })
        var authorized = true
        val search = TorrentSearchGateway { _, _ ->
            authorized = false
            ProviderCallResult.Success(
                ProviderPage(
                    items = listOf(
                        TorrentCandidate(
                            infoHash = "0123456789abcdef0123456789abcdef01234567",
                            magnetUri = null,
                            torrentUrl = null,
                            displayName = "Tokyo Ghoul",
                            files = listOf(TorrentCandidateFile(0, "Tokyo Ghoul Manga/Chapter 01/001.jpg", 1024)),
                        ),
                    ),
                    nextCursor = null,
                ),
            )
        }

        RefreshProviderTorrentInventory(titles, registry, search, ledger)
            .execute("tokyo", authorized = { authorized }, observedAt = 1000)
            .stop shouldBe InventoryRefreshStop.DISABLED
        coVerify(exactly = 0) { ledger.saveBatch(any()) }
    }

    @Test
    fun disabledTorrentProviderNeverStartsSearchOrSavesEvidence() = runTest {
        val titles = mockk<CanonicalTitleRepository>()
        coEvery { titles.getById("tokyo") } returns title
        val ledger = mockk<InventoryEvidenceRepository>(relaxed = true)
        val registry =
            DefaultProviderRegistry(registrations = { listOf(registration(ProviderLifecycleStatus.DISABLED)) })
        var searches = 0
        val refresh = RefreshProviderTorrentInventory(
            titles,
            registry,
            TorrentSearchGateway { _, _ ->
                searches++
                error("Disabled provider must not search")
            },
            ledger,
        )

        refresh.hasEnabledSources() shouldBe false
        refresh.execute("tokyo").stop shouldBe InventoryRefreshStop.DISABLED
        searches shouldBe 0
        coVerify(exactly = 0) { ledger.saveBatch(any()) }
    }

    private fun registration(
        lifecycle: ProviderLifecycleStatus = ProviderLifecycleStatus.ENABLED,
    ) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = providerId,
            name = "Nyaa",
            version = ProviderVersion("0.1.14", 14),
            origin = ProviderOrigin.Repository("repo"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ),
        lifecycleStatus = lifecycle,
    )
}
