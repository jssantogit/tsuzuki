package eu.kanade.tachiyomi.provider.repository

import eu.kanade.presentation.more.settings.screen.providerRepositoryTrustConfirmationToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.supplychain.FileProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.FileProviderRepositoryTrustStore
import tachiyomi.core.provider.supplychain.ProviderArtifactDescriptor
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.ProviderRepositoryIndex
import tachiyomi.core.provider.supplychain.ProviderRepositoryTrust
import tachiyomi.core.provider.supplychain.SignedProviderRepositoryIndex
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact
import tachiyomi.core.provider.supplychain.sha256Hex
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstalledScriptProviderRegistryTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `installed package becomes enabled Provider registration with manifest authority`() {
        val artifactStore = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())
        artifactStore.activate(artifact(versionCode = 1))
        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = FileProviderLocalConfigurationStore(
                tempDir.resolve("config").toFile(),
            ),
        )

        val registration = registry.registration(ProviderId("reader.example"))!!

        registration.lifecycleStatus shouldBe ProviderLifecycleStatus.ENABLED
        registration.descriptor.capabilities shouldBe setOf(
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        )
        registration.descriptor.permissions.network?.origins shouldBe
            setOf("https://reader.example")
        registration.descriptor.permissions.secrets shouldBe setOf("session")
        registration.descriptor.settings.single().type shouldBe ProviderSettingType.STRING
        registration.descriptor.contentLanguages shouldBe setOf("en", "pt-BR")
        registration.facets.map { it.facetId }.toSet() shouldBe setOf("en", "pt-BR")
    }

    @Test
    fun `enablement and language selection update registry and configuration fingerprint`() {
        val artifactStore = ProviderArtifactStore(tempDir.resolve("state-artifacts").toFile())
        artifactStore.activate(artifact(versionCode = 1))
        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = FileProviderLocalConfigurationStore(
                tempDir.resolve("state-config").toFile(),
            ),
        )
        val providerId = ProviderId("reader.example")
        val initialFingerprint = registry.configurationFingerprint(providerId)

        registry.setEnabledContentLanguages(providerId, setOf("pt-BR"))
        registry.facets(providerId).map { it.facetId } shouldBe listOf("pt-BR")
        registry.configurationFingerprint(providerId) shouldBe
            registry.registration(providerId)!!.configurationFingerprint
        (registry.configurationFingerprint(providerId) == initialFingerprint) shouldBe false

        registry.setEnabled(providerId, false)
        registry.registration(providerId)!!.lifecycleStatus shouldBe ProviderLifecycleStatus.DISABLED
    }

    @Test
    fun `revoked installed package remains visible as blocked with manifest metadata`() {
        val artifactStore = ProviderArtifactStore(tempDir.resolve("revoked-artifacts").toFile())
        val installed = artifact(versionCode = 1)
        artifactStore.activate(installed)

        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 1,
            trustedKeys = mapOf("root-1" to keyPair.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(
                tempDir.resolve("revoked-trust").toFile(),
            ),
        )
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(installed.descriptor),
            revokedArtifactSha256 = setOf(installed.descriptor.sha256),
        )
        val payload = Json {
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(index).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        val verified = trust.verifyAndAccept(
            SignedProviderRepositoryIndex(
                keyId = "root-1",
                payload = payload,
                signature = signature,
            ),
        )
        trust.applyRevocations(verified, artifactStore)

        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = FileProviderLocalConfigurationStore(
                tempDir.resolve("revoked-config").toFile(),
            ),
        )
        val registration = registry.registration(ProviderId("reader.example"))!!

        registration.lifecycleStatus shouldBe ProviderLifecycleStatus.BLOCKED
        registration.descriptor.name shouldBe "Reader Example"
        registration.descriptor.capabilities shouldBe setOf(
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        )
        registry.enabled(ProviderCapabilities.ReadingChaptersV1) shouldBe emptyList()
    }

    @Test
    fun `configuration fingerprint changes when active artifact changes`() {
        val artifactStore = ProviderArtifactStore(tempDir.resolve("fingerprint-artifacts").toFile())
        val configurationStore = FileProviderLocalConfigurationStore(
            tempDir.resolve("fingerprint-config").toFile(),
        )
        artifactStore.activate(artifact(versionCode = 1))
        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = configurationStore,
        )
        val providerId = ProviderId("reader.example")
        val v1Fingerprint = registry.configurationFingerprint(providerId)

        artifactStore.activate(artifact(versionCode = 2))

        val v2Fingerprint = registry.configurationFingerprint(providerId)
        (v2Fingerprint == v1Fingerprint) shouldBe false
    }

    @Test
    fun `local configuration cannot be created for an uninstalled Provider`() {
        val configurationStore = FileProviderLocalConfigurationStore(
            tempDir.resolve("uninstalled-config").toFile(),
        )
        val registry = InstalledScriptProviderRegistry(
            artifactStore = ProviderArtifactStore(
                tempDir.resolve("uninstalled-artifacts").toFile(),
            ),
            configurationStore = configurationStore,
        )
        val providerId = ProviderId("missing.example")

        shouldThrow<IllegalArgumentException> {
            registry.setEnabled(providerId, false)
        }
        configurationStore.get(providerId.value) shouldBe null
    }

    @Test
    fun `repository trust confirmation is invalidated by any enrollment edit`() {
        val confirmed = providerRepositoryTrustConfirmationToken(
            displayName = "Example",
            repositoryId = "repo.example",
            indexUrl = "https://repo.example/index.json",
            keyId = "root-1",
            keyFingerprint = "abc123",
        )

        providerRepositoryTrustConfirmationToken(
            displayName = "Example",
            repositoryId = "repo.example",
            indexUrl = "https://repo.example/index-v2.json",
            keyId = "root-1",
            keyFingerprint = "abc123",
        ) == confirmed shouldBe false

        providerRepositoryTrustConfirmationToken(
            displayName = "Example",
            repositoryId = "repo.example",
            indexUrl = "https://repo.example/index.json",
            keyId = "root-2",
            keyFingerprint = "def456",
        ) == confirmed shouldBe false
    }

    @Test
    fun `language selection cannot grant undeclared Provider facet`() {
        val artifactStore = ProviderArtifactStore(tempDir.resolve("invalid-artifacts").toFile())
        artifactStore.activate(artifact(versionCode = 1))
        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = FileProviderLocalConfigurationStore(
                tempDir.resolve("invalid-config").toFile(),
            ),
        )

        shouldThrow<IllegalArgumentException> {
            registry.setEnabledContentLanguages(
                ProviderId("reader.example"),
                setOf("ja"),
            )
        }
    }

    private fun artifact(versionCode: Long): VerifiedProviderArtifact {
        val bytes = tsz(versionCode)
        return VerifiedProviderArtifact(
            repositoryId = "repo.example",
            descriptor = ProviderArtifactDescriptor(
                providerId = "reader.example",
                versionName = "1.0.$versionCode",
                versionCode = versionCode,
                artifactUrl = "https://repo.example/reader-$versionCode.tsz",
                sha256 = sha256Hex(bytes),
                minHostApi = 1,
            ),
            bytes = bytes,
        )
    }

    private fun tsz(versionCode: Long): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "reader.example",
              "name": "Reader Example",
              "version": {"name": "1.0.$versionCode", "code": $versionCode},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [
                {"id":"reading.chapters","version":1},
                {"id":"reading.pages","version":1}
              ],
              "permissions": {
                "network": {
                  "origins": ["https://reader.example"],
                  "localNetwork": false
                },
                "storage": {"enabled": true},
                "secrets": ["session"]
              },
              "contentLanguages": ["en", "pt-BR"],
              "settings": [
                {
                  "key": "quality",
                  "label": "Quality",
                  "type": "string",
                  "required": false,
                  "options": []
                }
              ]
            }
        """.trimIndent()

        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("main.js"))
                zip.write("export default {}".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }
}
