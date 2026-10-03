package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProviderHostBridgeTest {

    @Test
    fun providerRuntime_routesHttpAndBrowserCallsThroughHostBridge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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

            val hostBridge = object : IProviderHostBridge.Stub() {
                override fun httpGet(url: String?): String {
                    assertEquals("https://allowed.example/data", url)
                    return "http-ok"
                }

                override fun browserReadText(
                    url: String?,
                    cssSelector: String?,
                ): String {
                    assertEquals("https://allowed.example/browser", url)
                    assertEquals("#probe", cssSelector)
                    return "browser-ok"
                }

                override fun binaryFetch(url: String?): String =
                    throw UnsupportedOperationException()

                override fun binaryZipEntry(resourceHandle: String?, entryName: String?): String =
                    throw UnsupportedOperationException()

                override fun binaryAesCbcDecrypt(
                    resourceHandle: String?,
                    keyHex: String?,
                    ivHex: String?,
                ): String = throw UnsupportedOperationException()

                override fun binaryImageCrop(
                    resourceHandle: String?,
                    x: Int,
                    y: Int,
                    width: Int,
                    height: Int,
                ): String = throw UnsupportedOperationException()

                override fun binaryImagePixel(
                    resourceHandle: String?,
                    x: Int,
                    y: Int,
                ): String = throw UnsupportedOperationException()
            }

            val runtime = requireNotNull(remote)
            assertEquals(
                "ok:http-ok",
                runtime.evaluateWithHost(
                    "await tsuzuki.http.get('https://allowed.example/data')",
                    2_000L,
                    1_000L,
                    hostBridge,
                ),
            )
            assertEquals(
                "ok:browser-ok",
                runtime.evaluateWithHost(
                    "await tsuzuki.browser.readText('https://allowed.example/browser', '#probe')",
                    2_000L,
                    1_000L,
                    hostBridge,
                ),
            )
        } finally {
            if (bound) {
                context.unbindService(connection)
            }
        }
    }

    private companion object {
        const val SERVICE_CONNECT_TIMEOUT_SECONDS = 20L
    }
}
