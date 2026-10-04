package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import java.nio.file.Path

class JlibtorrentProviderP2pAvailabilityTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `release native support excludes x86_64 until upstream RELRO is 16k safe`() {
        JlibtorrentNativeSupport.isReleaseAbiSupported("arm64-v8a") shouldBe true
        JlibtorrentNativeSupport.isReleaseAbiSupported("armeabi-v7a") shouldBe true
        JlibtorrentNativeSupport.isReleaseAbiSupported("x86") shouldBe true
        JlibtorrentNativeSupport.isReleaseAbiSupported("x86_64") shouldBe false
    }

    @Test
    fun `unsupported runtime fails before creating a native session`() = runTest {
        var sessionCreated = false
        val engine = JlibtorrentProviderP2pDownloadEngine(
            metadataResolver = { _, _, _ -> error("metadata must not be requested") },
            initialPeers = { emptyList() },
            sessionParamsFactory = { error("session params must not be created") },
            downloadTimeoutMs = 30_000L,
            nativeSupport = { false },
            sessionManagerFactory = {
                sessionCreated = true
                error("native session must not be created")
            },
        )

        val result = engine.download(
            request = ProviderP2pAcquireRequest(
                operationId = "read:chapter-012",
                magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                infoHash = "0123456789abcdef0123456789abcdef01234567",
                selectedFileIndex = 1,
                selectedFilePath = "pack/chapter-012.cbz",
                selectedFileSizeBytes = 2048,
            ),
            workingDirectory = tempDir.resolve("work").toFile(),
        )

        result shouldBe ProviderP2pDownloadResult.Failure(
            ProviderP2pFailureCode.UNAVAILABLE,
        )
        sessionCreated shouldBe false
    }
}
