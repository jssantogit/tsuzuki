package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.Color
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentInfo
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProviderTorrentAcceptanceFixtures {

    data class ThreeChapterTorrent(
        val torrent: TorrentInfo,
        val seedRoot: File,
        val selectedFileIndex: Int,
        val selectedFilePath: String,
        val selectedArchiveBytes: ByteArray,
        val unselectedFileIndexes: List<Int>,
    )

    fun createThreeChapterTorrent(root: File): ThreeChapterTorrent {
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }

        val chapter011 = cbz(
            listOf("001.png" to tinyPng(Color.RED)),
        )
        val chapter012 = cbz(
            listOf(
                "003.png" to tinyPng(Color.BLUE),
                "README.txt" to "fixture-not-image".encodeToByteArray(),
                "001.png" to tinyPng(Color.RED),
                "002.png" to tinyPng(Color.GREEN),
            ),
        )
        val chapter013 = cbz(
            listOf("001.png" to tinyPng(Color.BLUE)),
        )

        File(pack, "chapter-011.cbz").writeBytes(chapter011)
        File(pack, "chapter-012.cbz").writeBytes(chapter012)
        File(pack, "chapter-013.cbz").writeBytes(chapter013)

        val torrent = TorrentBuilder()
            .path(pack)
            .pieceSize(16 * 1024)
            .generate()
            .entry()
            .bencode()
            .let(::TorrentInfo)
        val files = torrent.files()
        val selectedFileIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-012.cbz") }
        val selectedFilePath = files.filePath(selectedFileIndex)
        val unselectedFileIndexes = (0 until files.numFiles())
            .filterNot { it == selectedFileIndex }

        return ThreeChapterTorrent(
            torrent = torrent,
            seedRoot = seedRoot,
            selectedFileIndex = selectedFileIndex,
            selectedFilePath = selectedFilePath,
            selectedArchiveBytes = chapter012,
            unselectedFileIndexes = unselectedFileIndexes,
        )
    }

    fun createTorrentSearchProviderPackage(
        providerId: String,
        repositoryId: String,
        origin: String,
        endpoint: String,
    ): ScriptProviderPackage {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": ${jsonString(providerId)},
              "name": "Torrent Acceptance Provider",
              "version": {"name": "1.0.0", "code": 1},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id": "torrent.search", "version": 1}],
              "permissions": {
                "network": {
                  "origins": [${jsonString(origin)}],
                  "localNetwork": true
                }
              },
              "contentLanguages": ["en"],
              "settings": []
            }
        """.trimIndent()
        val main = """
            const endpoint = ${jsonString(endpoint)};

            export default {
              torrent: {
                search: async (input) => {
                  const headers = JSON.stringify({
                    "X-Tsuzuki-Titles": (input.titles ?? []).join("|"),
                    "X-Tsuzuki-Languages": (input.preferredLanguages ?? []).join("|"),
                    "X-Tsuzuki-Chapter": input.chapterNumber ?? "",
                    "X-Tsuzuki-Volume": input.volume == null ? "" : String(input.volume),
                  });
                  const encoded = await tsuzuki.http.request("GET", endpoint, headers);
                  const response = JSON.parse(encoded);
                  if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw new Error(`fixture HTTP ${'$'}{response.statusCode}`);
                  }
                  return JSON.parse(response.body);
                },
              },
            };
        """.trimIndent()
        val bytes = providerPackage(
            mapOf(
                "manifest.json" to manifest.encodeToByteArray(),
                "main.js" to main.encodeToByteArray(),
            ),
        )
        val parsed = ProviderPackageParser().parse(bytes)
        return ScriptProviderPackage(
            repositoryId = repositoryId,
            versionCode = 1L,
            bytes = bytes,
            parsed = parsed,
        )
    }

    fun createDirectP2pProviderPackage(
        providerId: String,
        repositoryId: String,
    ): ScriptProviderPackage {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": ${jsonString(providerId)},
              "name": "Direct P2P Acceptance Provider",
              "version": {"name": "1.0.0", "code": 1},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id": "acquisition.p2p", "version": 1}],
              "permissions": {
                "storage": {"enabled": false},
                "secrets": []
              },
              "contentLanguages": [],
              "settings": []
            }
        """.trimIndent()
        val main = """
            async function p2p(input) {
              const torrent = input.torrent ?? {};
              const file = input.file ?? {};
              const request = {
                operationId: input.operationId,
                magnetUri: torrent.magnetUri ?? null,
                torrentUrl: torrent.torrentUrl ?? null,
                infoHash: torrent.infoHash ?? null,
                selectedFileIndex: file.index,
                selectedFilePath: file.path,
                selectedFileSizeBytes: file.sizeBytes ?? null,
              };
              const encoded = await tsuzuki.p2p.acquire(JSON.stringify(request));
              const response = JSON.parse(encoded);
              if (response.status === "pending") {
                return { status: "pending", jobId: response.jobId };
              }
              if (response.status === "ready") {
                return {
                  status: "ready",
                  resource: response.resource,
                  format: response.format,
                };
              }
              throw new Error(`fixture P2P failure: ${'$'}{response.failure ?? response.status}`);
            }

            export default {
              acquisition: {
                p2p,
              },
            };
        """.trimIndent()
        val bytes = providerPackage(
            mapOf(
                "manifest.json" to manifest.encodeToByteArray(),
                "main.js" to main.encodeToByteArray(),
            ),
        )
        val parsed = ProviderPackageParser().parse(bytes)
        return ScriptProviderPackage(
            repositoryId = repositoryId,
            versionCode = 1L,
            bytes = bytes,
            parsed = parsed,
        )
    }

    fun localOnlyParams(port: Int): SessionParams {
        val settings = SettingsPack()
            .listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        return SessionParams(settings)
    }

    fun startLoopbackSeeder(
        seeder: SessionManager,
        fixture: ThreeChapterTorrent,
        port: Int,
    ) {
        seeder.start(localOnlyParams(port))
        seeder.download(fixture.torrent, fixture.seedRoot)
        await("loopback seeder ready") {
            seeder.find(fixture.torrent)?.status(true)?.isSeeding == true
        }
    }

    fun stopLoopbackSeeder(seeder: SessionManager) {
        Thread { seeder.stop() }.apply {
            start()
            join(10_000)
        }
    }

    private fun providerPackage(entries: Map<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private fun await(
        label: String,
        timeoutMs: Long = 30_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private fun cbz(entries: List<Pair<String, ByteArray>>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun tinyPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, color)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
