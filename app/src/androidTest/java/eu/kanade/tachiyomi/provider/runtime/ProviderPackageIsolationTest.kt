package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import tachiyomi.core.provider.packageformat.ProviderPackageException
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderPackageValidationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.supplychain.ProviderArtifactDescriptor
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.VerifiedProviderArtifact
import tachiyomi.core.provider.supplychain.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ProviderPackageIsolationTest {

    @Test
    fun tsz_validation_and_capability_invocation_run_through_isolated_runtime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageRoot = File(context.cacheDir, "provider-package-runtime").apply {
            deleteRecursively()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
            ),
        )
        val packageBytes = tsz(
            main = """
                import { suffix } from "./modules/helper.js";
                export default {
                  reading: {
                    chapters: async (input) => ({
                      title: input.title + suffix,
                      count: 3
                    })
                  }
                };
            """.trimIndent(),
            modules = mapOf(
                "modules/helper.js" to """export const suffix = " isolated";""",
            ),
        )

        val validation = client.validatePackage(
            request = ProviderPackageValidationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = "validate:${UUID.randomUUID()}",
                providerId = PROVIDER_ID,
                artifactVersionCode = 1,
                limits = ProviderRuntimeLimitsDto(),
            ),
            packageBytes = packageBytes,
        )
        assertNull(validation.failure)
        assertEquals("valid", validation.value)

        val invocationId = "invoke:${UUID.randomUUID()}"
        val response = client.invokePackage(
            request = ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = PROVIDER_ID,
                artifactVersionCode = 1,
                capabilityId = "reading.chapters",
                capabilityVersion = 1,
                configurationFingerprint = "test",
                fileName = "main.js",
                hostModules = emptySet(),
                limits = ProviderRuntimeLimitsDto(),
            ),
            packageBytes = packageBytes,
            inputJson = """{"title":"Tsuzuki"}""",
            hostPolicy = ProviderHostInvocationPolicy(
                providerId = PROVIDER_ID,
                invocationId = invocationId,
            ),
        )

        assertNull(response.failure)
        assertEquals("""{"title":"Tsuzuki isolated","count":3}""", response.value)
        storageRoot.deleteRecursively()
    }

    @Test
    fun isolated_contract_failure_preserves_previous_active_artifact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-package-activation").apply {
            deleteRecursively()
            mkdirs()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = File(root, "storage"),
            ),
        )
        val store = ProviderArtifactStore(File(root, "artifacts"))
        val activator = ProviderPackageActivator(
            hostApiVersion = 1,
            parser = ProviderPackageParser(),
            artifactStore = store,
            contractValidator = IsolatedProviderPackageContractValidator(client),
        )

        val v1 = tsz(
            versionCode = 1,
            main = """
                export default {
                  reading: { chapters: async () => [] }
                };
            """.trimIndent(),
        )
        activator.activate(verified(versionCode = 1, bytes = v1))
        assertEquals(1L, store.current(PROVIDER_ID)?.versionCode)

        val invalidV2 = tsz(
            versionCode = 2,
            main = "export default { reading: {} };",
        )
        val error = runCatching {
            activator.activate(verified(versionCode = 2, bytes = invalidV2))
        }.exceptionOrNull()

        assertTrue(error is ProviderPackageException)
        assertEquals(1L, store.current(PROVIDER_ID)?.versionCode)
        assertEquals(v1.toList(), store.readCurrentArtifact(PROVIDER_ID).toList())
        root.deleteRecursively()
    }

    @Test
    fun package_with_external_module_import_fails_closed_inside_isolated_runtime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(context),
        )
        val packageBytes = tsz(
            main = """
                import value from "https://evil.example/module.js";
                export default {
                  reading: { chapters: async () => value }
                };
            """.trimIndent(),
        )

        val response = client.validatePackage(
            request = ProviderPackageValidationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = "validate-external:${UUID.randomUUID()}",
                providerId = PROVIDER_ID,
                artifactVersionCode = 1,
                limits = ProviderRuntimeLimitsDto(),
            ),
            packageBytes = packageBytes,
        )

        assertEquals(ProviderRuntimeFailureCode.PACKAGE_INVALID, response.failure)
    }

    private fun verified(
        versionCode: Long,
        bytes: ByteArray,
    ) = VerifiedProviderArtifact(
        repositoryId = "repo.example",
        descriptor = ProviderArtifactDescriptor(
            providerId = PROVIDER_ID,
            versionName = "1.0.$versionCode",
            versionCode = versionCode,
            artifactUrl = "https://repo.example/provider-$versionCode.tsz",
            sha256 = sha256Hex(bytes),
            minHostApi = 1,
        ),
        bytes = bytes,
    )

    private fun tsz(
        versionCode: Long = 1,
        main: String,
        modules: Map<String, String> = emptyMap(),
    ): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "$PROVIDER_ID",
              "name": "Provider Example",
              "version": {"name": "1.0.$versionCode", "code": $versionCode},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id":"reading.chapters","version":1}],
              "permissions": {},
              "contentLanguages": ["en"],
              "settings": []
            }
        """.trimIndent()

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.encodeToByteArray())
                    zip.closeEntry()
                }

                entry("manifest.json", manifest)
                entry("main.js", main)
                modules.forEach(::entry)
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val PROVIDER_ID = "org.example.reader"
    }
}
