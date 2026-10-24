package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pAcquireResponse
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderP2pJobManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquisition is asynchronous and converges to provider scoped managed archive`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var downloads = 0
        val managed = managedStore()
        val manager = ProviderP2pJobManager(
            root = tempDir.resolve("jobs").toFile(),
            managedFiles = managed,
            engine = ProviderP2pDownloadEngine { request, directory ->
                downloads += 1
                val file = directory.resolve(request.selectedFilePath.substringAfterLast('/'))
                file.writeBytes(zip("page-1.jpg", "chapter"))
                ProviderP2pDownloadResult.Ready(
                    file = file,
                    format = ProviderManagedResourceFormat.CBZ,
                )
            },
            scope = scope,
        )
        val service = manager.service("org.example.p2p")
        val request = request()

        val first = service.acquire(request)
        (first is ProviderP2pAcquireResponse.Pending) shouldBe true
        downloads shouldBe 0

        scope.advanceUntilIdle()

        val ready = service.acquire(request) as ProviderP2pAcquireResponse.Ready
        downloads shouldBe 1
        ready.format shouldBe ProviderManagedResourceFormat.CBZ
        ready.resource.startsWith("managed:") shouldBe true

        managed.resolve(
            providerId = ProviderId("org.example.p2p"),
            resource = ProviderManagedResourceRef(ready.resource),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe "managed-uri:${ready.resource}.cbz"

        managed.resolve(
            providerId = ProviderId("org.example.other"),
            resource = ProviderManagedResourceRef(ready.resource),
            format = ProviderManagedFileFormat.CBZ,
        ) shouldBe null

        manager.close()
    }

    @Test
    fun `operation id cannot be replayed with a different selected file`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var downloads = 0
        val manager = ProviderP2pJobManager(
            root = tempDir.resolve("jobs").toFile(),
            managedFiles = managedStore(),
            engine = ProviderP2pDownloadEngine { request, directory ->
                downloads += 1
                val file = directory.resolve("chapter.cbz")
                file.writeBytes(zip("page.jpg", "chapter"))
                ProviderP2pDownloadResult.Ready(
                    file = file,
                    format = ProviderManagedResourceFormat.CBZ,
                )
            },
            scope = scope,
        )
        val service = manager.service("org.example.p2p")

        (service.acquire(request()) is ProviderP2pAcquireResponse.Pending) shouldBe true
        val conflicting = service.acquire(
            request().copy(
                selectedFileIndex = 2,
                selectedFilePath = "pack/chapter-013.cbz",
            ),
        )

        conflicting shouldBe ProviderP2pAcquireResponse.Failure(
            ProviderP2pFailureCode.FILE_MISMATCH,
        )

        scope.advanceUntilIdle()
        downloads shouldBe 1
        manager.close()
    }

    @Test
    fun `active transfer cancellation allows a clean retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var starts = 0
        var cancellations = 0
        val manager = ProviderP2pJobManager(
            root = tempDir.resolve("jobs").toFile(),
            managedFiles = managedStore(),
            engine = ProviderP2pDownloadEngine { _, _ ->
                starts += 1
                try {
                    awaitCancellation()
                } finally {
                    cancellations += 1
                }
            },
            scope = scope,
        )
        val service = manager.service("org.example.p2p")
        val request = request()

        (service.acquire(request) is ProviderP2pAcquireResponse.Pending) shouldBe true
        runCurrent()
        starts shouldBe 1

        manager.cancelAll() shouldBe 1
        runCurrent()
        cancellations shouldBe 1

        (service.acquire(request) is ProviderP2pAcquireResponse.Pending) shouldBe true
        runCurrent()
        starts shouldBe 2

        manager.close()
    }

    @Test
    fun `engine failure remains typed and stable for polling`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val manager = ProviderP2pJobManager(
            root = tempDir.resolve("jobs").toFile(),
            managedFiles = managedStore(),
            engine = ProviderP2pDownloadEngine { _, _ ->
                ProviderP2pDownloadResult.Failure(ProviderP2pFailureCode.METADATA_UNAVAILABLE)
            },
            scope = scope,
        )
        val service = manager.service("org.example.p2p")
        val request = request()

        (service.acquire(request) is ProviderP2pAcquireResponse.Pending) shouldBe true
        scope.advanceUntilIdle()

        service.acquire(request) shouldBe ProviderP2pAcquireResponse.Failure(
            ProviderP2pFailureCode.METADATA_UNAVAILABLE,
        )
        manager.close()
    }

    @Test
    fun `Host cached metadata requires an exact per invocation selection grant`() = runTest {
        val cache = HostVerifiedTorrentMetadataCache(
            describe = {
                VerifiedTorrentDescription(
                    infoHash = "0123456789abcdef0123456789abcdef01234567",
                    files = listOf(VerifiedTorrentFile(1, "pack/chapter-012.cbz", 2048L)),
                )
            },
        )
        val url = "https://example.invalid/test.torrent"
        val approved = request().copy(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            torrentUrl = url,
        )
        cache.offer(
            "org.example.source",
            TorrentCandidate(
                infoHash = approved.infoHash,
                magnetUri = approved.magnetUri,
                torrentUrl = url,
                displayName = "Chapter 12",
                files = listOf(TorrentCandidateFile(1, "pack/chapter-012.cbz", 2048L)),
            ),
            byteArrayOf(1, 2, 3),
        ) shouldBe true

        val seen = mutableListOf<Boolean>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val engine = object : ProviderP2pDownloadEngine {
            override suspend fun download(
                request: ProviderP2pAcquireRequest,
                workingDirectory: java.io.File,
            ) = ProviderP2pDownloadResult.Failure(ProviderP2pFailureCode.METADATA_UNAVAILABLE)

            override suspend fun downloadWithHostMetadata(
                request: ProviderP2pAcquireRequest,
                workingDirectory: java.io.File,
                verifiedBytes: ByteArray?,
            ): ProviderP2pDownloadResult {
                seen += verifiedBytes != null
                return ProviderP2pDownloadResult.Failure(ProviderP2pFailureCode.METADATA_UNAVAILABLE)
            }
        }
        val jobs = ProviderP2pJobManager(
            root = tempDir.resolve("host-grants").toFile(),
            managedFiles = managedStore(),
            engine = engine,
            verifiedMetadata = cache,
            scope = scope,
        )
        try {
            val withoutGrant = approved.copy(operationId = "no-grant")
            (jobs.service("org.example.p2p").acquire(withoutGrant) is ProviderP2pAcquireResponse.Pending) shouldBe true
            scope.advanceUntilIdle()
            seen shouldBe listOf(false)

            val withGrant = approved.copy(operationId = "approved")
            (
                jobs.service("org.example.p2p", withGrant).acquire(withGrant)
                    is ProviderP2pAcquireResponse.Pending
                ) shouldBe true
            // A second Provider invocation with no Host selection approval must not
            // poll an already-running privileged job just by repeating its operation ID.
            jobs.service("org.example.p2p").acquire(withGrant) shouldBe
                ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.FILE_MISMATCH)
            scope.advanceUntilIdle()
            seen shouldBe listOf(false, true)
            // Completed privileged jobs are not transferable across invocations either.
            jobs.service("org.example.p2p").acquire(withGrant) shouldBe
                ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.FILE_MISMATCH)
            // The originally approved invocation can continue to poll the result.
            jobs.service("org.example.p2p", withGrant).acquire(withGrant) shouldBe
                ProviderP2pAcquireResponse.Failure(ProviderP2pFailureCode.METADATA_UNAVAILABLE)

            val differentSelection = approved.copy(
                operationId = "mismatch",
                selectedFilePath = "pack/other.cbz",
            )
            (
                jobs.service("org.example.p2p", withGrant).acquire(differentSelection)
                    is ProviderP2pAcquireResponse.Pending
                ) shouldBe true
            scope.advanceUntilIdle()
            seen shouldBe listOf(false, true, false)
        } finally {
            jobs.close()
        }
    }

    private fun request() = ProviderP2pAcquireRequest(
        operationId = "read:canonical-12",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        selectedFileIndex = 1,
        selectedFilePath = "pack/chapter-012.cbz",
        selectedFileSizeBytes = 2048,
    )

    private fun managedStore() = ProviderManagedFileStore(
        root = tempDir.resolve("managed").toFile(),
        uriFactory = { file ->
            "managed-uri:managed:${file.name.substringBefore('.')}.${file.extension}"
        },
        maxFileBytes = 4L * 1024L * 1024L,
        maxTotalBytesPerProvider = 8L * 1024L * 1024L,
    )

    private fun zip(
        name: String,
        content: String,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
