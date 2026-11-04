package eu.kanade.tachiyomi.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.SanitizedStructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticSanitizer
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class MainThreadFrameStallMonitorAndroidTest {

    @Test
    fun realMainLooperBlockProducesSanitizedFrameStallDuringOptInCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recorded = CopyOnWriteArrayList<SanitizedStructuredDiagnosticEvent>()
        val recorder = object : StructuredDiagnosticRecorder {
            override val sessionId = "46dbf4c7-28be-4f57-92a5-90bb6db99cb5"
            override val detailedCaptureActive: Boolean = true
            override fun canonicalTitleReference(canonicalTitleId: String): String? = null
            override fun mihonMangaReference(mihonMangaId: Long): String? = null

            override fun record(event: StructuredDiagnosticEvent) {
                StructuredDiagnosticSanitizer.sanitize(event)?.let(recorded::add)
            }
        }
        lateinit var monitor: MainThreadFrameStallMonitor
        instrumentation.runOnMainSync {
            monitor = MainThreadFrameStallMonitor(recorder)
            monitor.start()
        }
        try {
            // The opt-in monitor must establish a real frame baseline before blocking the main looper.
            // Give the Choreographer multiple foreground callbacks to establish a baseline.
            Thread.sleep(300L)
            instrumentation.runOnMainSync {
                // Deterministic synthetic UI stall, not a simulated Provider/HTTP failure.
                Thread.sleep(350L)
            }
            Thread.sleep(350L)
        } finally {
            instrumentation.runOnMainSync { monitor.stop() }
        }
        assertTrue(
            "A real main-thread stall must appear in sanitized opt-in diagnostics",
            recorded.any { event ->
                event.name == DiagnosticEventName.APP_FRAME_STALL &&
                    (event.durationMillis ?: 0L) >= 200L
            },
        )
    }
}
