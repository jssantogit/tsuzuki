package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.zip.ZipInputStream

/** One controlled acceptance gate. Actual adapters, synthetic upstream and pixels; no live requests. */
@RunWith(AndroidJUnit4::class)
class ProviderAdvancedExtractionInstrumentedTest {
    @Test
    fun bothAdaptersThroughAdvancedExtraction(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "advanced-extraction-" + UUID.randomUUID()).apply { mkdirs() }
        val image = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.setPixel(0, 0, Color.RED)
            bitmap.setPixel(1, 0, Color.BLUE)
            ByteArrayOutputStream().use { output ->
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val ballList = buildString {
            append("""{"code":200,"data":[{"id":"ballch1","number":1,"language":"en","name":"Chapter 1"}""")
            for (index in 2..320) {
                append(""",{"id":"foreign$index","number":$index,"language":"fr","ignored":"""")
                append("x".repeat(900))
                append("\"}")
            }
            append("]}")
        }
        assertTrue(ballList.encodeToByteArray().size > 192 * 1024)
        val http = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url
                val bytes = when (url.host to url.encodedPath) {
                    "mangaball.com" to "/api/v1/title/search-advanced" ->
                        """{"code":200,"data":[{"id":"ballfixture","name":"Fixture Work"}],"pagination":{"current_page":1,"last_page":1}}""".encodeToByteArray()
                    "mangaball.com" to "/api/v1/chapter/chapter-listing-by-title-id" -> {
                        assertEquals("POST", request.method)
                        assertEquals("application/json", request.body?.contentType()?.toString())
                        ballList.encodeToByteArray()
                    }
                    "mangaball.com" to "/api/v1/chapter-detail" ->
                        """{"data":{"chapter":{"title_id":"ballfixture","pages":["https://mangaball.com/fixture.png"]}}}""".encodeToByteArray()
                    "mangafire.to" to "/api/titles" ->
                        """{"items":[{"hid":"firefixture","title":"Fixture Work"}]}""".encodeToByteArray()
                    "mangafire.to" to "/api/titles/firefixture/chapters" -> {
                        if (url.queryParameter("page") == "1") {
                            """{"items":[{"id":101,"number":1,"name":"Chapter 1","language":"en"}],"meta":{"hasNext":true,"lastPage":2}}""".encodeToByteArray()
                        } else {
                            """{"items":[{"id":102,"number":2,"name":"Chapter 2","language":"en"}],"meta":{"hasNext":false,"lastPage":2}}""".encodeToByteArray()
                        }
                    }
                    "mangafire.to" to "/api/chapters/101" ->
                        """{"data":{"id":101,"mangaHid":"firefixture","language":"en","pages":[{"url":"https://mangafire.to/fixture.png"}]}}""".encodeToByteArray()
                    "mangaball.com" to "/fixture.png", "mangafire.to" to "/fixture.png" -> image
                    else -> throw AssertionError("Unexpected controlled route")
                }
                // Never call chain.proceed: every upstream response is synthetic.
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody())
                    .build()
            }.build()
        val managed = ProviderManagedFileStore(File(root, "managed"), { it.absolutePath })
        val client = ProviderRuntimeClient(
            context,
            ProviderHostInvocationFactory(context, storageRoot = root, baseHttpClient = http, managedFiles = managed),
        )
        val failures = mutableListOf<String>()
        try {
            for (name in listOf("mangaall-ball", "mangafire")) {
                try {
                    val candidate = instrumentation.context.assets.open("advanced-$name-unsigned.zip").use { it.readBytes() }
                    val manifest = ProviderPackageParser().parse(candidate).manifest
                    val ball = name == "mangaall-ball"
                    val work = if (ball) "mangaball:ballfixture" else "firefixture"
                    val chapter = if (ball) "mangaball:ballch1" else "101"
                    val binding = JsonObject(
                        mapOf(
                            "providerId" to JsonPrimitive(manifest.id),
                            "externalWorkId" to JsonPrimitive(work),
                            "facetId" to JsonPrimitive("en"),
                        ),
                    )
                    suspend fun invoke(capability: String, input: String): JsonObject {
                        val invocationId = "advanced:" + UUID.randomUUID()
                        val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId).forCapability(capability)
                        assertTrue(policy.browserOrigins.isEmpty())
                        val request = ProviderRuntimeInvocationRequest(
                            protocolVersion = ProviderRuntimeProtocol.VERSION,
                            invocationId = invocationId,
                            providerId = manifest.id,
                            artifactVersionCode = manifest.version.code,
                            capabilityId = capability,
                            capabilityVersion = 1,
                            configurationFingerprint = "advanced-extraction-controlled",
                            fileName = "main.js",
                            hostModules = policy.allowedHostModules(),
                            limits = ProviderRuntimeLimitsDto(wallClockTimeoutMs = 20_000L, jsExecutionTimeoutMs = 10_000L),
                        )
                        val response = client.invokePackage(request, candidate, input, policy)
                        assertNull("$name $capability", response.failure)
                        return Json.parseToJsonElement(requireNotNull(response.value)).jsonObject
                    }
                    val lookup = invoke("reading.lookup", """{"titles":["Fixture Work"],"requestedLanguage":"en"}""")
                    val item = lookup["items"]!!.jsonArray.single().jsonObject
                    assertEquals(work, item["externalWorkId"]!!.jsonPrimitive.content)
                    assertEquals("Fixture Work", item["title"]!!.jsonPrimitive.content)
                    assertEquals(JsonNull, lookup["nextCursor"])
                    val first = invoke("reading.chapters", """{"binding":$binding}""")
                    assertEquals(chapter, first["items"]!!.jsonArray.single().jsonObject["providerChapterId"]!!.jsonPrimitive.content)
                    if (ball) {
                        assertEquals(JsonNull, first["nextCursor"])
                    } else {
                        assertEquals("2", first["nextCursor"]!!.jsonPrimitive.content)
                        val second = invoke("reading.chapters", """{"binding":$binding,"cursor":"2"}""")
                        assertEquals("102", second["items"]!!.jsonArray.single().jsonObject["providerChapterId"]!!.jsonPrimitive.content)
                        assertEquals(JsonNull, second["nextCursor"])
                    }
                    val pages = invoke("reading.pages", """{"binding":$binding,"providerChapterId":"$chapter"}""")
                    assertEquals(1, pages["pages"]!!.jsonArray.size)
                    val token = ProviderManagedResourceRef(pages["testManaged"]!!.jsonPrimitive.content)
                    val archive = managed.resolve(ProviderId(manifest.id), token, ProviderManagedFileFormat.CBZ)
                    assertNotNull(archive)
                    assertNull(managed.resolve(ProviderId("app.tsuzuki.unrelated"), token, ProviderManagedFileFormat.CBZ))
                    ZipInputStream(File(requireNotNull(archive)).inputStream()).use { zip ->
                        assertEquals("00001.png", zip.nextEntry?.name)
                        val bytes = zip.readBytes()
                        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        try {
                            assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
                            assertEquals(Color.RED, bitmap.getPixel(1, 0))
                        } finally {
                            bitmap.recycle()
                        }
                        assertNull(zip.nextEntry)
                    }
                } catch (failure: Throwable) {
                    failures += "$name: " + (failure.message ?: failure.javaClass.simpleName)
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
