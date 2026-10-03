package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProviderRealHostBrokerTest {

    @Test
    fun providerRuntime_usesRealHostBrokersAndIsolatesBrowserProfiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/data" -> MockResponse(body = "http-real")
                    "/browser" -> MockResponse(
                        headers = okhttp3.Headers.headersOf("Content-Type", "text/html; charset=utf-8"),
                        body = BROWSER_HTML,
                    )
                    else -> MockResponse(code = 404)
                }
            }
        }
        server.start()

        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val policy = ProviderHostPolicy(
            allowedOrigins = setOf(origin),
            allowLocalNetwork = true,
        )
        val brokerA = ProviderHostBroker(context, policy, "tsuzuki-spike-provider-a")
        val brokerB = ProviderHostBroker(context, policy, "tsuzuki-spike-provider-b")

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

        try {
            assertTrue("isolated service should bind", bound)
            assertTrue(
                "isolated service should connect",
                connected.await(SERVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val runtime = requireNotNull(remote)

            assertEquals(
                "ok:http-real",
                runtime.evaluateWithHost(
                    "await tsuzuki.http.get('${server.url("/data")}')",
                    5_000L,
                    2_000L,
                    hostBridgeFor(brokerA),
                ),
            )

            assertEquals(
                "ok:alpha",
                runtime.evaluateWithHost(
                    "await tsuzuki.browser.readText('${server.url("/browser?write=alpha")}', '#probe')",
                    10_000L,
                    2_000L,
                    hostBridgeFor(brokerA),
                ),
            )
            assertEquals(
                "ok:empty",
                runtime.evaluateWithHost(
                    "await tsuzuki.browser.readText('${server.url("/browser")}', '#probe')",
                    10_000L,
                    2_000L,
                    hostBridgeFor(brokerB),
                ),
            )
            assertEquals(
                "ok:alpha",
                runtime.evaluateWithHost(
                    "await tsuzuki.browser.readText('${server.url("/browser")}', '#probe')",
                    10_000L,
                    2_000L,
                    hostBridgeFor(brokerA),
                ),
            )
        } finally {
            if (bound) {
                context.unbindService(connection)
            }
            server.close()
        }
    }

    private fun hostBridgeFor(broker: ProviderHostBroker) = object : IProviderHostBridge.Stub() {
        override fun httpGet(url: String?): String = broker.httpGet(url.orEmpty())

        override fun browserReadText(
            url: String?,
            cssSelector: String?,
        ): String = broker.browserReadText(
            url = url.orEmpty(),
            cssSelector = cssSelector.orEmpty(),
        )
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
