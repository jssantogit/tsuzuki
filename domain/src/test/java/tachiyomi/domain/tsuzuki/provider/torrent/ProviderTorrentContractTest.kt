package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderPage

class ProviderTorrentContractTest {

    private val chapter12 = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        ),
        volume = null,
        preferredLanguages = emptySet(),
    )

    @Test
    fun `torrent candidate requires a resolvable identity and safe file metadata`() {
        shouldThrow<IllegalArgumentException> {
            TorrentCandidate(
                infoHash = null,
                magnetUri = null,
                torrentUrl = null,
                displayName = "Pack",
            )
        }

        shouldThrow<IllegalArgumentException> {
            TorrentCandidateFile(
                index = 0,
                path = "../chapter-012.cbz",
                sizeBytes = 1024,
            )
        }

        TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = null,
            torrentUrl = null,
            displayName = "Pack",
            files = listOf(
                TorrentCandidateFile(
                    index = 0,
                    path = "pack/chapter-012.cbz",
                    sizeBytes = 1024,
                ),
            ),
        ).files?.single()?.path shouldBe "pack/chapter-012.cbz"
    }

    @Test
    fun `chapter mapper selects one explicit matching chapter file`() {
        val candidate = candidate(
            files = listOf(
                file(0, "pack/chapter-011.cbz"),
                file(1, "pack/chapter-012.cbz"),
                file(2, "pack/chapter-013.cbz"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `chapter mapper uses explicit volume evidence to disambiguate equal chapter numbers`() {
        val request = chapter12.copy(volume = 2)
        val candidate = candidate(
            files = listOf(
                file(0, "pack/Vol. 1 Ch. 12.cbz"),
                file(1, "pack/Vol. 2 Ch. 12.cbz"),
            ),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `chapter mapper fails closed when more than one file remains plausible`() {
        val candidate = candidate(
            files = listOf(
                file(0, "release-a/chapter-012.cbz"),
                file(1, "release-b/chapter-012.zip"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Ambiguous(candidate.files!!)
    }

    @Test
    fun `chapter mapper does not treat arbitrary title numbers as chapter identity`() {
        val candidate = candidate(
            files = listOf(
                file(0, "Series Name 12.cbz"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `acquisition policy keeps debrid preference separate from direct p2p consent`() {
        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = true,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(
                TorrentAcquisitionRoute.DEBRID,
                TorrentAcquisitionRoute.DIRECT_P2P,
            ),
        )

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = true,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(TorrentAcquisitionRoute.DEBRID),
        )

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = false,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.DirectP2pConsentRequired

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            hasUsableDebrid = false,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Unavailable
    }

    @Test
    fun `p2p only never bypasses the explicit direct p2p preference`() {
        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            hasUsableDebrid = true,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.DirectP2pConsentRequired

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            hasUsableDebrid = false,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(TorrentAcquisitionRoute.DIRECT_P2P),
        )
    }

    @Test
    fun `acquisition router tries debrid first and falls back to p2p only when policy permits`() = runTest {
        val candidate = candidate(listOf(file(0, "pack/chapter-012.cbz")))
        val selected = candidate.files!!.single()
        val debrid = RecordingAcquisitionBackend(
            TorrentBackendResult.Failure(TorrentAcquisitionFailure.UNAVAILABLE),
        )
        val p2p = RecordingAcquisitionBackend(
            TorrentBackendResult.Success(
                TorrentReadableResource.LocalArchive(
                    uri = "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz",
                    format = TorrentArchiveFormat.CBZ,
                ),
            ),
        )
        val router = TorrentAcquisitionRouter(
            debrid = debrid,
            directP2p = p2p,
        )

        router.acquire(
            candidate = candidate,
            selectedFile = selected,
            decision = TorrentAcquisitionDecision.Routes(
                listOf(
                    TorrentAcquisitionRoute.DEBRID,
                    TorrentAcquisitionRoute.DIRECT_P2P,
                ),
            ),
        ) shouldBe TorrentAcquisitionResult.Success(
            route = TorrentAcquisitionRoute.DIRECT_P2P,
            file = selected,
            resource = TorrentReadableResource.LocalArchive(
                uri = "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz",
                format = TorrentArchiveFormat.CBZ,
            ),
        )
        debrid.requests shouldBe listOf(candidate to selected)
        p2p.requests shouldBe listOf(candidate to selected)
    }

    @Test
    fun `acquisition router never touches p2p when policy contains only debrid`() = runTest {
        val candidate = candidate(listOf(file(0, "pack/chapter-012.cbz")))
        val selected = candidate.files!!.single()
        val debrid = RecordingAcquisitionBackend(
            TorrentBackendResult.Failure(TorrentAcquisitionFailure.AUTH_REQUIRED),
        )
        val p2p = RecordingAcquisitionBackend(
            TorrentBackendResult.Failure(TorrentAcquisitionFailure.UNAVAILABLE),
        )
        val router = TorrentAcquisitionRouter(debrid, p2p)

        router.acquire(
            candidate = candidate,
            selectedFile = selected,
            decision = TorrentAcquisitionDecision.Routes(
                listOf(TorrentAcquisitionRoute.DEBRID),
            ),
        ) shouldBe TorrentAcquisitionResult.Failure(
            TorrentAcquisitionFailure.AUTH_REQUIRED,
        )
        debrid.requests.size shouldBe 1
        p2p.requests shouldBe emptyList()
    }

    @Test
    fun `acquisition router rejects a selected file that is not part of the candidate`() = runTest {
        val candidate = candidate(listOf(file(0, "pack/chapter-012.cbz")))
        val backend = RecordingAcquisitionBackend(
            TorrentBackendResult.Failure(TorrentAcquisitionFailure.UNAVAILABLE),
        )
        val router = TorrentAcquisitionRouter(backend, backend)

        shouldThrow<IllegalArgumentException> {
            router.acquire(
                candidate = candidate,
                selectedFile = file(99, "pack/chapter-012.cbz"),
                decision = TorrentAcquisitionDecision.Routes(
                    listOf(TorrentAcquisitionRoute.DIRECT_P2P),
                ),
            )
        }
        backend.requests shouldBe emptyList()
    }

    @Test
    fun `readable resources fail closed on unsafe HTTP and unmanaged local file authority`() {
        shouldThrow<IllegalArgumentException> {
            TorrentReadableResource.HttpFile(
                url = "file:///sdcard/chapter.cbz",
                allowedOrigins = setOf("https://cdn.example"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            TorrentReadableResource.HttpFile(
                url = "https://cdn.example/chapter.cbz",
                headers = mapOf("Host" to "evil.example"),
                allowedOrigins = setOf("https://cdn.example"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            TorrentReadableResource.LocalArchive(
                uri = "file:///data/user/0/app.tsuzuki/cache/chapter.cbz",
                format = TorrentArchiveFormat.CBZ,
            )
        }

        TorrentReadableResource.HttpFile(
            url = "https://cdn.example/chapter.cbz",
            headers = mapOf("Authorization" to "Bearer opaque"),
            allowedOrigins = setOf("https://cdn.example"),
        ).url shouldBe "https://cdn.example/chapter.cbz"
    }

    @Test
    fun `provider requests keep torrent discovery and acquisition intent capability scoped`() {
        val search = TorrentSearchRequest(
            titles = listOf("Example title", "Example alias"),
            preferredLanguages = setOf("en", "pt-BR"),
            cursor = ProviderCursor("next-page"),
        )
        search.titles shouldBe listOf("Example title", "Example alias")
        search.cursor?.value shouldBe "next-page"

        val candidate = candidate(listOf(file(3, "pack/chapter-012.cbz")))
        val selected = candidate.files!!.single()
        val request = TorrentAcquisitionRequest(
            operationId = "read:canonical-12",
            candidate = candidate,
            selectedFile = selected,
        )
        request.selectedFile shouldBe selected

        shouldThrow<IllegalArgumentException> {
            TorrentAcquisitionRequest(
                operationId = "read:canonical-12",
                candidate = candidate,
                selectedFile = file(99, "pack/chapter-012.cbz"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            TorrentAcquisitionRequest(
                operationId = "invalid operation id",
                candidate = candidate,
                selectedFile = selected,
            )
        }
    }

    @Test
    fun `provider acquisition states preserve debrid HTTP and p2p local resource boundaries`() {
        val http = TorrentReadableResource.HttpFile(
            url = "https://cdn.example/chapter-012.cbz",
            allowedOrigins = setOf("https://cdn.example"),
        )
        DebridResolveState.Ready(http).resource shouldBe http
        DebridResolveState.Pending("debrid-job-12").jobId shouldBe "debrid-job-12"

        val local = TorrentReadableResource.LocalArchive(
            uri = "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz",
            format = TorrentArchiveFormat.CBZ,
        )
        P2pAcquireState.Ready(local).resource shouldBe local
        P2pAcquireState.Pending("p2p-job-12").jobId shouldBe "p2p-job-12"

        shouldThrow<IllegalArgumentException> {
            DebridResolveState.Pending("")
        }
        shouldThrow<IllegalArgumentException> {
            P2pAcquireState.Pending(" ".repeat(3))
        }
    }

    @Test
    fun `provider acquisition gateways remain selected by capability rather than vendor type`() = runTest {
        val providerId = ProviderId("org.example.provider")
        val expected = ProviderCallResult.Success(
            ProviderPage<TorrentCandidate>(
                items = emptyList(),
                nextCursor = null,
            ),
        )
        val search = TorrentSearchGateway { requestedProviderId, request ->
            requestedProviderId shouldBe providerId
            request.titles shouldBe listOf("Example")
            expected
        }

        search.search(
            providerId = providerId,
            request = TorrentSearchRequest(titles = listOf("Example")),
        ) shouldBe expected
    }

    private class RecordingAcquisitionBackend(
        private val result: TorrentBackendResult,
    ) : TorrentAcquisitionBackend {
        val requests = mutableListOf<Pair<TorrentCandidate, TorrentCandidateFile>>()

        override suspend fun acquire(
            candidate: TorrentCandidate,
            file: TorrentCandidateFile,
        ): TorrentBackendResult {
            requests += candidate to file
            return result
        }
    }

    private fun candidate(files: List<TorrentCandidateFile>) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        torrentUrl = null,
        displayName = "Example pack",
        languages = setOf("en"),
        files = files,
    )

    private fun file(index: Int, path: String) = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = 1024,
    )
}
