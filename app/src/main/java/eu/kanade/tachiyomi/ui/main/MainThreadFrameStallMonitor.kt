package eu.kanade.tachiyomi.ui.main

import android.view.Choreographer
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder

/**
 * Detects large main-looper frame gaps, not the root cause of an ANR.
 * A missed frame can be caused by UI work, process scheduling or GC; correlate
 * this timestamp with the separately timed chapter reconciliation/UI phases.
 * This does not claim that a frame gap alone proves the blocking function.
 */
internal class MainThreadFrameGapDetector(
    private val thresholdMs: Long = 200L,
    private val maxReports: Int = 64,
) {
    private var lastFrameNanos: Long? = null
    private var reports = 0

    init {
        require(thresholdMs >= 1)
        require(maxReports >= 1)
    }

    fun reset() {
        lastFrameNanos = null
        reports = 0
    }

    fun observe(frameTimeNanos: Long, capturing: Boolean): Long? {
        if (!capturing) {
            reset()
            return null
        }
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == null || frameTimeNanos <= previous) return null
        val gapMs = (frameTimeNanos - previous) / 1_000_000L
        if (gapMs < thresholdMs || reports >= maxReports) return null
        reports++
        return gapMs
    }
}

/**
 * Starts and stops with the foreground Activity; never stores raw stack traces
 * or adds an uncapped per-frame diagnostic stream. Events are emitted only
 * inside a user-controlled detailed-capture window.
 */
internal class MainThreadFrameStallMonitor(
    private val recorder: StructuredDiagnosticRecorder,
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : Choreographer.FrameCallback {
    private val detector = MainThreadFrameGapDetector()
    private var running = false

    fun start() {
        if (running) return
        running = true
        detector.reset()
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
        detector.reset()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val capturing = recorder.detailedCaptureActive
        detector.observe(frameTimeNanos, capturing)?.let { gapMillis ->
            runCatching {
                recorder.record(
                    StructuredDiagnosticEvent(
                        timestampMillis = System.currentTimeMillis(),
                        severity = DiagnosticSeverity.WARN,
                        subsystem = DiagnosticSubsystem.APP,
                        name = DiagnosticEventName.APP_FRAME_STALL,
                        sessionId = recorder.sessionId,
                        operationId = null,
                        stage = DiagnosticStage.RENDER,
                        outcome = DiagnosticOutcome.PARTIAL,
                        durationMillis = gapMillis.coerceAtMost(86_400_000L),
                    ),
                )
            }
        }
        choreographer.postFrameCallback(this)
    }
}
