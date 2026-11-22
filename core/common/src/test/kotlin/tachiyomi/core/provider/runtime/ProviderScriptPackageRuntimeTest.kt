package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestMangaAllChapterSnapshotPermission
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
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

    @Test
    fun `future MangaALL snapshots require exact manifest scope and code11`(): Unit = runBlocking {
        val main = """
            export default {
              reading: {
                chapters: async () => {
                  const first = JSON.parse(await tsuzuki.mangaAllChapterSnapshot.start("mangadot:131", "en"));
                  const next = JSON.parse(await tsuzuki.mangaAllChapterSnapshot.next("mangadot:131", "en", first.nextCursor));
                  return { chapters: first.items.concat(next.items).map(x => x.id) };
                }
              }
            };
        """.trimIndent()
        val original = providerPackage(main)
        val granted = original.copy(
            manifest = original.manifest.copy(
                id = "app.tsuzuki.mangaall",
                version = ProviderManifestVersion("0.6.0-unsigned", 11),
                minHostApi = 2,
                permissions = ProviderManifestPermissions(
                    network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                    mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(enabled = true),
                ),
            ),
        )
        val calls = mutableListOf<String>()
        val bridge = object : ProviderMangaAllChapterSnapshotHostService {
            override suspend fun start(workId: String, language: String): String {
                calls += "start:$workId:$language"
                return """{"items":[{"id":"first"}],"nextCursor":"m1:131:en:0000000000000001:1"}"""
            }

            override suspend fun next(workId: String, language: String, cursor: String): String {
                calls += "next:$workId:$language:$cursor"
                return """{"items":[{"id":"second"}],"nextCursor":null}"""
            }
        }
        val services = ProviderHostServices(mangaAllChapterSnapshot = bridge)
        val runtime = ProviderScriptPackageRuntime()
        runtime.invoke(granted, "reading.chapters", 1, "{}", services) shouldBe
            ProviderPackageExecution.Success("""{"chapters":["first","second"]}""")
        calls.size shouldBe 2

        // All invalid/legacy grants deny the *same injected* native bridge.
        val denied = listOf(
            granted.copy(manifest = granted.manifest.copy(version = ProviderManifestVersion("0.5.5", 10))),
            granted.copy(manifest = granted.manifest.copy(id = "foreign.provider")),
            granted.copy(manifest = granted.manifest.copy(minHostApi = 1)),
            granted.copy(manifest = granted.manifest.copy(permissions = ProviderManifestPermissions())),
        )
        for (candidate in denied) {
            runtime.invoke(candidate, "reading.chapters", 1, "{}", services) shouldBe
                ProviderPackageExecution.Failure(ProviderPackageFailure.MODULE_ERROR)
        }
        calls.size shouldBe 2
    }

    @Test
    fun `future Host JS bridge rejects oversized and malformed native page results`(): Unit = runBlocking {
        val base = providerPackage(
            main = """
                export default {
                  reading: { chapters: async () =>
                    JSON.parse(await tsuzuki.mangaAllChapterSnapshot.start("mangadot:131", "en")) }
                };
            """.trimIndent(),
        )
        val pkg = base.copy(
            manifest = base.manifest.copy(
                id = "app.tsuzuki.mangaall",
                version = ProviderManifestVersion("0.6.0-unsigned", 11),
                minHostApi = 2,
                permissions = ProviderManifestPermissions(
                    network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                    mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(true),
                ),
            ),
        )
        val runtime = ProviderScriptPackageRuntime()
        for (badResult in listOf("not-json", "x".repeat(60 * 1024))) {
            val bridge = object : ProviderMangaAllChapterSnapshotHostService {
                override suspend fun start(workId: String, language: String) = badResult
                override suspend fun next(workId: String, language: String, cursor: String) =
                    error("Not reached")
            }
            runtime.invoke(
                pkg,
                "reading.chapters",
                1,
                "{}",
                ProviderHostServices(mangaAllChapterSnapshot = bridge),
            ) shouldBe ProviderPackageExecution.Failure(ProviderPackageFailure.HOST_ERROR)
        }
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
