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
    fun `same repository id with a different trust root cannot inherit installed origin`() {
        val store = ProviderArtifactStore(tempDir.resolve("trust-root-origin").toFile())
        store.activate(
            artifact(
                repositoryId = "repo.a",
                versionCode = 1,
                trustAnchor = "root-a",
            ),
        )

        shouldThrow<ProviderSupplyChainException> {
            store.activate(
                artifact(
                    repositoryId = "repo.a",
                    versionCode = 2,
                    trustAnchor = "root-b",
                ),
            )
        }

        store.current("reader.example")?.versionCode shouldBe 1L
        store.current("reader.example")?.repositoryTrustAnchorSha256 shouldBe
            sha256Hex("root-a".encodeToByteArray())
    }

    @Test
    fun `artifact state mutation boundaries are synchronized so origin pinning is atomic`() {
        val activate = ProviderArtifactStore::class.java.getDeclaredMethod(
            "activate",
            VerifiedProviderArtifact::class.java,
        )
        val rollback = ProviderArtifactStore::class.java.getDeclaredMethod(
            "rollback",
            String::class.java,
        )

        Modifier.isSynchronized(activate.modifiers) shouldBe true
        Modifier.isSynchronized(rollback.modifiers) shouldBe true
    }

    @Test
    fun `rejected foreign origin cannot poison a later same version update`() {
        val store = ProviderArtifactStore(tempDir.resolve("foreign-poisoning").toFile())
        store.activate(artifact(repositoryId = "repo.a", versionCode = 1))

        shouldThrow<ProviderSupplyChainException> {
            store.activate(artifact(repositoryId = "repo.b", versionCode = 2))
        }
        store.current("reader.example")?.repositoryId shouldBe "repo.a"
        store.current("reader.example")?.versionCode shouldBe 1L

        store.activate(artifact(repositoryId = "repo.a", versionCode = 2))

        store.current("reader.example")?.repositoryId shouldBe "repo.a"
        store.current("reader.example")?.versionCode shouldBe 2L
        store.previous("reader.example")?.versionCode shouldBe 1L
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

    @Test
    fun `explicit uninstall removes rollback state and releases trust origin`() {
        val root = tempDir.resolve("uninstall-artifacts").toFile()
        val store = ProviderArtifactStore(root)
        store.activate(artifact(repositoryId = "repo.a", versionCode = 1))
        store.activate(artifact(repositoryId = "repo.a", versionCode = 2))
        store.activate(artifact(repositoryId = "repo.keep", versionCode = 1, providerId = "reader.keep"))

        store.uninstall("reader.example") shouldBe true
        store.current("reader.example") shouldBe null
        store.previous("reader.example") shouldBe null
        root.resolve("reader.example").exists() shouldBe false
        store.uninstall("reader.example") shouldBe false
        store.current("reader.keep")?.repositoryId shouldBe "repo.keep"

        store.activate(artifact(repositoryId = "repo.b", versionCode = 1))
        store.current("reader.example")?.repositoryId shouldBe "repo.b"
    }

    private fun artifact(
        repositoryId: String,
        versionCode: Long,
        trustAnchor: String = "$repositoryId-root",
        providerId: String = "reader.example",
    ): VerifiedProviderArtifact {
        val bytes = "$repositoryId-v$versionCode".encodeToByteArray()
        return VerifiedProviderArtifact(
            repositoryId = repositoryId,
            repositoryTrustAnchorSha256 = sha256Hex(trustAnchor.encodeToByteArray()),
            descriptor = ProviderArtifactDescriptor(
                providerId = providerId,
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
