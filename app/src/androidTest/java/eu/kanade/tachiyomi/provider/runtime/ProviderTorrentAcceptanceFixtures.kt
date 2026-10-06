package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentInfo
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ProviderTorrentAcceptanceFixtures {

    data class ThreeChapterTorrentFixture(
        val torrent: TorrentInfo,
        val seedRoot: File,
        val selectedIndex: Int,
        val selectedPath: String,
        val selectedBytes: ByteArray,
        val ignoredIndexes: List<Int>,
    )

    data class RuntimeTorrentGatewayFixture(
        val providerId: ProviderId,
        val gateway: ScriptProviderTorrentGateway,
    )

    fun createThreeChapterTorrent(root: File): ThreeChapterTorrentFixture {
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }

        File(pack, "chapter-011.cbz").writeBytes(
            cbz(
                "001.png" to png(Color.RED),
            ),
        )
        val selectedBytes = cbz(
            "001.png" to png(Color.RED),
            "002.png" to png(Color.GREEN),
            "003.png" to png(Color.BLUE),
        )
        File(pack, "chapter-012.cbz").writeBytes(selectedBytes)
        File(pack, "chapter-013.cbz").writeBytes(
            cbz(
                "001.png" to png(Color.YELLOW),
            ),
        )

        val torrent = TorrentBuilder()
            .path(pack)
            .pieceSize(16 * 1024)
            .generate()
            .entry()
            .bencode()
            .let(::TorrentInfo)
        val files = torrent.files()
        val selectedIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-012.cbz") }
        val ignoredIndexes = (0 until files.numFiles())
            .filter { it != selectedIndex }

        return ThreeChapterTorrentFixture(
            torrent = torrent,
            seedRoot = seedRoot,
            selectedIndex = selectedIndex,
            selectedPath = files.filePath(selectedIndex),
            selectedBytes = selectedBytes,
            ignoredIndexes = ignoredIndexes,
        )
    }

    fun runtimeTorrentGateway(
        context: Context,
        origin: String,
    ): RuntimeTorrentGatewayFixture {
        val providerId = ProviderId("org.example.acceptance.torrent")
        val repositoryId = "acceptance.repo"
        val packageBytes = torrentProviderPackage(
            providerId = providerId.value,
            origin = origin,
        )
        val parsed = ProviderPackageParser().parse(packageBytes)
        val activePackage = ScriptProviderPackage(
            repositoryId = repositoryId,
            versionCode = 1,
            bytes = packageBytes,
            parsed = parsed,
        )
        val permissions = ProviderPermissionSet(
            network = ProviderNetworkPermission(
                origins = setOf(origin),
                localNetwork = true,
            ),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = ProviderDescriptor(
                            id = providerId,
                            name = "Acceptance Torrent Provider",
                            version = ProviderVersion("1.0.0", 1),
                            origin = ProviderOrigin.Repository(repositoryId),
                            runtime = ProviderRuntimeKind.SCRIPT,
                            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                            permissions = permissions,
                            settings = emptyList(),
                            contentLanguages = setOf("en"),
                        ),
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        configurationFingerprint = "acceptance-v1",
                    ),
                )
            },
        )
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(context),
        )
        val executor = ScriptProviderCapabilityExecutor(
            registry = registry,
            packageSource = ScriptProviderPackageSource { requested ->
                activePackage.takeIf { requested == providerId }
            },
            runtimeClient = runtimeClient,
        )
        return RuntimeTorrentGatewayFixture(
            providerId = providerId,
            gateway = ScriptProviderTorrentGateway(executor),
        )
    }

    fun localOnlyParams(port: Int): SessionParams {
        val settings = SettingsPack()
            .listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        return SessionParams(settings)
    }

    fun await(
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

    private fun torrentProviderPackage(
        providerId: String,
        origin: String,
    ): ByteArray {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "$providerId",
              "name": "Acceptance Torrent Provider",
              "version": {"name": "1.0.0", "code": 1},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id":"torrent.search","version":1}],
              "permissions": {
                "network": {
                  "origins": ["$origin"],
                  "localNetwork": true
                },
                "storage": {"enabled": false},
                "secrets": []
              },
              "contentLanguages": ["en"],
              "settings": []
            }
        """.trimIndent()
        val main = """
            async function search(input) {
              const url = new URL("$origin/search");
              url.searchParams.set("titles", Array.from(input.titles ?? []).join("|"));
              url.searchParams.set("chapter", String(input.chapterNumber ?? ""));
              url.searchParams.set("volume", String(input.volume ?? ""));
              url.searchParams.set("languages", Array.from(input.preferredLanguages ?? []).join("|"));
              const raw = await globalThis.tsuzuki.http.request(
                "GET",
                url.toString(),
                JSON.stringify({ Accept: "application/json" })
              );
              const response = JSON.parse(raw);
              if (response.statusCode < 200 || response.statusCode >= 300) {
                throw new Error("Acceptance fixture HTTP failure");
              }
              return JSON.parse(String(response.body ?? "{}"));
            }

            export default {
              torrent: { search },
            };
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
            }
            output.toByteArray()
        }
    }

    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    bitmap.setPixel(x, y, color)
                }
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
