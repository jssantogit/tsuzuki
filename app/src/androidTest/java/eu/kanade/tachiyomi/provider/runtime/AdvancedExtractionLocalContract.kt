package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Local-only protocol, resource and isolation checks in the same acceptance method. */
internal object AdvancedExtractionLocalContract {
    suspend fun run(context: Context, root: File) {
        val server = MockWebServer()
        server.start()
        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val factory = ProviderHostInvocationFactory(context, storageRoot = File(root, "local"))
        val client = ProviderRuntimeClient(context, factory)

        suspend fun invoke(script: String, providerId: String = "app.tsuzuki.test.advanced", browser: Boolean = false): tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse {
            val invocationId = "local:" + UUID.randomUUID()
            val policy = ProviderHostInvocationPolicy(
                providerId = providerId,
                invocationId = invocationId,
                networkOrigins = setOf(origin),
                browserOrigins = if (browser) setOf(origin) else emptySet(),
                allowLocalNetwork = true,
            )
            val request = ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = providerId,
                artifactVersionCode = 1,
                capabilityId = "reading.pages",
                capabilityVersion = 1,
                configurationFingerprint = "advanced-local-only",
                fileName = "main.js",
                hostModules = policy.allowedHostModules(),
                limits = ProviderRuntimeLimitsDto(wallClockTimeoutMs = 10_000L, jsExecutionTimeoutMs = 5_000L),
            )
            return client.invoke(request, script.encodeToByteArray(), policy)
        }
        try {
            val compressed = ByteArrayOutputStream().use { output ->
                GZIPOutputStream(output).use { it.write("""{"nested":{"items":[1,2,3]}}""".encodeToByteArray()) }
                output.toByteArray()
            }
            server.enqueue(MockResponse.Builder().body(Buffer().write(compressed)).build())
            val binary = invoke(
                """
                const response = JSON.parse(await tsuzuki.http.requestResource("GET", "$origin/gzip", "{}"));
                const handle = await tsuzuki.binary.transform(response.resourceHandle, "gzip");
                const page = JSON.parse(await tsuzuki.binary.inspect(handle, "json-array-page", '{"pointer":"/nested/items","offset":1,"limit":2}'));
                if (JSON.stringify(page.items) !== "[2,3]" || page.nextOffset !== null || page.total !== 3) throw Error("page");
                if (!/^[0-9a-f]{64}$/.test(await tsuzuki.binary.inspect(handle, "sha256", ""))) throw Error("digest");
                if (!(await tsuzuki.binary.release(handle))) throw Error("release");
                const xml = JSON.parse(await tsuzuki.document.query("<root><item key='x'>a</item><item>b</item></root>", "xml", "item", "key"));
                if (JSON.stringify(xml) !== '["x",null]') throw Error("xml");
                const fields = JSON.parse(await tsuzuki.protocol.protobufFields("CKwCEgFh"));
                if (fields.length !== 2 || fields[0].field !== 1 || fields[0].value !== "300" || fields[1].value !== "YQ==") throw Error("protobuf");
                if (await tsuzuki.protocol.jsonPointer('{"a/b":{"~":true}}', "/a~1b/~0") !== "true") throw Error("pointer");
                "ok";
                """.trimIndent(),
            )
            assertNull(binary.failure)
            assertEquals("ok", binary.value)
            assertNotNull(invoke("""await tsuzuki.protocol.protobufFields("gA==")""").failure)
            assertNotNull(invoke("""await tsuzuki.protocol.jsonPointer('{"a":1}', "/missing")""").failure)
            server.enqueue(MockResponse(body = "abc"))
            val leaked = invoke("""JSON.parse(await tsuzuki.http.requestResource("GET", "$origin/raw", "{}")).resourceHandle""")
            assertNull(leaked.failure)
            val handle = requireNotNull(leaked.value)
            assertTrue(handle.startsWith("res:"))
            assertNotNull(invoke("""await tsuzuki.binary.inspect("$handle", "sha256", "")""", "app.tsuzuki.test.other").failure)
            assertNotNull(invoke("""await tsuzuki.binary.inspect("$handle", "sha256", "")""").failure)
            server.enqueue(MockResponse(headers = headersOf("Set-Cookie", "probe=alpha; Path=/"), body = "one"))
            assertNull(invoke("""await tsuzuki.http.get("$origin/cookie")""").failure)
            server.enqueue(MockResponse(body = "two"))
            assertNull(invoke("""await tsuzuki.http.get("$origin/cookie")""").failure)
            server.enqueue(MockResponse(body = "three"))
            assertNull(invoke("""await tsuzuki.http.get("$origin/cookie")""", "app.tsuzuki.test.other").failure)
            // Drain the known five calls; no blocking wait for an unexpected request.
            val requests = (1..5).map { requireNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals("probe=alpha", requests[3].headers["Cookie"])
            assertNull(requests[4].headers["Cookie"])
            server.enqueue(
                MockResponse(
                    headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                    body = """<html><body><script>setTimeout(()=>{const e=document.createElement('div');e.id='late';e.setAttribute('data-value','ready');document.body.appendChild(e)},250)</script></body></html>""",
                ),
            )
            val rendered = invoke("""await tsuzuki.browser.query("$origin/dynamic", "#late", "data-value")""", browser = true)
            assertNull(rendered.failure)
            assertEquals("[\"ready\"]", rendered.value)
        } finally {
            server.close()
        }
    }
}
