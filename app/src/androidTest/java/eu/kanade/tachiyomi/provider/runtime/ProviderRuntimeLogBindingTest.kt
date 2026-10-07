package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
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
}
