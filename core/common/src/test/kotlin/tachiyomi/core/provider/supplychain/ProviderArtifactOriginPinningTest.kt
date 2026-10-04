package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path

class ProviderArtifactOriginPinningTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installed Provider cannot silently switch repository origin`() {
        val store = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())
        store.activate(artifact(repositoryId = "repo.a", versionCode = 1))

        shouldThrow<ProviderSupplyChainException> {
            store.activate(artifact(repositoryId = "repo.b", versionCode = 2))
        }

        store.current("reader.example")?.repositoryId shouldBe "repo.a"
        store.current("reader.example")?.versionCode shouldBe 1L
    }

    @Test
    fun `artifact activation boundary is synchronized so origin pinning is atomic`() {
        val activate = ProviderArtifactStore::class.java.getDeclaredMethod(
            "activate",
            VerifiedProviderArtifact::class.java,
        )

        Modifier.isSynchronized(activate.modifiers) shouldBe true
    }

    @Test
    fun `installed Provider may update within its pinned repository origin`() {
        val store = ProviderArtifactStore(tempDir.resolve("same-origin").toFile())
        store.activate(artifact(repositoryId = "repo.a", versionCode = 1))
        store.activate(artifact(repositoryId = "repo.a", versionCode = 2))

        store.current("reader.example")?.repositoryId shouldBe "repo.a"
        store.current("reader.example")?.versionCode shouldBe 2L
        store.previous("reader.example")?.versionCode shouldBe 1L
    }

    private fun artifact(
        repositoryId: String,
        versionCode: Long,
    ): VerifiedProviderArtifact {
        val bytes = "$repositoryId-v$versionCode".encodeToByteArray()
        return VerifiedProviderArtifact(
            repositoryId = repositoryId,
            descriptor = ProviderArtifactDescriptor(
                providerId = "reader.example",
                versionName = "1.0.$versionCode",
                versionCode = versionCode,
                artifactUrl = "https://$repositoryId/reader-$versionCode.tsz",
                sha256 = sha256Hex(bytes),
                minHostApi = 1,
            ),
            bytes = bytes,
        )
    }
}
