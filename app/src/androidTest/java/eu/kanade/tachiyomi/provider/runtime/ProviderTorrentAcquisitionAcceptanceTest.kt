package eu.kanade.tachiyomi.provider.runtime

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TcpEndpoint
import eu.kanade.tachiyomi.provider.torrent.HttpProviderTorrentMetadataInspector
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentArtifactEngine
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentMetadataSearchGateway
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentPreferences
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.core.archive.ArchiveReader
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.content.PrepareTorrentArtifact
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveGateway
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireGateway
import tachiyomi.domain.tsuzuki.provider.torrent.PrepareProviderTorrentForReader
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderChapterTorrentOption
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
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentHttpFileMaterializer
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest
import tachiyomi.domain.tsuzuki.provider.torrent.toContentDelivery
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ProviderTorrentAcquisitionAcceptanceTest {

    @Test
    fun hostVerifiedMetadata_skipsMagnetResolutionAndReadsOnlySelectedChapter() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-host-verified-acceptance").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val managedFiles = ProviderManagedFileStore(context).apply { clearAll() }
        val sourceUrl = "https://example.invalid/fixture.torrent"
        val candidate = candidate(fixture).copy(torrentUrl = sourceUrl)
        val selected = (TorrentChapterMapper().map(chapter12Request(), candidate) as TorrentChapterFileMatch.Exact).file
        val cache = HostVerifiedTorrentMetadataCache()
        val seeder = SessionManager()
        val seedPort = 49431
        val leecherPort = 49432
        val jobRoot = File(root, "jobs")
        var jobs: ProviderP2pJobManager? = null

        try {
            assertTrue(
                "genuine Host-parsed torrent metadata should be reusable",
                cache.offer("org.example.torrent.search.acceptance", candidate, fixture.metadataBytes),
            )
            ProviderTorrentAcceptanceFixtures.startLoopbackSeeder(seeder, fixture, seedPort)
            val engine = JlibtorrentProviderP2pDownloadEngine(
                initialPeers = { listOf(TcpEndpoint("127.0.0.1", seedPort)) },
                sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(leecherPort) },
                metadataTimeoutSeconds = 1,
                downloadTimeoutMs = 30_000L,
                nativeSupport = { true },
            )
            jobs = ProviderP2pJobManager(
                root = jobRoot,
                managedFiles = managedFiles,
                engine = engine,
                verifiedMetadata = cache,
            )
            val request = ProviderP2pAcquireRequest(
                operationId = "host-verified-chapter-012",
                infoHash = candidate.infoHash,
                magnetUri = candidate.magnetUri,
                torrentUrl = sourceUrl,
                selectedFileIndex = selected.index,
                selectedFilePath = selected.path,
                selectedFileSizeBytes = selected.sizeBytes,
            )
            assertTrue(
                cache.find(PROVIDER_ID.value, request)?.contentEquals(fixture.metadataBytes) == true,
            )
            assertTrue(
                "a different archive cannot borrow Host-verified chapter metadata",
                cache.find(PROVIDER_ID.value, request.copy(selectedFilePath = "Other.cbz")) == null,
            )
            val service = jobs.service(PROVIDER_ID.value, request)
            assertTrue(service.acquire(request) is ProviderP2pAcquireResponse.Pending)

            val ready = withTimeout(35_000L) {
                while (true) {
                    when (val result = service.acquire(request)) {
                        is ProviderP2pAcquireResponse.Ready -> return@withTimeout result
                        is ProviderP2pAcquireResponse.Failure -> error("cached Host P2P failed: ${result.reason}")
                        is ProviderP2pAcquireResponse.Pending -> delay(100L)
                    }
                }
                error("unreachable")
            }
            val uri = managedFiles.resolve(
                PROVIDER_ID,
                ProviderManagedResourceRef(ready.resource),
                ProviderManagedFileFormat.CBZ,
            )
            assertTrue("Reader resource should be available", uri?.startsWith("content://") == true)
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
            assertArrayEquals(fixture.selectedArchiveBytes, bytes)
            val downloadRoot = File(jobRoot, "download")
            assertFalse("unexpected global torrent output", downloadRoot.isFile)
        } finally {
            jobs?.close()
            ProviderTorrentAcceptanceFixtures.stopLoopbackSeeder(seeder)
            managedFiles.clearAll()
            root.deleteRecursively()
        }
    }

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
    fun providerDiscoveryHydrationExactMatchApprovedP2pAndReaderPagesCompleteInOneSession() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-full-chain").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val server = MockWebServer()
        server.start()
        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val metadataUrl = server.url("/verified.torrent").toString()
        val hash = requireNotNull(fixture.torrent.infoHashV1()?.toHex())
        server.enqueue(
            MockResponse.Builder().body(
                """
                {"items":[{"infoHash":"$hash","torrentUrl":"$metadataUrl",
                "displayName":"Acceptance Series release","languages":["en"]}],"nextCursor":null}
                """.trimIndent(),
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(fixture.metadataBytes)).build(),
        )

        val searchId = ProviderId("org.example.torrent.search.acceptance")
        val searchPackage = ProviderTorrentAcceptanceFixtures.createTorrentSearchProviderPackage(
            providerId = searchId.value,
            repositoryId = REPOSITORY_ID,
            origin = origin,
            endpoint = server.url("/torrent-search").toString(),
        )
        val p2pPackage = ProviderTorrentAcceptanceFixtures.createDirectP2pProviderPackage(
            providerId = PROVIDER_ID.value,
            repositoryId = REPOSITORY_ID,
        )
        val searchRegistration = ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = searchId,
                name = "Fixture discovery",
                version = ProviderVersion("1.0.0", 1),
                origin = ProviderOrigin.Repository(REPOSITORY_ID),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                permissions = ProviderPermissionSet(
                    network = ProviderNetworkPermission(
                        origins = setOf(origin),
                        localNetwork = true,
                    ),
                ),
                settings = emptyList(),
                contentLanguages = setOf("en"),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
        )
        val registry = DefaultProviderRegistry(
            registrations = { listOf(searchRegistration, registration(p2pPackage)) },
        )
        val cache = HostVerifiedTorrentMetadataCache()
        val managedFiles = ProviderManagedFileStore(context).apply { clearAll() }
        val seeder = SessionManager()
        var jobs: ProviderP2pJobManager? = null

        try {
            ProviderTorrentAcceptanceFixtures.startLoopbackSeeder(seeder, fixture, 49451)
            jobs = ProviderP2pJobManager(
                root = File(root, "jobs"),
                managedFiles = managedFiles,
                verifiedMetadata = cache,
                engine = JlibtorrentProviderP2pDownloadEngine(
                    initialPeers = { listOf(TcpEndpoint("127.0.0.1", 49451)) },
                    sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(49452) },
                    metadataTimeoutSeconds = 1,
                    downloadTimeoutMs = 30_000L,
                    nativeSupport = { true },
                ),
            )
            val hostFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = File(root, "storage"),
                p2pServiceFactory = { id -> requireNotNull(jobs).service(id) },
                approvedP2pServiceFactory = { id, approved -> requireNotNull(jobs).service(id, approved) },
                managedFiles = managedFiles,
            )
            val executor = ScriptProviderCapabilityExecutor(
                registry = registry,
                packageSource = ScriptProviderPackageSource { id ->
                    when (id) {
                        searchId -> searchPackage
                        PROVIDER_ID -> p2pPackage
                        else -> null
                    }
                },
                runtimeClient = ProviderRuntimeClient(context, hostFactory),
            )
            val gateway = ScriptProviderTorrentGateway(executor, managedResources = managedFiles)
            val hydratedSearch = ProviderTorrentMetadataSearchGateway(
                delegate = gateway,
                registry = registry,
                inspector = HttpProviderTorrentMetadataInspector(
                    OkHttpClient(),
                    verifiedMetadata = cache,
                ),
                diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
            )
            val found = hydratedSearch.search(
                providerId = searchId,
                request = TorrentSearchRequest(
                    titles = listOf("Acceptance Series"),
                    preferredLanguages = setOf("en"),
                    chapterNumber = "12",
                ),
            )
            assertTrue("isolated discovery + Host hydration failed: $found", found is ProviderCallResult.Success)
            val candidate = (found as ProviderCallResult.Success).value.items.single()
            assertEquals(
                "Host hydration must retain the entire native file table including padding",
                fixture.torrent.files().numFiles(),
                candidate.files.orEmpty().size,
            )
            assertEquals(
                "native file table should contain the three CBZ chapters",
                3,
                candidate.files.orEmpty().count { it.path.endsWith(".cbz") },
            )
            assertEquals(fixture.selectedFilePath, candidate.files!![fixture.selectedFileIndex].path)
            val match = TorrentChapterMapper().map(chapter12Request(), candidate)
            assertTrue("cross-work identity or archive ambiguity: $match", match is TorrentChapterFileMatch.Exact)
            val selected = (match as TorrentChapterFileMatch.Exact).file
            assertEquals(fixture.selectedFileIndex, selected.index)
            val delivery = requireNotNull(
                ProviderChapterTorrentOption(
                    canonicalChapterId = "canonical-chapter-12",
                    providerId = searchId,
                    candidate = candidate,
                    selectedFile = selected,
                ).toContentDelivery(),
            )
            assertEquals(metadataUrl, delivery.torrentUrl)
            assertEquals(selected.sizeBytes, delivery.fileSizeBytes)
            val verifiedSelection = ProviderP2pAcquireRequest(
                operationId = "acceptance:full-chain",
                infoHash = hash,
                torrentUrl = metadataUrl,
                selectedFileIndex = selected.index,
                selectedFilePath = selected.path,
                selectedFileSizeBytes = selected.sizeBytes,
            )
            assertTrue(
                "Host-verified metadata must survive the discovery-to-acquisition provider boundary",
                cache.find(PROVIDER_ID.value, verifiedSelection)?.contentEquals(fixture.metadataBytes) == true,
            )

            val preferences = ProviderTorrentPreferences(InMemoryPreferenceStore()).apply {
                acquisitionPreference.set(TorrentAcquisitionPreference.P2P_ONLY)
                directP2pAllowed.set(true)
            }
            val coordinator = ProviderTorrentAcquisitionCoordinator(
                registry = registry,
                debrid = DebridResolveGateway { _, _ -> error("P2P_ONLY must not call Debrid") },
                p2p = gateway,
                directP2pHostAvailable = { true },
            )
            val engine = ProviderTorrentArtifactEngine(
                prepareForReader = PrepareProviderTorrentForReader(
                    coordinator = coordinator,
                    httpMaterializer = TorrentHttpFileMaterializer { _, _, _ ->
                        error("Host-managed P2P archive must not be materialized via HTTP")
                    },
                ),
                preferences = preferences,
                pollIntervalMs = 100L,
                acquisitionTimeoutMs = 45_000L,
            )
            val prepared = PrepareTorrentArtifact(engine).execute(delivery).getOrThrow()
            assertTrue("Reader expected managed archive: $prepared", prepared is PreparedChapterContent.LocalArchive)
            val uri = (prepared as PreparedChapterContent.LocalArchive).uri
            val raw = context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
            assertArrayEquals("only the requested CBZ is materialized", fixture.selectedArchiveBytes, raw)

            val reader = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r").use { descriptor ->
                ArchiveReader(requireNotNull(descriptor))
            }
            val loader = ArchivePageLoader(reader)
            try {
                val pages = loader.getPages()
                assertEquals(3, pages.size)
                val actualColors = pages.map { page ->
                    val bitmap = BitmapFactory.decodeStream(requireNotNull(page.stream).invoke())
                    val decoded = requireNotNull(bitmap)
                    try {
                        decoded.getPixel(0, 0)
                    } finally {
                        decoded.recycle()
                    }
                }
                assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), actualColors)
            } finally {
                loader.recycle()
            }
            assertEquals(2, server.requestCount)
        } finally {
            jobs?.close()
            ProviderTorrentAcceptanceFixtures.stopLoopbackSeeder(seeder)
            managedFiles.clearAll()
            server.close()
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
                path = "alternate/Acceptance Series 012.zip",
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

    @Test
    fun revokedHostAuthorization_stopsNativeP2pAndCleansWorkingDirectory() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-revocation-native").apply {
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
        val authorized = AtomicBoolean(true)
        val jobRoot = File(root, "jobs")
        val jobs = ProviderP2pJobManager(
            root = jobRoot,
            managedFiles = managedFiles,
            engine = JlibtorrentProviderP2pDownloadEngine(
                metadataResolver = { _, _, _ -> fixture.torrent },
                initialPeers = { listOf(TcpEndpoint("127.0.0.1", 49331)) },
                sessionParamsFactory = {
                    ProviderTorrentAcceptanceFixtures.localOnlyParams(49332)
                },
                downloadTimeoutMs = 10_000L,
                nativeSupport = { true },
            ),
            authorizationCheck = { authorized.get() },
            authorizationCheckIntervalMs = 100L,
        )
        val request = ProviderP2pAcquireRequest(
            operationId = "acceptance:revoke-native",
            magnetUri = candidate.magnetUri,
            infoHash = candidate.infoHash,
            selectedFileIndex = selected.index,
            selectedFilePath = selected.path,
            selectedFileSizeBytes = selected.sizeBytes,
        )
        val service = jobs.service(PROVIDER_ID.value)

        try {
            assertTrue(service.acquire(request) is ProviderP2pAcquireResponse.Pending)
            await("native P2P working directory created") {
                jobRoot.listFiles().orEmpty().isNotEmpty()
            }

            // No Reader poll during the transition: the Host monitor must stop
            // the native session and evict the stale job on its own.
            authorized.set(false)
            await("revoked native P2P working files cleaned", timeoutMs = 8_000L) {
                jobRoot.listFiles().orEmpty().isEmpty()
            }
            assertEquals(
                ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.CANCELLED),
                service.acquire(request),
            )
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
