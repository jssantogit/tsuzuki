package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest

class ProviderScriptPackageRuntimeTest {

    @Test
    fun `loads package local ES modules and invokes declared capability`() = runBlocking {
        val pkg = providerPackage(
            main = """
                import { suffix } from "./modules/helper.js";
                export default {
                  reading: {
                    chapters: async (input) => ({
                      title: input.title + suffix,
                      chapters: [1, 2, 3]
                    })
                  }
                };
            """.trimIndent(),
            modules = mapOf(
                "modules/helper.js" to """export const suffix = " ok";""",
            ),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(
            providerPackage = pkg,
            capabilityId = "reading.chapters",
            capabilityVersion = 1,
            inputJson = """{"title":"Tsuzuki"}""",
        ) shouldBe ProviderPackageExecution.Success(
            """{"title":"Tsuzuki ok","chapters":[1,2,3]}""",
        )
    }

    @Test
    fun `package capability uses only injected Host Services and preserves host failure semantics`() = runBlocking {
        val pkg = providerPackage(
            main = """
                export default {
                  reading: {
                    chapters: async () => ({
                      value: await tsuzuki.storage.get("key")
                    })
                  }
                };
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()
        val storage = object : ProviderStorageHostService {
            override suspend fun get(key: String): String? = "stored"

            override suspend fun set(key: String, value: String) = Unit

            override suspend fun remove(key: String) = Unit
        }

        runtime.invoke(
            providerPackage = pkg,
            capabilityId = "reading.chapters",
            capabilityVersion = 1,
            inputJson = "{}",
            hostServices = ProviderHostServices(storage = storage),
        ) shouldBe ProviderPackageExecution.Success("""{"value":"stored"}""")

        val failingStorage = object : ProviderStorageHostService {
            override suspend fun get(key: String): String? {
                throw IllegalStateException("host-only detail")
            }

            override suspend fun set(key: String, value: String) = Unit

            override suspend fun remove(key: String) = Unit
        }
        runtime.invoke(
            providerPackage = pkg,
            capabilityId = "reading.chapters",
            capabilityVersion = 1,
            inputJson = "{}",
            hostServices = ProviderHostServices(storage = failingStorage),
        ) shouldBe ProviderPackageExecution.Failure(ProviderPackageFailure.HOST_ERROR)
    }

    @Test
    fun `fresh module VM does not retain provider global state between invocations`() = runBlocking {
        val pkg = providerPackage(
            main = """
                globalThis.counter = (globalThis.counter || 0) + 1;
                export default {
                  reading: {
                    chapters: async () => ({ counter: globalThis.counter })
                  }
                };
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(pkg, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Success("""{"counter":1}""")
        runtime.invoke(pkg, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Success("""{"counter":1}""")
    }

    @Test
    fun `rejects invocation of undeclared capability before executing provider code`() = runBlocking {
        val pkg = providerPackage(
            main = """
                throw new Error("must not execute");
                export default {};
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(pkg, "reading.pages", 1, "{}") shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.UNDECLARED_CAPABILITY)
    }

    @Test
    fun `contract validation requires every declared capability export`() = runBlocking {
        val valid = providerPackage(
            main = """
                export default {
                  reading: {
                    chapters: async () => []
                  }
                };
            """.trimIndent(),
        )
        val missing = providerPackage(
            main = "export default { reading: {} };",
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.validateContract(valid) shouldBe ProviderPackageContract.Valid
        runtime.validateContract(missing) shouldBe
            ProviderPackageContract.Invalid(ProviderPackageFailure.MISSING_CAPABILITY_EXPORT)
    }

    @Test
    fun `module loader rejects traversal and modules outside package`() = runBlocking {
        val traversal = providerPackage(
            main = """
                import value from "../outside.js";
                export default {
                  reading: { chapters: async () => value }
                };
            """.trimIndent(),
        )
        val external = providerPackage(
            main = """
                import value from "https://evil.example/module.js";
                export default {
                  reading: { chapters: async () => value }
                };
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(traversal, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.MODULE_ERROR)
        runtime.invoke(external, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.MODULE_ERROR)
    }

    @Test
    fun `oversized provider result fails closed before Binder response`() = runBlocking {
        val pkg = providerPackage(
            main = """
                export default {
                  reading: {
                    chapters: async () => ({ value: "x".repeat(${ProviderRuntimeProtocol.MAX_RESULT_JSON_CHARS + 1}) })
                  }
                };
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(pkg, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.MALFORMED_RESULT)
    }

    @Test
    fun `malformed provider result fails closed at JSON boundary`() = runBlocking {
        val pkg = providerPackage(
            main = """
                export default {
                  reading: {
                    chapters: async () => undefined
                  }
                };
            """.trimIndent(),
        )
        val runtime = ProviderScriptPackageRuntime()

        runtime.invoke(pkg, "reading.chapters", 1, "{}") shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.MALFORMED_RESULT)
    }

    private fun providerPackage(
        main: String,
        modules: Map<String, String> = emptyMap(),
    ): ParsedProviderPackage {
        val entries = linkedMapOf<String, ByteArray>()
        entries["manifest.json"] = "{}".encodeToByteArray()
        entries["main.js"] = main.encodeToByteArray()
        modules.forEach { (path, source) ->
            entries[path] = source.encodeToByteArray()
        }
        return ParsedProviderPackage(
            manifest = ProviderScriptManifest(
                manifestVersion = 1,
                id = "org.example.reader",
                name = "Reader",
                version = ProviderManifestVersion("1.0.0", 1),
                minHostApi = 1,
                entrypoint = "main.js",
                capabilities = listOf(
                    ProviderManifestCapability("reading.chapters", 1),
                ),
            ),
            entries = entries,
        )
    }
}
