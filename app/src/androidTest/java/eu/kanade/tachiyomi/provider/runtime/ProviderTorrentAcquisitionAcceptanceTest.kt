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
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentAcquisitionCoordinator
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentAcquisitionState
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
                request = TorrentChapterRequest(
                    identity = CanonicalChapterIdentity(
                        type = CanonicalChapterType.REGULAR,
                        baseNumber = 12,
                    ),
                    volume = null,
                    preferredLanguages = setOf("en"),
                ),
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
            assertTrue("first P2P acquisition must be pending but was $first", first is ProviderTorrentAcquisitionState.Pending)

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
