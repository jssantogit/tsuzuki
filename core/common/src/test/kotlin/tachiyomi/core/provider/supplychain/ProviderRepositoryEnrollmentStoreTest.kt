package tachiyomi.core.provider.supplychain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProviderRepositoryEnrollmentStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `persists confirmed repository enrollment and fingerprint across restart`() {
        val enrollment = enrollment("repo.example")
        val store = FileProviderRepositoryEnrollmentStore(tempDir.resolve("enrollments").toFile())

        store.save(
            EnrolledProviderRepository(
                displayName = "Example Repository",
                enrollment = enrollment,
            ),
        )

        val restarted = FileProviderRepositoryEnrollmentStore(tempDir.resolve("enrollments").toFile())
        val stored = restarted.get("repo.example")!!

        stored.displayName shouldBe "Example Repository"
        stored.enrollment shouldBe enrollment
        stored.keyFingerprintSha256 shouldBe providerRepositoryKeyFingerprint(enrollment.signingKey)
        restarted.list().map { it.enrollment.repositoryId } shouldBe listOf("repo.example")
    }

    @Test
    fun `metadata and index url may change without silently repinning repository key`() {
        val initial = enrollment("repo.example")
        val store = FileProviderRepositoryEnrollmentStore(tempDir.resolve("updates").toFile())
        store.save(EnrolledProviderRepository("Old name", initial))

        store.save(
            EnrolledProviderRepository(
                displayName = "New name",
                enrollment = initial.copy(
                    indexUrl = "https://mirror.example/index.json",
                ),
            ),
        )

        store.get("repo.example")!!.let { updated ->
            updated.displayName shouldBe "New name"
            updated.enrollment.indexUrl shouldBe "https://mirror.example/index.json"
            updated.enrollment.signingKey shouldBe initial.signingKey
        }

        shouldThrow<ProviderSupplyChainException> {
            store.save(
                EnrolledProviderRepository(
                    displayName = "Repinned",
                    enrollment = enrollment("repo.example"),
                ),
            )
        }
    }

    @Test
    fun `enrollment rejects signing keys outside P-256`() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }
        val store = FileProviderRepositoryEnrollmentStore(tempDir.resolve("wrong-curve").toFile())

        shouldThrow<ProviderSupplyChainException> {
            store.save(
                EnrolledProviderRepository(
                    displayName = "Wrong curve",
                    enrollment = ProviderRepositoryEnrollment(
                        repositoryId = "repo.example",
                        indexUrl = "https://repo.example/index.json",
                        signingKey = ProviderRepositorySigningKey(
                            keyId = "root-1",
                            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `repository removal is explicit and idempotent`() {
        val store = FileProviderRepositoryEnrollmentStore(tempDir.resolve("remove").toFile())
        store.save(EnrolledProviderRepository("Example", enrollment("repo.example")))

        store.remove("repo.example") shouldBe true
        store.remove("repo.example") shouldBe false
        store.get("repo.example") shouldBe null
        store.list() shouldBe emptyList()
    }

    @Test
    fun `fingerprint is sha256 of the pinned public key bytes`() {
        val enrollment = enrollment("repo.example")
        val keyBytes = Base64.getDecoder().decode(enrollment.signingKey.publicKeyBase64)

        providerRepositoryKeyFingerprint(enrollment.signingKey) shouldBe sha256Hex(keyBytes)
    }

    private fun enrollment(repositoryId: String): ProviderRepositoryEnrollment {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        return ProviderRepositoryEnrollment(
            repositoryId = repositoryId,
            indexUrl = "https://$repositoryId/index.json",
            signingKey = ProviderRepositorySigningKey(
                keyId = "root-1",
                publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            ),
        )
    }
}
