package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.provider.torrent.ScriptProviderTorrentGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Temporary diagnostic only. Public Nyaa availability must never become a required merge gate.
 */
@RunWith(AndroidJUnit4::class)
class ProviderNyaaLiveHostDiagnosticTest {

    @Test
    fun liveNyaaRss_hasItemsThroughRealScriptRuntimeAndAndroidHost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activePackage = createLiveProbePackage()
        val storageRoot = File(context.cacheDir, "provider-nyaa-live-diagnostic").apply {
            deleteRecursively()
        }
        val runtimeClient = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
            ),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(registration(activePackage))
            },
        )
        val executor = ScriptProviderCapabilityExecutor(
            registry = registry,
            packageSource = ScriptProviderPackageSource { requested ->
                activePackage.takeIf { requested == PROVIDER_ID }
            },
            runtimeClient = runtimeClient,
        )
        val gateway = ScriptProviderTorrentGateway(executor)

        try {
            val result = gateway.search(
                PROVIDER_ID,
                TorrentSearchRequest(titles = listOf("One Piece")),
            )

            assertTrue(
                "live Nyaa RSS must be reachable through the real Android Host; result=$result",
                result is ProviderCallResult.Success && result.value.items.isNotEmpty(),
            )
        } finally {
            storageRoot.deleteRecursively()
        }
    }

    private fun createLiveProbePackage(): ScriptProviderPackage {
        val manifest = """
            {
              "manifestVersion": 1,
              "id": "$PROVIDER_ID_VALUE",
              "name": "Nyaa Live Android Diagnostic",
              "version": {"name": "1.0.0", "code": 1},
              "minHostApi": 1,
              "entrypoint": "main.js",
              "capabilities": [{"id": "torrent.search", "version": 1}],
              "permissions": {
                "network": {
                  "origins": ["https://nyaa.si"],
                  "localNetwork": false
                }
              },
              "contentLanguages": [],
              "settings": []
            }
        """.trimIndent()
        val main = """
            const ENDPOINT = "https://nyaa.si/?page=rss&c=3_0&f=0&q=One%20Piece";
            const INFO_HASH = "0123456789abcdef0123456789abcdef01234567";

            export default {
              torrent: {
                search: async () => {
                  const encoded = await tsuzuki.http.request(
                    "GET",
                    ENDPOINT,
                    JSON.stringify({ Accept: "application/rss+xml,application/xml,text/xml" }),
                  );
                  const response = JSON.parse(encoded);
                  if (response.statusCode < 200 || response.statusCode >= 300) {
                    throw new Error("Nyaa diagnostic HTTP " + response.statusCode);
                  }
                  const body = String(response.body ?? "");
                  const withoutDeclaration = body.trim().replace(/^<\\?xml[^>]*>\\s*/i, "");
                  if (!/^<rss\\b/i.test(withoutDeclaration)) {
                    throw new Error("Nyaa diagnostic response was not RSS");
                  }
                  const hasItem = /<item\\b/i.test(body);
                  return {
                    items: hasItem ? [{
                      infoHash: INFO_HASH,
                      magnetUri: "magnet:?xt=urn:btih:" + INFO_HASH,
                      displayName: "Live Nyaa RSS item observed",
                    }] : [],
                    nextCursor: null,
                  };
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
            repositoryId = REPOSITORY_ID,
            versionCode = 1L,
            bytes = bytes,
            parsed = parsed,
        )
    }

    private fun registration(activePackage: ScriptProviderPackage): ProviderRegistration {
        val manifest = activePackage.parsed.manifest
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = ProviderId(manifest.id),
                name = manifest.name,
                version = ProviderVersion(manifest.version.name, manifest.version.code),
                origin = ProviderOrigin.Repository(activePackage.repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = manifest.contentLanguages,
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
            configurationFingerprint = "nyaa-live-diagnostic-v1",
            enabledCapabilities = setOf(ProviderCapabilities.TorrentSearchV1),
        )
    }

    private fun providerPackage(entries: Map<String, ByteArray>): ByteArray =
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

    private companion object {
        const val PROVIDER_ID_VALUE = "org.example.nyaa.live-diagnostic"
        val PROVIDER_ID = ProviderId(PROVIDER_ID_VALUE)
        const val REPOSITORY_ID = "diagnostic.repo"
    }
}
