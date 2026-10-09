package eu.kanade.tachiyomi.provider.runtime

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TcpEndpoint
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveGateway
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireGateway
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentAcquisitionCoordinator
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentAcquisitionState
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionFailure
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionPreference
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRoute
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterFileMatch
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterMapper
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentChapterRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProviderTorrentAcquisitionAcceptanceTest {

    @Test
    fun canonicalChapter12_acquiresOnlyExactArchiveThroughManagedLoopbackP2p() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-acquisition-acceptance").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val managedFiles = ProviderManagedFileStore(context).apply { clearAll() }
        val seeder = SessionManager()
        val seedPort = 49301
        val leecherPort = 49302
        var engineStarts = 0
        var unrelatedFileCompleted = false

        ProviderTorrentAcceptanceFixtures.startLoopbackSeeder(
            seeder = seeder,
            fixture = fixture,
            port = seedPort,
        )

        val productionEngine = JlibtorrentProviderP2pDownloadEngine(
            metadataResolver = { _, _, _ -> fixture.torrent },
            initialPeers = { listOf(TcpEndpoint("127.0.0.1", seedPort)) },
            sessionParamsFactory = {
                ProviderTorrentAcceptanceFixtures.localOnlyParams(leecherPort)
            },
            downloadTimeoutMs = 30_000L,
            nativeSupport = { true },
        )
        val engine = ProviderP2pDownloadEngine { request, workingDirectory ->
            engineStarts += 1
            productionEngine.download(request, workingDirectory).also { result ->
                if (result is ProviderP2pDownloadResult.Ready) {
                    val downloadRoot = File(
                        workingDirectory,
                        JlibtorrentProviderP2pDownloadEngine.DOWNLOAD_DIRECTORY,
                    )
                    unrelatedFileCompleted = fixture.unselectedFileIndexes.any { index ->
                        val file = File(downloadRoot, fixture.torrent.files().filePath(index))
                        file.isFile && file.length() == fixture.torrent.files().fileSize(index)
                    }
                }
            }
        }
        val jobRoot = File(root, "jobs")
        val jobs = ProviderP2pJobManager(
            root = jobRoot,
            managedFiles = managedFiles,
            engine = engine,
        )
        val activePackage: ScriptProviderPackage =
            ProviderTorrentAcceptanceFixtures.createDirectP2pProviderPackage(
                providerId = PROVIDER_ID.value,
                repositoryId = REPOSITORY_ID,
            )
        val registry = DefaultProviderRegistry(
            registrations = { listOf(registration(activePackage)) },
        )
        val hostFactory = ProviderHostInvocationFactory(
            context = context,
            storageRoot = File(root, "storage"),
            p2pServiceFactory = { providerId -> jobs.service(providerId) },
            managedFiles = managedFiles,
        )
        val executor = ScriptProviderCapabilityExecutor(
            registry = registry,
            packageSource = ScriptProviderPackageSource { requested ->
                activePackage.takeIf { requested == PROVIDER_ID }
            },
            runtimeClient = ProviderRuntimeClient(context, hostFactory),
        )
        val gateway = ScriptProviderTorrentGateway(
            executor = executor,
            managedResources = managedFiles,
        )
        val coordinator = ProviderTorrentAcquisitionCoordinator(
            registry = registry,
            debrid = DebridResolveGateway { _, _ ->
                error("Debrid must not be touched in P2P_ONLY acceptance")
            },
            p2p = gateway,
            directP2pHostAvailable = { true },
        )

        try {
            val candidate = candidate(fixture)
            val match = TorrentChapterMapper().map(
                request = chapter12Request(),
                candidate = candidate,
            )
            assertTrue("canonical chapter 12 must map exactly but was $match", match is TorrentChapterFileMatch.Exact)
            val selected = (match as TorrentChapterFileMatch.Exact).file
            assertEquals(fixture.selectedFileIndex, selected.index)
            assertEquals(fixture.selectedFilePath, selected.path)

            val acquisition = TorrentAcquisitionRequest(
                operationId = "acceptance:canonical-12",
                candidate = candidate,
                selectedFile = selected,
            )
            val first = coordinator.acquire(
                request = acquisition,
                preference = TorrentAcquisitionPreference.P2P_ONLY,
                directP2pAllowed = true,
            )
            assertTrue(
                "first P2P acquisition must be pending but was $first",
                first is ProviderTorrentAcquisitionState.Pending,
            )

            val ready = withTimeout(35_000L) {
                while (true) {
                    when (
                        val state = coordinator.acquire(
                            request = acquisition,
                            preference = TorrentAcquisitionPreference.P2P_ONLY,
                            directP2pAllowed = true,
                        )
                    ) {
                        is ProviderTorrentAcquisitionState.Ready -> return@withTimeout state
                        is ProviderTorrentAcquisitionState.Failure ->
                            error("loopback P2P failed unexpectedly: ${state.reason}")
                        is ProviderTorrentAcquisitionState.Pending -> delay(200L)
                    }
                }
                error("unreachable")
            }

            assertEquals(TorrentAcquisitionRoute.DIRECT_P2P, ready.route)
            assertEquals(PROVIDER_ID, ready.providerId)
            val archive = ready.resource as TorrentReadableResource.LocalArchive
            assertEquals(TorrentArchiveFormat.CBZ, archive.format)
            assertTrue("P2P result must be a managed content URI", archive.uri.startsWith("content://"))
            val bytes = context.contentResolver.openInputStream(Uri.parse(archive.uri))!!.use { it.readBytes() }
            assertArrayEquals(fixture.selectedArchiveBytes, bytes)
            assertEquals(1, engineStarts)
            assertFalse("unselected torrent files must remain incomplete", unrelatedFileCompleted)
            await("P2P working directory cleanup") {
                jobRoot.listFiles().orEmpty().isEmpty()
            }
        } finally {
            jobs.close()
            ProviderTorrentAcceptanceFixtures.stopLoopbackSeeder(seeder)
            managedFiles.clearAll()
            root.deleteRecursively()
        }
    }

    @Test
    fun ambiguousChapterMapping_neverProducesAnAutomaticP2pSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-acquisition-ambiguous").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
            val candidate = candidate(fixture)
            val duplicate = TorrentCandidateFile(
                index = candidate.files.orEmpty().maxOf(TorrentCandidateFile::index) + 1,
                path = "alternate/chapter-012.zip",
                sizeBytes = fixture.selectedArchiveBytes.size.toLong(),
                languages = setOf("en"),
            )
            val ambiguous = candidate.copy(
                files = candidate.files.orEmpty() + duplicate,
            )

            val match = TorrentChapterMapper().map(
                request = chapter12Request(),
                candidate = ambiguous,
            )

            assertTrue(
                "ambiguous chapter pack must fail closed before acquisition but was $match",
                match is TorrentChapterFileMatch.Ambiguous,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directP2pWithoutConsent_neverCallsP2pGateway() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-acquisition-no-consent").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
            val candidate = candidate(fixture)
            val selected = (
                TorrentChapterMapper().map(
                    chapter12Request(),
                    candidate,
                ) as TorrentChapterFileMatch.Exact
                ).file
            val activePackage = ProviderTorrentAcceptanceFixtures.createDirectP2pProviderPackage(
                providerId = PROVIDER_ID.value,
                repositoryId = REPOSITORY_ID,
            )
            val registry = DefaultProviderRegistry(
                registrations = { listOf(registration(activePackage)) },
            )
            var p2pCalls = 0
            val coordinator = ProviderTorrentAcquisitionCoordinator(
                registry = registry,
                debrid = DebridResolveGateway { _, _ ->
                    error("Debrid must not be touched in P2P_ONLY acceptance")
                },
                p2p = P2pAcquireGateway { _, _ ->
                    p2pCalls += 1
                    error("P2P gateway must not be touched without consent")
                },
                directP2pHostAvailable = { true },
            )

            val result = coordinator.acquire(
                request = TorrentAcquisitionRequest(
                    operationId = "acceptance:no-consent",
                    candidate = candidate,
                    selectedFile = selected,
                ),
                preference = TorrentAcquisitionPreference.P2P_ONLY,
                directP2pAllowed = false,
            )

            assertEquals(
                ProviderTorrentAcquisitionState.Failure(
                    TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
                ),
                result,
            )
            assertEquals(0, p2pCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unavailableLoopbackSeeder_failsBoundedlyAndCleansWorkingFiles() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-acquisition-no-seeder").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val managedFiles = ProviderManagedFileStore(context).apply { clearAll() }
        val candidate = candidate(fixture)
        val selected = (
            TorrentChapterMapper().map(
                chapter12Request(),
                candidate,
            ) as TorrentChapterFileMatch.Exact
            ).file
        val jobRoot = File(root, "jobs")
        val engine = JlibtorrentProviderP2pDownloadEngine(
            metadataResolver = { _, _, _ -> fixture.torrent },
            initialPeers = { listOf(TcpEndpoint("127.0.0.1", 49311)) },
            sessionParamsFactory = {
                ProviderTorrentAcceptanceFixtures.localOnlyParams(49312)
            },
            downloadTimeoutMs = 1_000L,
            nativeSupport = { true },
        )
        val jobs = ProviderP2pJobManager(
            root = jobRoot,
            managedFiles = managedFiles,
            engine = engine,
        )
        val service = jobs.service(PROVIDER_ID.value)
        val request = ProviderP2pAcquireRequest(
            operationId = "acceptance:no-seeder",
            magnetUri = candidate.magnetUri,
            infoHash = candidate.infoHash,
            selectedFileIndex = selected.index,
            selectedFilePath = selected.path,
            selectedFileSizeBytes = selected.sizeBytes,
        )

        try {
            val first = service.acquire(request)
            assertTrue(
                "first no-seeder acquisition must be pending but was $first",
                first is ProviderP2pAcquireResponse.Pending,
            )

            val failure = withTimeout(8_000L) {
                while (true) {
                    when (val state = service.acquire(request)) {
                        is ProviderP2pAcquireResponse.Failure -> return@withTimeout state
                        is ProviderP2pAcquireResponse.Pending -> delay(100L)
                        is ProviderP2pAcquireResponse.Ready ->
                            error("no-seeder acquisition unexpectedly completed")
                    }
                }
                error("unreachable")
            }

            assertEquals(ProviderP2pFailureCode.NETWORK_ERROR, failure.reason)
            await("failed P2P working directory cleanup") {
                jobRoot.listFiles().orEmpty().isEmpty()
            }
        } finally {
            jobs.close()
            managedFiles.clearAll()
            root.deleteRecursively()
        }
    }

    private fun chapter12Request() = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        ),
        volume = null,
        preferredLanguages = setOf("en"),
        titles = listOf("Acceptance Series"),
        chapterNumber = "12",
    )

    private fun candidate(
        fixture: ProviderTorrentAcceptanceFixtures.ThreeChapterTorrent,
    ): TorrentCandidate {
        val files = fixture.torrent.files()
        return TorrentCandidate(
            infoHash = fixture.torrent.infoHashV1()?.toHex(),
            magnetUri = fixture.torrent.makeMagnetUri(),
            torrentUrl = null,
            displayName = "Acceptance pack",
            languages = setOf("en"),
            files = (0 until files.numFiles()).map { index ->
                TorrentCandidateFile(
                    index = index,
                    path = files.filePath(index),
                    sizeBytes = files.fileSize(index),
                    languages = setOf("en"),
                )
            },
        )
    }

    private fun registration(activePackage: ScriptProviderPackage): ProviderRegistration {
        val manifest = activePackage.parsed.manifest
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = ProviderId(manifest.id),
                name = manifest.name,
                version = ProviderVersion(manifest.version.name, manifest.version.code),
                origin = ProviderOrigin.Repository(activePackage.repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(ProviderCapabilities.AcquisitionP2pV1),
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = manifest.contentLanguages,
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            configurationFingerprint = "acceptance-p2p-v1",
            enabledCapabilities = setOf(ProviderCapabilities.AcquisitionP2pV1),
        )
    }

    private fun await(
        label: String,
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50L)
        }
        error("Timed out waiting for $label")
    }

    private companion object {
        val PROVIDER_ID = ProviderId("org.example.torrent.p2p.acceptance")
        const val REPOSITORY_ID = "acceptance.repo"
    }
}
