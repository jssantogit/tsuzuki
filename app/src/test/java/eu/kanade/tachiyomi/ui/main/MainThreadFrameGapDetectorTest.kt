package eu.kanade.tachiyomi.ui.main

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MainThreadFrameGapDetectorTest {

    @Test
    fun `detects only substantial missed frames after establishing a baseline`() {
        val detector = MainThreadFrameGapDetector(thresholdMs = 200L, maxReports = 3)
        detector.observe(1_000_000_000L, capturing = true) shouldBe null
        detector.observe(1_016_000_000L, capturing = true) shouldBe null
        detector.observe(1_265_000_000L, capturing = true) shouldBe 249L
    }

    @Test
    fun `stops recording outside opt in capture and resets after resume`() {
        val detector = MainThreadFrameGapDetector(thresholdMs = 200L, maxReports = 3)
        detector.observe(1_000_000_000L, capturing = true) shouldBe null
        detector.observe(3_000_000_000L, capturing = false) shouldBe null
        detector.observe(6_000_000_000L, capturing = true) shouldBe null
        detector.observe(6_250_000_000L, capturing = true) shouldBe 250L
        detector.reset()
        detector.observe(15_000_000_000L, capturing = true) shouldBe null
    }

    @Test
    fun `rejects non monotonic frame times and caps emitted events`() {
        val detector = MainThreadFrameGapDetector(thresholdMs = 200L, maxReports = 2)
        detector.observe(1_000_000_000L, capturing = true) shouldBe null
        detector.observe(1_350_000_000L, capturing = true) shouldBe 350L
        detector.observe(1_300_000_000L, capturing = true) shouldBe null
        detector.observe(1_600_000_000L, capturing = true) shouldBe 300L
        detector.observe(1_900_000_000L, capturing = true) shouldBe null
    }
}
