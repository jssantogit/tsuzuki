package eu.kanade.tachiyomi.provider.runtime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProviderRuntimeIsolationTest {

    @Test
    fun providerRuntime_isolatedFreshAndBrokered() {
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

            val runtime = requireNotNull(remote)
            assertNotEquals(Process.myUid(), runtime.processUid())
            assertNotEquals(Process.myPid(), runtime.processPid())
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.INTERNET),
            )
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkPermission(
                    Manifest.permission.INTERNET,
                    runtime.processPid(),
                    runtime.processUid(),
                ),
            )

            invoke(runtime, "1 + 2") shouldBeSuccess "3"

            val stateful = "globalThis.counter = (globalThis.counter || 0) + 1; counter"
            invoke(runtime, stateful) shouldBeSuccess "1"
            invoke(runtime, stateful) shouldBeSuccess "1"

            val timeout = invoke(
                runtime = runtime,
                source = "while (true) {}",
                limits = ProviderRuntimeLimitsDto(
                    wallClockTimeoutMs = 500,
                    jsExecutionTimeoutMs = 100,
                    memoryLimitBytes = 8L * 1024L * 1024L,
                    stackLimitBytes = 256L * 1024L,
                ),
            )
            assertEquals(ProviderRuntimeFailureCode.TIMEOUT, timeout.failure)

            val host = fakeHostBridge(httpResult = "broker-ok")
            invoke(
                runtime = runtime,
                source = "await tsuzuki.http.get('https://allowed.example/data')",
                hostBridge = host,
                hostModules = setOf(ProviderHostModule.HTTP),
            ) shouldBeSuccess "broker-ok"
        } finally {
            if (bound) {
                context.unbindService(connection)
            }
        }
    }

    private fun invoke(
        runtime: IProviderRuntimeService,
        source: String,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
        hostBridge: IProviderHostBridge? = null,
        hostModules: Set<ProviderHostModule> = emptySet(),
    ): ProviderRuntimeInvocationResponse {
        val request = ProviderRuntimeInvocationRequest(
            protocolVersion = ProviderRuntimeProtocol.VERSION,
            invocationId = "instrumented-invocation",
            providerId = "org.example.reader",
            artifactVersionCode = 1,
            capabilityId = "reading.chapters",
            capabilityVersion = 1,
            configurationFingerprint = "instrumented-config",
            fileName = "main.js",
            hostModules = hostModules,
            limits = limits,
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

    private infix fun ProviderRuntimeInvocationResponse.shouldBeSuccess(expected: String) {
        assertEquals(null, failure)
        assertEquals(expected, value)
    }

    private fun fakeHostBridge(httpResult: String) = object : IProviderHostBridge.Stub() {
        override fun httpGet(url: String?): String {
            assertEquals("https://allowed.example/data", url)
            return httpResult
        }

        override fun httpGetResource(url: String?): String =
            throw UnsupportedOperationException()

        override fun domSelectText(resourceHandle: String?, cssSelector: String?): String =
            throw UnsupportedOperationException()

        override fun browserReadText(url: String?, cssSelector: String?): String =
            throw UnsupportedOperationException()

        override fun storageGet(key: String?): String? = null

        override fun storageSet(key: String?, value: String?) = Unit

        override fun storageRemove(key: String?) = Unit

        override fun secretGet(key: String?): String? = null

        override fun binaryFetch(url: String?): String =
            throw UnsupportedOperationException()

        override fun binaryZipEntry(resourceHandle: String?, entryName: String?): String =
            throw UnsupportedOperationException()

        override fun cryptoAesCbcDecrypt(
            resourceHandle: String?,
            keyHex: String?,
            ivHex: String?,
        ): String = throw UnsupportedOperationException()

        override fun imageCrop(
            resourceHandle: String?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ): String = throw UnsupportedOperationException()

        override fun imagePixel(resourceHandle: String?, x: Int, y: Int): String =
            throw UnsupportedOperationException()

        override fun logInfo(message: String?) = Unit
    }

    private companion object {
        const val SERVICE_CONNECT_TIMEOUT_SECONDS = 20L
    }
}
