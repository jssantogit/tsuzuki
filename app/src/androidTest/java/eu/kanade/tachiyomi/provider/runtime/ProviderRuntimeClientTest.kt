package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ProviderRuntimeClientTest {

    @Test
    fun cancellingCaller_interruptsRemoteInvocationAndRuntimeRemainsUsable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageRoot = File(context.cacheDir, "provider-runtime-client").apply {
            deleteRecursively()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = storageRoot,
            ),
        )

        val cancelled = runCatching {
            withTimeout(1_000) {
                client.invoke(
                    request = request(
                        invocationId = "cancel-me",
                        limits = ProviderRuntimeLimitsDto(
                            wallClockTimeoutMs = 30_000,
                            jsExecutionTimeoutMs = 30_000,
                            memoryLimitBytes = 8L * 1024L * 1024L,
                            stackLimitBytes = 256L * 1024L,
                        ),
                    ),
                    source = "while (true) {}".encodeToByteArray(),
                    hostPolicy = ProviderHostInvocationPolicy(
                        providerId = "org.example.reader",
                        invocationId = "cancel-me",
                    ),
                )
            }
        }.exceptionOrNull()

        assertTrue(cancelled is TimeoutCancellationException)

        val response = client.invoke(
            request = request(invocationId = "after-cancel"),
            source = "6 * 7".encodeToByteArray(),
            hostPolicy = ProviderHostInvocationPolicy(
                providerId = "org.example.reader",
                invocationId = "after-cancel",
            ),
        )

        assertEquals(null, response.failure)
        assertEquals("42", response.value)
        storageRoot.deleteRecursively()
        Unit
    }


    @Test
    fun consecutivePages_reuseBinderButIdleBindingEventuallyCloses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = CopyOnWriteArrayList<ProviderRuntimeBindingPhase>()
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(context = context),
            bindingObserver = { events.add(it) },
            bindingIdleTimeoutMs = 1_000L,
        )

        suspend fun invoke(id: String): String? {
            val result = client.invoke(
                request = request(invocationId = id),
                source = "6 * 7".encodeToByteArray(),
                hostPolicy = ProviderHostInvocationPolicy(
                    providerId = "org.example.reader",
                    invocationId = id,
                ),
            )
            assertEquals(null, result.failure)
            return result.value
        }

        assertEquals("42", invoke("page-first"))
        assertEquals("42", invoke("page-second"))
        assertEquals(1, events.count { it == ProviderRuntimeBindingPhase.BIND_STARTED })
        assertEquals(1, events.count { it == ProviderRuntimeBindingPhase.BIND_REUSED })

        delay(1_500L)
        assertTrue(events.contains(ProviderRuntimeBindingPhase.BIND_INVALIDATED))
        assertEquals("42", invoke("page-after-idle"))
        assertEquals(2, events.count { it == ProviderRuntimeBindingPhase.BIND_STARTED })
    }

    private fun request(
        invocationId: String,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
    ) = ProviderRuntimeInvocationRequest(
        protocolVersion = ProviderRuntimeProtocol.VERSION,
        invocationId = invocationId,
        providerId = "org.example.reader",
        artifactVersionCode = 1,
        capabilityId = "reading.pages",
        capabilityVersion = 1,
        configurationFingerprint = "instrumentation",
        fileName = "main.js",
        limits = limits,
    )
}
