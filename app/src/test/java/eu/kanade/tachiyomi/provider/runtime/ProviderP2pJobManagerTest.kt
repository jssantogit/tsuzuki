package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

        service.acquire(request()) is ProviderP2pAcquireResponse.Pending shouldBe true
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

        service.acquire(request) is ProviderP2pAcquireResponse.Pending shouldBe true
        scope.advanceUntilIdle()

        service.acquire(request) shouldBe ProviderP2pAcquireResponse.Failure(
            ProviderP2pFailureCode.METADATA_UNAVAILABLE,
        )
        manager.close()
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
            "managed-uri:managed:${file.name.substringBefore('.') }.${file.extension}"
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
