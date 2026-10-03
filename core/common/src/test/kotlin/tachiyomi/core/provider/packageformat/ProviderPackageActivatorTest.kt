package tachiyomi.core.provider.packageformat

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.supplychain.ProviderArtifactDescriptor
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact
import tachiyomi.core.provider.supplychain.sha256Hex
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderPackageActivatorTest {

    @TempDir
    lateinit var tempDir: Path

    private val parser = ProviderPackageParser(
        ProviderPackageLimits(
            maxEntries = 16,
            maxEntryBytes = 64 * 1024,
            maxTotalUncompressedBytes = 256 * 1024,
        ),
    )

    @Test
    fun `activation validates signed descriptor against manifest before switching current version`() {
        val store = ProviderArtifactStore(tempDir.resolve("artifacts").toFile())
        val activator = ProviderPackageActivator(
            hostApiVersion = 3,
            parser = parser,
            artifactStore = store,
        )

        val v1Bytes = tsz(manifest(id = "reader.example", versionName = "1.0.1", versionCode = 1))
        activator.activate(verified("reader.example", "1.0.1", 1, 1, v1Bytes))
        store.current("reader.example")?.versionCode shouldBe 1L

        val wrongIdentity = tsz(manifest(id = "other.example", versionName = "1.0.2", versionCode = 2))
        shouldThrow<ProviderPackageException> {
            activator.activate(verified("reader.example", "1.0.2", 2, 1, wrongIdentity))
        }
        store.current("reader.example")?.versionCode shouldBe 1L

        val wrongVersion = tsz(manifest(id = "reader.example", versionName = "9.9.9", versionCode = 2))
        shouldThrow<ProviderPackageException> {
            activator.activate(verified("reader.example", "1.0.2", 2, 1, wrongVersion))
        }
        store.current("reader.example")?.versionCode shouldBe 1L
    }

    @Test
    fun `failed package validation preserves the previously active artifact`() {
        val store = ProviderArtifactStore(tempDir.resolve("failed-update").toFile())
        val activator = ProviderPackageActivator(
            hostApiVersion = 3,
            parser = parser,
            artifactStore = store,
        )

        val v1Bytes = tsz(manifest(id = "reader.example", versionName = "1.0.1", versionCode = 1))
        activator.activate(verified("reader.example", "1.0.1", 1, 1, v1Bytes))

        val invalidV2 = tsz(
            manifest(id = "reader.example", versionName = "1.0.2", versionCode = 2),
            includeEntrypoint = false,
        )
        shouldThrow<ProviderPackageException> {
            activator.activate(verified("reader.example", "1.0.2", 2, 1, invalidV2))
        }

        store.current("reader.example")?.versionCode shouldBe 1L
        store.readCurrentArtifact("reader.example") shouldBe v1Bytes
    }

    @Test
    fun `manifest host api must agree with the signed repository descriptor and local host`() {
        val store = ProviderArtifactStore(tempDir.resolve("host-api").toFile())
        val activator = ProviderPackageActivator(
            hostApiVersion = 2,
            parser = parser,
            artifactStore = store,
        )

        val mismatch = tsz(
            manifest(
                id = "reader.example",
                versionName = "1.0.1",
                versionCode = 1,
                minHostApi = 3,
            ),
        )
        shouldThrow<ProviderPackageException> {
            activator.activate(verified("reader.example", "1.0.1", 1, 2, mismatch))
        }
        store.current("reader.example") shouldBe null
    }

    private fun verified(
        providerId: String,
        versionName: String,
        versionCode: Long,
        minHostApi: Int,
        bytes: ByteArray,
    ) = VerifiedProviderArtifact(
        descriptor = ProviderArtifactDescriptor(
            providerId = providerId,
            versionName = versionName,
            versionCode = versionCode,
            artifactUrl = "https://repo.example/$providerId-$versionCode.tsz",
            sha256 = sha256Hex(bytes),
            minHostApi = minHostApi,
        ),
        bytes = bytes,
    )

    private fun manifest(
        id: String,
        versionName: String,
        versionCode: Long,
        minHostApi: Int = 1,
    ): String = """
        {
          "manifestVersion": 1,
          "id": "$id",
          "name": "Reader Example",
          "version": {"name": "$versionName", "code": $versionCode},
          "minHostApi": $minHostApi,
          "entrypoint": "main.js",
          "capabilities": [{"id":"reading.chapters","version":1}],
          "permissions": {},
          "contentLanguages": ["en"],
          "settings": []
        }
    """.trimIndent()

    private fun tsz(
        manifest: String,
        includeEntrypoint: Boolean = true,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.encodeToByteArray())
            zip.closeEntry()
            if (includeEntrypoint) {
                zip.putNextEntry(ZipEntry("main.js"))
                zip.write("export default {}".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
