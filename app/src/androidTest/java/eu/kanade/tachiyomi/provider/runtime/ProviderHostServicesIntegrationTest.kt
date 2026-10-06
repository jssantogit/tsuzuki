package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ProviderHostServicesIntegrationTest {

    @Test
    fun providerHostBridge_enforcesPerInvocationOperationBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invocation = ProviderHostInvocationFactory(context).create(
            ProviderHostInvocationPolicy(
                providerId = "org.example.reader",
                invocationId = "budget-test",
                maxHostOperations = 2,
            ),
        )

        try {
            invocation.bridge.logInfo("one")
            invocation.bridge.logInfo("two")

            val failure = runCatching {
                invocation.bridge.logInfo("three")
            }.exceptionOrNull()

            assertTrue(failure is SecurityException || failure is IllegalStateException)
        } finally {
            invocation.close()
        }
    }

    @Test
    fun providerHostPolicy_rejectsInvalidOperationBudget() {
        assertTrue(
            runCatching {
                ProviderHostInvocationPolicy(
                    providerId = "org.example.reader",
                    invocationId = "invalid-budget",
                    maxHostOperations = 0,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ProviderHostInvocationPolicy(
                    providerId = "org.example.reader",
                    invocationId = "invalid-budget",
                    maxHostOperations = 4097,
                )
            }.isFailure,
        )
    }

    @Test
    fun providerHostPolicy_keepsP2pFailClosedUnlessCoreExplicitlyGrantsIt() {
        val defaultPolicy = ProviderHostInvocationPolicy(
            providerId = "org.example.p2p",
            invocationId = "p2p-default",
        )
        assertTrue(ProviderHostModule.P2P !in defaultPolicy.allowedHostModules())

        val grantedPolicy = defaultPolicy.copy(
            invocationId = "p2p-granted",
            directP2pEnabled = true,
        )
        assertTrue(ProviderHostModule.P2P in grantedPolicy.allowedHostModules())
    }

    @Test
    fun providerRuntime_keepsComplexReadingBytesInsideHostServices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = ByteArray(16) { index -> (index + 1).toByte() }
        val iv = ByteArray(16) { index -> (0x10 + index).toByte() }
        val fixture = encryptedImageZip(key, iv)

        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body(Buffer().write(fixture))
                .build(),
        )
        server.start()

        val runtime = bindRuntime(context)
        val storageRoot = File(context.cacheDir, "provider-host-services-complex").apply {
            deleteRecursively()
        }
        val factory = ProviderHostInvocationFactory(
            context = context,
            storageRoot = storageRoot,
        )
        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val invocation = factory.create(
            ProviderHostInvocationPolicy(
                providerId = "org.example.reader",
                invocationId = "complex-reading",
                networkOrigins = setOf(origin),
                allowLocalNetwork = true,
            ),
        )

        try {
            val script = """
                const archive = await tsuzuki.binary.fetch('${server.url("/bundle.zip")}');
                const encrypted = await tsuzuki.binary.zipEntry(archive, 'page.enc');
                const decrypted = await tsuzuki.crypto.aesCbcDecrypt(
                  encrypted,
                  '${key.toHex()}',
                  '${iv.toHex()}'
                );
                const cropped = await tsuzuki.image.crop(decrypted, 0, 0, 1, 1);
                await tsuzuki.image.pixel(cropped, 0, 0);
            """.trimIndent()

            val response = invoke(
                runtime = runtime.remote,
                source = script,
                invocationId = "complex-reading",
                hostBridge = invocation.bridge,
                hostModules = setOf(
                    ProviderHostModule.BINARY,
                    ProviderHostModule.CRYPTO,
                    ProviderHostModule.IMAGE,
                ),
            )

            assertEquals(null, response.failure)
            assertEquals("FFFF0000", response.value)
            assertEquals(1, server.requestCount)
        } finally {
            invocation.close()
            runtime.close()
            server.close()
            storageRoot.deleteRecursively()
        }
    }

    @Test
    fun providerBrowser_closeCancelsInFlightHostWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("<html><body><div id=\"probe\">late</div></body></html>")
                .bodyDelay(30, TimeUnit.SECONDS)
                .build(),
        )
        server.start()

        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val browser = AndroidProviderBrowserHostService(
            context = context,
            policy = tachiyomi.core.provider.runtime.ProviderNetworkPolicy(
                allowedOrigins = setOf(origin),
                allowLocalNetwork = true,
            ),
            providerProfileName = "tsuzuki-provider-cancel-test",
        )

        try {
            val pending = async(Dispatchers.IO) {
                runCatching {
                    browser.readText(
                        server.url("/slow").toString(),
                        "#probe",
                    )
                }
            }

            withContext(Dispatchers.IO) {
                server.takeRequest()
            }
            browser.close()

            withTimeout(2_000) {
                assertTrue(pending.await().isFailure)
            }
        } finally {
            browser.close()
            server.close()
        }
    }

    @Test
    fun providerBrowser_closedBrokerRejectsNewWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.start()

        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val browser = AndroidProviderBrowserHostService(
            context = context,
            policy = tachiyomi.core.provider.runtime.ProviderNetworkPolicy(
                allowedOrigins = setOf(origin),
                allowLocalNetwork = true,
            ),
            providerProfileName = "tsuzuki-provider-closed-test",
        )

        try {
            browser.close()

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    browser.readText(
                        server.url("/after-close").toString(),
                        "#probe",
                    )
                }
            }

            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        } finally {
            browser.close()
            server.close()
        }
    }

    @Test
    fun providerBrowserProfiles_areIsolatedByProviderAndPersistentAcrossInvocations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = BROWSER_HTML,
            ),
        )
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = BROWSER_HTML,
            ),
        )
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = BROWSER_HTML,
            ),
        )
        server.start()

        val runtime = bindRuntime(context)
        val storageRoot = File(context.cacheDir, "provider-host-services-browser").apply {
            deleteRecursively()
        }
        val factory = ProviderHostInvocationFactory(
            context = context,
            storageRoot = storageRoot,
        )
        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }

        fun invocation(providerId: String, invocationId: String) =
            factory.create(
                ProviderHostInvocationPolicy(
                    providerId = providerId,
                    invocationId = invocationId,
                    browserOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
            )

        val a1 = invocation("org.example.a", "browser-a1")
        val a2 = invocation("org.example.a", "browser-a2")
        val b1 = invocation("org.example.b", "browser-b1")

        try {
            val first = invoke(
                runtime.remote,
                "await tsuzuki.browser.readText('${server.url("/browser?write=alpha")}', '#probe')",
                "browser-a1",
                a1.bridge,
                providerId = "org.example.a",
                hostModules = setOf(ProviderHostModule.BROWSER),
            )
            assertEquals("alpha", first.value)

            val second = invoke(
                runtime.remote,
                "await tsuzuki.browser.readText('${server.url("/browser")}', '#probe')",
                "browser-a2",
                a2.bridge,
                providerId = "org.example.a",
                hostModules = setOf(ProviderHostModule.BROWSER),
            )
            assertEquals("alpha", second.value)

            val other = invoke(
                runtime.remote,
                "await tsuzuki.browser.readText('${server.url("/browser")}', '#probe')",
                "browser-b1",
                b1.bridge,
                providerId = "org.example.b",
                hostModules = setOf(ProviderHostModule.BROWSER),
            )
            assertEquals("empty", other.value)
        } finally {
            a1.close()
            a2.close()
            b1.close()
            runtime.close()
            server.close()
            storageRoot.deleteRecursively()
        }
    }

    @Test
    fun providerBrowser_blocks_cross_origin_subresources_before_network_fetch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowed = MockWebServer()
        val blocked = MockWebServer()
        allowed.start()
        blocked.start()
        allowed.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = """
                    <!doctype html>
                    <html>
                    <body>
                      <img src="${blocked.url("/leak.png")}" />
                      <div id="probe">safe</div>
                    </body>
                    </html>
                """.trimIndent(),
            ),
        )
        blocked.enqueue(MockResponse(body = "should-not-be-fetched"))

        val runtime = bindRuntime(context)
        val factory = ProviderHostInvocationFactory(context)
        val origin = allowed.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val invocation = factory.create(
            ProviderHostInvocationPolicy(
                providerId = "org.example.reader",
                invocationId = "browser-subresource",
                browserOrigins = setOf(origin),
                allowLocalNetwork = true,
            ),
        )

        try {
            val response = invoke(
                runtime = runtime.remote,
                source = "await tsuzuki.browser.readText('${allowed.url("/page")}', '#probe')",
                invocationId = "browser-subresource",
                hostBridge = invocation.bridge,
                hostModules = setOf(ProviderHostModule.BROWSER),
            )

            assertEquals(null, response.failure)
            assertEquals("safe", response.value)
            assertEquals(0, blocked.requestCount)
        } finally {
            invocation.close()
            runtime.close()
            allowed.close()
            blocked.close()
        }
    }

    private fun invoke(
        runtime: IProviderRuntimeService,
        source: String,
        invocationId: String,
        hostBridge: IProviderHostBridge,
        providerId: String = "org.example.reader",
        hostModules: Set<ProviderHostModule> = emptySet(),
    ): ProviderRuntimeInvocationResponse {
        val request = ProviderRuntimeInvocationRequest(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            invocationId = invocationId,
            providerId = providerId,
            artifactVersionCode = 1,
            capabilityId = "reading.pages",
            capabilityVersion = 1,
            configurationFingerprint = "instrumentation",
            fileName = "main.js",
            hostModules = hostModules,
            limits = ProviderRuntimeLimitsDto(
                wallClockTimeoutMs = 10_000,
                jsExecutionTimeoutMs = 3_000,
                memoryLimitBytes = 32L * 1024L * 1024L,
                stackLimitBytes = 512L * 1024L,
            ),
        )
        val pipe = ParcelFileDescriptor.createPipe()
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
            output.write(source.encodeToByteArray())
        }

        return pipe[0].use { sourceFd ->
            ProviderRuntimeProtocol.decodeResponse(
                runtime.invoke(
                    ProviderRuntimeProtocol.encodeRequest(request),
                    sourceFd,
                    hostBridge,
                ),
            )
        }
    }

    private fun bindRuntime(context: Context): BoundRuntime {
        val connected = CountDownLatch(1)
        var remote: IProviderRuntimeService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                remote = IProviderRuntimeService.Stub.asInterface(service)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
            }
        }

        val bound = context.bindService(
            Intent(context, ProviderRuntimeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("isolated runtime should bind", bound)
        assertTrue(
            "isolated runtime should connect",
            connected.await(SERVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return BoundRuntime(
            context = context,
            connection = connection,
            remote = requireNotNull(remote),
        )
    }

    private fun encryptedImageZip(
        key: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        bitmap.setPixel(1, 0, Color.BLUE)

        val png = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            bitmap.recycle()
            stream.toByteArray()
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        val encrypted = cipher.doFinal(png)

        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("page.enc"))
                zip.write(encrypted)
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private data class BoundRuntime(
        val context: Context,
        val connection: ServiceConnection,
        val remote: IProviderRuntimeService,
    ) : AutoCloseable {
        override fun close() {
            context.unbindService(connection)
        }
    }

    private companion object {
        const val SERVICE_CONNECT_TIMEOUT_SECONDS = 20L

        val BROWSER_HTML = """
            <!doctype html>
            <html>
            <body>
              <div id="probe"></div>
              <script>
                const params = new URLSearchParams(location.search);
                const writeValue = params.get('write');
                if (writeValue) {
                  localStorage.setItem('provider-key', writeValue);
                }
                document.getElementById('probe').textContent =
                  localStorage.getItem('provider-key') || 'empty';
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
