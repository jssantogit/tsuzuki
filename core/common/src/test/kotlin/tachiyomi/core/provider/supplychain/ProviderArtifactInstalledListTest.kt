package tachiyomi.core.provider.supplychain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProviderArtifactInstalledListTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installed Provider snapshot is deterministic and includes repository origin`() {
        val store = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())
        store.activate(artifact("repo.b", "z.reader", 1))
        store.activate(artifact("repo.a", "a.reader", 2))

        store.listInstalled().map { it.providerId } shouldBe listOf("a.reader", "z.reader")
        store.listInstalled().map { it.repositoryId } shouldBe listOf("repo.a", "repo.b")
        store.listInstalled().map { it.versionCode } shouldBe listOf(2L, 1L)
    }

    private fun artifact(
        repositoryId: String,
        providerId: String,
        versionCode: Long,
    ): VerifiedProviderArtifact {
        val bytes = "$repositoryId-$providerId-$versionCode".encodeToByteArray()
        return VerifiedProviderArtifact(
            repositoryId = repositoryId,
            descriptor = ProviderArtifactDescriptor(
                providerId = providerId,
                versionName = "1.0.$versionCode",
                versionCode = versionCode,
                artifactUrl = "https://$repositoryId/$providerId-$versionCode.tsz",
                sha256 = sha256Hex(bytes),
                minHostApi = 1,
            ),
            bytes = bytes,
        )
    }
}
