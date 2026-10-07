package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mihon.app.di.AppBindings
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderRuntimeLogBindingTest {

    @Test
    fun productionHostBindingRoutesProviderLogInfoToConfiguredSink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val managedFiles = AppBindings.providesProviderManagedFileStore(context)
        val p2pJobs = AppBindings.providesProviderP2pJobManager(context, managedFiles)
        val observed = mutableListOf<Pair<String, String>>()
        val logSink = ProviderRuntimeLogSink { providerId, message ->
            observed += providerId to message
        }

        try {
            val factory = AppBindings.providesProviderHostInvocationFactory(
                context = context,
                managedFiles = managedFiles,
                p2pJobs = p2pJobs,
                logSink = logSink,
            )
            factory.create(
                ProviderHostInvocationPolicy(
                    providerId = "app.tsuzuki.nyaa",
                    invocationId = "diagnostic-log-binding",
                ),
            ).use { invocation ->
                invocation.bridge.logInfo(
                    "{\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\",\"rawItemCount\":2,\"acceptedCandidateCount\":1,\"elapsedMs\":42}",
                )
            }
        } finally {
            p2pJobs.close()
            managedFiles.clearAll()
        }

        assertEquals(
            listOf(
                "app.tsuzuki.nyaa" to
                    "{\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\",\"rawItemCount\":2,\"acceptedCandidateCount\":1,\"elapsedMs\":42}",
            ),
            observed,
        )
    }

    @Test
    fun productionHostBindingRoutesHttpLifecycleDiagnosticsWithoutUrlDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val managedFiles = AppBindings.providesProviderManagedFileStore(context)
        val p2pJobs = AppBindings.providesProviderP2pJobManager(context, managedFiles)
        val observed = mutableListOf<Pair<String, String>>()
        val logSink = ProviderRuntimeLogSink { providerId, message ->
            observed += providerId to message
        }
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("ok").build())
        server.start()

        try {
            val factory = AppBindings.providesProviderHostInvocationFactory(
                context = context,
                managedFiles = managedFiles,
                p2pJobs = p2pJobs,
                logSink = logSink,
            )
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            factory.create(
                policy = ProviderHostInvocationPolicy(
                    providerId = "app.tsuzuki.nyaa",
                    invocationId = "http-diagnostic-log-binding",
                    networkOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                invocationTimeoutMs = 750,
            ).use { invocation ->
                assertEquals(
                    "ok",
                    invocation.bridge.httpGet(server.url("/secret-title?q=one-piece").toString()),
                )
            }

            assertEquals(2, observed.size)
            assertTrue(observed.all { (providerId, _) -> providerId == "app.tsuzuki.nyaa" })
            assertTrue(
                observed.any { (_, message) ->
                    message.startsWith("host_http phase=STARTED host=${server.url("/").host} ") &&
                        message.contains("timeoutMs=750 status=none failure=none")
                },
            )
            assertTrue(
                observed.any { (_, message) ->
                    message.startsWith("host_http phase=SUCCEEDED host=${server.url("/").host} ") &&
                        message.contains("timeoutMs=750 status=200 failure=none")
                },
            )
            assertTrue(observed.none { (_, message) -> message.contains("secret-title") })
            assertTrue(observed.none { (_, message) -> message.contains("one-piece") })
        } finally {
            server.close()
            p2pJobs.close()
            managedFiles.clearAll()
        }
    }
}
