package eu.kanade.tachiyomi.provider.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.supplychain.FileProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.ProviderArtifactDescriptor
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact
import tachiyomi.core.provider.supplychain.sha256Hex
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import java.io.ByteArrayOutputStream
import java.nio.file.Path
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
