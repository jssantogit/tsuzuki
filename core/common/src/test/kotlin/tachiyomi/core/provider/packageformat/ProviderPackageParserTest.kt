package tachiyomi.core.provider.packageformat

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderPackageParserTest {

    private val parser = ProviderPackageParser(
        limits = ProviderPackageLimits(
            maxEntries = 16,
            maxEntryBytes = 64 * 1024,
            maxTotalUncompressedBytes = 256 * 1024,
        ),
    )

    @Test
    fun `parses a bounded v1 tsz package and preserves manifest contract`() {
        val parsed = parser.parse(
            tsz(
                "manifest.json" to manifest(),
                "main.js" to "export default { reading: {} }",
                "modules/helper.js" to "export const value = 1",
                "assets/icon.svg" to "<svg/>",
            ),
        )

        parsed.manifest.manifestVersion shouldBe 1
        parsed.manifest.id shouldBe "reader.example"
        parsed.manifest.version.code shouldBe 2L
        parsed.manifest.entrypoint shouldBe "main.js"
        parsed.manifest.capabilities.single().id shouldBe "reading.chapters"
        parsed.entries.keys shouldBe setOf(
            "manifest.json",
            "main.js",
            "modules/helper.js",
            "assets/icon.svg",
        )
    }

    @Test
    fun `rejects traversal absolute paths and executable payloads`() {
        listOf(
            "../escape.js" to "x",
            "/absolute.js" to "x",
            "modules\\escape.js" to "x",
            "payload.apk" to "x",
            "payload.dex" to "x",
            "payload.jar" to "x",
            "payload.so" to "x",
            "payload.wasm" to "x",
        ).forEach { (path, content) ->
            shouldThrow<ProviderPackageException> {
                parser.parse(
                    tsz(
                        "manifest.json" to manifest(),
                        "main.js" to "export default {}",
                        path to content,
                    ),
                )
            }
        }
    }

    @Test
    fun `rejects missing entrypoint malformed manifest oversized content and corrupt zip`() {
        shouldThrow<ProviderPackageException> {
            parser.parse(tsz("manifest.json" to manifest(), "other.js" to "x"))
        }
        shouldThrow<ProviderPackageException> {
            parser.parse(tsz("manifest.json" to "{not-json}", "main.js" to "x"))
        }
        shouldThrow<ProviderPackageException> {
            parser.parse(
                tsz(
                    "manifest.json" to manifest(),
                    "main.js" to "x".repeat(70 * 1024),
                ),
            )
        }
        shouldThrow<ProviderPackageException> {
            parser.parse("not-a-zip".encodeToByteArray())
        }
    }

    @Test
    fun `rejects manifest capabilities with duplicate ids and versions`() {
        shouldThrow<ProviderPackageException> {
            parser.parse(
                tsz(
                    "manifest.json" to manifest(
                        capabilities = """
                            [
                              {"id":"reading.chapters","version":1},
                              {"id":"reading.chapters","version":1}
                            ]
                        """.trimIndent(),
                    ),
                    "main.js" to "export default {}",
                ),
            )
        }
    }

    private fun manifest(
        capabilities: String = """[{"id":"reading.chapters","version":1}]""",
    ): String = """
        {
          "manifestVersion": 1,
          "id": "reader.example",
          "name": "Reader Example",
          "version": {"name": "1.0.2", "code": 2},
          "minHostApi": 1,
          "entrypoint": "main.js",
          "capabilities": $capabilities,
          "permissions": {
            "network": {
              "origins": ["https://reader.example"],
              "localNetwork": false
            },
            "browser": {"origins": []},
            "storage": {"enabled": true},
            "secrets": ["session"]
          },
          "contentLanguages": ["en"],
          "settings": []
        }
    """.trimIndent()

    private fun tsz(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
