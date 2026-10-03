package eu.kanade.tachiyomi.provider.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ProviderComplexReadingPipelineTest {

    @Test
    fun providerRuntime_keepsComplexReadingBytesInsideHostPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = ByteArray(16) { index -> (index + 1).toByte() }
        val iv = ByteArray(16) { index -> (0x10 + index).toByte() }
        val fixture = encryptedImageZip(key, iv)

        val server = MockWebServer()
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "application/zip"),
                body = fixture,
            ),
        )
        server.start()

        val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
        val broker = ProviderHostBroker(
            context = context,
            policy = ProviderHostPolicy(
                allowedOrigins = setOf(origin),
                allowLocalNetwork = true,
            ),
            providerProfileName = "tsuzuki-spike-complex-reading",
        )

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
            val script = """
                const archive = await tsuzuki.binary.fetch('${server.url("/bundle.zip")}');
                const encrypted = await tsuzuki.binary.zipEntry(archive, 'page.enc');
                const decrypted = await tsuzuki.binary.aesCbcDecrypt(
                  encrypted,
                  '${key.toHex()}',
                  '${iv.toHex()}'
                );
                const cropped = await tsuzuki.binary.imageCrop(decrypted, 0, 0, 1, 1);
                await tsuzuki.binary.imagePixel(cropped, 0, 0);
            """.trimIndent()

            assertEquals(
                "ok:FFFF0000",
                runtime.evaluateWithHost(
                    script,
                    10_000L,
                    3_000L,
                    hostBridgeFor(broker),
                ),
            )
            assertEquals(1, server.requestCount)
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

    private companion object {
        const val SERVICE_CONNECT_TIMEOUT_SECONDS = 20L
    }
}
