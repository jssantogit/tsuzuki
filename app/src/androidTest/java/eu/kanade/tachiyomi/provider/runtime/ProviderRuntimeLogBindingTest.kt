package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentPreferences
import mihon.app.di.AppBindings
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ProviderRuntimeLogBindingTest {

    @Test
    fun productionHostBindingRoutesProviderLogInfoToConfiguredSink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val managedFiles = AppBindings.providesProviderManagedFileStore(context)
        val p2pJobs = AppBindings.providesProviderP2pJobManager(
            context = context,
            managedFiles = managedFiles,
            verifiedMetadata = AppBindings.providesHostVerifiedTorrentMetadataCache(),
            registry = DefaultProviderRegistry(registrations = { emptyList() }),
            torrentPreferences = ProviderTorrentPreferences(InMemoryPreferenceStore()),
        )
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
                diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
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
        val p2pJobs = AppBindings.providesProviderP2pJobManager(
            context = context,
            managedFiles = managedFiles,
            verifiedMetadata = AppBindings.providesHostVerifiedTorrentMetadataCache(),
            registry = DefaultProviderRegistry(registrations = { emptyList() }),
            torrentPreferences = ProviderTorrentPreferences(InMemoryPreferenceStore()),
        )
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
                diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
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

    @Test
    fun productionHostBindingEmitsSanitizedHttpEventsOnlyDuringUserCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val managedFiles = AppBindings.providesProviderManagedFileStore(context)
        val p2pJobs = AppBindings.providesProviderP2pJobManager(
            context = context,
            managedFiles = managedFiles,
            verifiedMetadata = AppBindings.providesHostVerifiedTorrentMetadataCache(),
            registry = DefaultProviderRegistry(registrations = { emptyList() }),
            torrentPreferences = ProviderTorrentPreferences(InMemoryPreferenceStore()),
        )
        val recorder = object : StructuredDiagnosticRecorder {
            override val sessionId = "35d4ea34-f729-4336-b6ce-d6277ec3ac7c"

            @Volatile var enabled = false
            override val detailedCaptureActive: Boolean get() = enabled
            val events = CopyOnWriteArrayList<StructuredDiagnosticEvent>()
            override fun canonicalTitleReference(canonicalTitleId: String): String? = null
            override fun mihonMangaReference(mihonMangaId: Long): String? = null
            override fun record(event: StructuredDiagnosticEvent) {
                events += event
            }
        }
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("first").build())
        server.enqueue(MockResponse.Builder().body("second").build())
        server.start()
        try {
            val factory = AppBindings.providesProviderHostInvocationFactory(
                context = context,
                managedFiles = managedFiles,
                p2pJobs = p2pJobs,
                logSink = ProviderRuntimeLogSink { _, _ -> },
                diagnosticRecorder = recorder,
            )
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            factory.create(
                policy = ProviderHostInvocationPolicy(
                    providerId = "app.tsuzuki.mangafire",
                    invocationId = "no-capture-http-probe",
                    networkOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                invocationTimeoutMs = 1_500,
            ).use { invocation ->
                assertEquals(
                    "first",
                    invocation.bridge.httpGet(server.url("/secret-chapters?vrf=must-not-leak").toString()),
                )
            }
            assertTrue("HTTP trace is opt-in", recorder.events.isEmpty())

            recorder.enabled = true
            factory.create(
                policy = ProviderHostInvocationPolicy(
                    providerId = "app.tsuzuki.mangafire",
                    invocationId = "capture-http-probe",
                    networkOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                invocationTimeoutMs = 1_500,
            ).use { invocation ->
                assertEquals(
                    "second",
                    invocation.bridge.httpGet(server.url("/secret-chapters?vrf=must-not-leak").toString()),
                )
            }
            val observed = recorder.events.mapNotNull(StructuredDiagnosticSanitizer::sanitize)
            assertEquals(2, observed.size)
            assertTrue(observed.all { it.name == DiagnosticEventName.PROVIDER_HTTP_REQUEST })
            assertTrue(observed.any { it.outcome == DiagnosticOutcome.STARTED })
            assertTrue(observed.any { it.outcome == DiagnosticOutcome.SUCCEEDED })
            val providerIds = observed.map { it.attributes[DiagnosticAttribute.PROVIDER_ID] }
            val httpStatuses = observed.map { it.attributes[DiagnosticAttribute.HTTP_STATUS] }
            val permittedKeys = setOf(DiagnosticAttribute.PROVIDER_ID, DiagnosticAttribute.HTTP_STATUS)
            assertTrue(providerIds.all { it == DiagnosticAttributeValue.Text("app.tsuzuki.mangafire") })
            assertTrue(httpStatuses.contains(DiagnosticAttributeValue.Number(200)))
            assertTrue(observed.all { event -> event.attributes.keys.all(permittedKeys::contains) })
        } finally {
            server.close()
            p2pJobs.close()
            managedFiles.clearAll()
        }
    }
}
