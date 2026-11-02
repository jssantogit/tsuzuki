package eu.kanade.tachiyomi.ui.main

import android.os.Handler
import android.os.Looper
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
 * Full Android acceptance must verify this observer on a resumed Activity.
 */
internal class MainThreadFrameStallMonitor(
    private val recorder: StructuredDiagnosticRecorder,
    private val choreographer: Choreographer = Choreographer.getInstance(),
) : Choreographer.FrameCallback {
    private val detector = MainThreadFrameGapDetector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var observingFrames = false

    // Detailed capture is not a Flow. Poll cheaply while the Activity is resumed
    // rather than requesting every display frame for the application's lifetime.
    private val checkCapture = object : Runnable {
        override fun run() {
            if (!running) return
            val active = recorder.detailedCaptureActive
            if (active && !observingFrames) {
                observingFrames = true
                detector.reset()
                choreographer.postFrameCallback(this@MainThreadFrameStallMonitor)
            } else if (!active && observingFrames) {
                observingFrames = false
                choreographer.removeFrameCallback(this@MainThreadFrameStallMonitor)
                detector.reset()
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    fun start() {
        if (running) return
        running = true
        detector.reset()
        checkCapture.run()
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(checkCapture)
        if (observingFrames) choreographer.removeFrameCallback(this)
        observingFrames = false
        detector.reset()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !observingFrames) return
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
