package eu.kanade.tachiyomi.provider.repository

import eu.kanade.presentation.more.settings.screen.providerRepositoryTrustConfirmationToken
import eu.kanade.presentation.more.settings.screen.runProviderUiCatching
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
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

    private val fixtureKeyPair by lazy {
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

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
        registration.descriptor.origin shouldBe ProviderOrigin.Repository("repo.example")
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

        val keyPair = fixtureKeyPair
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
    fun `blocked and invalid Providers cannot be enabled through registry boundary`() {
        val blockedArtifacts = ProviderArtifactStore(
            tempDir.resolve("enable-blocked-artifacts").toFile(),
        )
        val blockedArtifact = artifact(versionCode = 1)
        blockedArtifacts.activate(blockedArtifact)

        val keyPair = fixtureKeyPair
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 1,
            trustedKeys = mapOf("root-1" to keyPair.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(
                tempDir.resolve("enable-blocked-trust").toFile(),
            ),
        )
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(blockedArtifact.descriptor),
            revokedArtifactSha256 = setOf(blockedArtifact.descriptor.sha256),
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
        trust.applyRevocations(verified, blockedArtifacts)

        val blockedConfig = FileProviderLocalConfigurationStore(
            tempDir.resolve("enable-blocked-config").toFile(),
        )
        val blockedRegistry = InstalledScriptProviderRegistry(
            artifactStore = blockedArtifacts,
            configurationStore = blockedConfig,
        )
        shouldThrow<IllegalStateException> {
            blockedRegistry.setEnabled(ProviderId("reader.example"), true)
        }
        blockedConfig.get("reader.example") shouldBe null

        val invalidArtifacts = ProviderArtifactStore(
            tempDir.resolve("enable-invalid-artifacts").toFile(),
        )
        val invalidBytes = "not-a-tsz".encodeToByteArray()
        invalidArtifacts.activate(
            verifiedArtifact(
                providerId = "invalid.example",
                versionName = "1.0.0",
                versionCode = 1,
                bytes = invalidBytes,
            ),
        )
        val invalidConfig = FileProviderLocalConfigurationStore(
            tempDir.resolve("enable-invalid-config").toFile(),
        )
        val invalidRegistry = InstalledScriptProviderRegistry(
            artifactStore = invalidArtifacts,
            configurationStore = invalidConfig,
        )
        invalidRegistry.registration(ProviderId("invalid.example"))!!.lifecycleStatus shouldBe
            ProviderLifecycleStatus.INVALID
        shouldThrow<IllegalStateException> {
            invalidRegistry.setEnabled(ProviderId("invalid.example"), true)
        }
        invalidConfig.get("invalid.example") shouldBe null
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
        ) shouldNotBe confirmed

        providerRepositoryTrustConfirmationToken(
            displayName = "Example",
            repositoryId = "repo.example",
            indexUrl = "https://repo.example/index.json",
            keyId = "root-2",
            keyFingerprint = "def456",
        ) shouldNotBe confirmed
    }

    @Test
    fun `Provider UI async helper never swallows coroutine cancellation`() = runBlocking {
        shouldThrow<CancellationException> {
            runProviderUiCatching<Unit> {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `reserved builtin id suppresses a preexisting script artifact`() {
        val artifactStore = ProviderArtifactStore(
            tempDir.resolve("reserved-script-artifacts").toFile(),
        )
        artifactStore.activate(
            artifact(
                versionCode = 1,
                providerId = "kitsu",
            ),
        )
        val configurationStore = FileProviderLocalConfigurationStore(
            tempDir.resolve("reserved-script-config").toFile(),
        )
        val registry = InstalledScriptProviderRegistry(
            artifactStore = artifactStore,
            configurationStore = configurationStore,
            reservedProviderIds = setOf("kitsu"),
        )

        registry.providers() shouldBe emptyList()
        registry.registration(ProviderId("kitsu")) shouldBe null
        registry.enabled(ProviderCapabilities.ReadingChaptersV1) shouldBe emptyList()
        shouldThrow<IllegalArgumentException> {
            registry.setEnabled(ProviderId("kitsu"), true)
        }
        shouldThrow<IllegalArgumentException> {
            registry.setEnabledContentLanguages(
                providerId = ProviderId("kitsu"),
                languages = setOf("en"),
            )
        }
        configurationStore.get("kitsu") shouldBe null
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

    private fun artifact(
        versionCode: Long,
        providerId: String = "reader.example",
    ): VerifiedProviderArtifact {
        val bytes = tsz(
            versionCode = versionCode,
            providerId = providerId,
        )
        return verifiedArtifact(
            providerId = providerId,
            versionName = "1.0." + versionCode,
            versionCode = versionCode,
            bytes = bytes,
        )
    }

    private fun verifiedArtifact(
        providerId: String,
        versionName: String,
        versionCode: Long,
        bytes: ByteArray,
    ): VerifiedProviderArtifact {
        val descriptor = ProviderArtifactDescriptor(
            providerId = providerId,
            versionName = versionName,
            versionCode = versionCode,
            artifactUrl = "https://repo.example/" + providerId + "-" + versionCode + ".tsz",
            sha256 = sha256Hex(bytes),
            minHostApi = 1,
        )
        val keyPair = fixtureKeyPair
        val trustStateName = "fixture-trust-" +
            providerId.replace('.', '-') +
            "-" +
            versionCode +
            "-" +
            sha256Hex(bytes).take(8)
        val trust = ProviderRepositoryTrust(
            repositoryId = "repo.example",
            hostApiVersion = 1,
            trustedKeys = mapOf("fixture-root" to keyPair.public.encoded),
            stateStore = FileProviderRepositoryTrustStore(
                tempDir.resolve(trustStateName).toFile(),
            ),
        )
        val index = ProviderRepositoryIndex(
            schemaVersion = 1,
            repositoryId = "repo.example",
            sequence = 1,
            providers = listOf(descriptor),
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
        val repository = trust.verifyAndAccept(
            SignedProviderRepositoryIndex(
                keyId = "fixture-root",
                payload = payload,
                signature = signature,
            ),
        )
        return trust.verifyArtifact(
            repository = repository,
            providerId = providerId,
            artifactBytes = bytes,
            installedVersionCode = null,
        )
    }

    private fun tsz(
        versionCode: Long,
        providerId: String = "reader.example",
    ): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "$providerId",
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
