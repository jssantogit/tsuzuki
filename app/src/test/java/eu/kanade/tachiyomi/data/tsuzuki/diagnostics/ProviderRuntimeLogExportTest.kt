package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ProviderRuntimeLogExportTest {

    @Test
    fun `nonverbose export keeps errors and provider runtime info only`() {
        var requestedPriority: String? = null
        val logcat = """
            --------- beginning of main
            2026-10-07 08:13:43.160 -0300 31900 31990 E FeatureFlags: fatal-ish error
            java.lang.IllegalStateException: details
            2026-10-07 08:13:44.000 -0300 31900 31990 W OtherTag: ordinary warning
            2026-10-07 08:13:45.000 -0300 31900 31990 I OtherTag: ordinary info
            ordinary info continuation
            2026-10-07 08:13:46.000 -0300 31900 31990 I Tsuzuki: provider_runtime_log providerId=app.tsuzuki.nyaa payload=host_http phase=FAILED host=nyaa.si elapsedMs=2500 timeoutMs=5000 status=none failure=READ_TIMEOUT
            provider diagnostic continuation
            2026-10-07 08:13:47.000 -0300 31900 31990 I OtherTag: prefix provider_runtime_log providerId=spoofed payload=must-not-pass
        """.trimIndent() + "\n"
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { priority ->
                requestedPriority = priority
                FakeProcess(logcat.toByteArray())
            },
            timeoutMillis = 1_000,
            maxOutputBytes = 64 * 1024,
        )

        val capture = collector.collectForExport(verboseLogging = false)

        assertEquals("I", requestedPriority)
        assertTrue(capture.content.contains("fatal-ish error"))
        assertTrue(capture.content.contains("IllegalStateException: details"))
        assertTrue(capture.content.contains("provider_runtime_log providerId=app.tsuzuki.nyaa"))
        assertTrue(capture.content.contains("provider diagnostic continuation"))
        assertFalse(capture.content.contains("ordinary warning"))
        assertFalse(capture.content.contains("ordinary info"))
        assertFalse(capture.content.contains("ordinary info continuation"))
        assertFalse(capture.content.contains("providerId=spoofed"))
    }

    @Test
    fun `verbose export preserves complete logcat`() {
        var requestedPriority: String? = null
        val logcat = """
            --------- beginning of main
            2026-10-07 08:13:45.000 -0300 31900 31990 I OtherTag: ordinary info
            2026-10-07 08:13:46.000 -0300 31900 31990 D OtherTag: ordinary debug
        """.trimIndent() + "\n"
        val collector = BoundedLogcatCollector(
            processStarter = LogcatProcessStarter { priority ->
                requestedPriority = priority
                FakeProcess(logcat.toByteArray())
            },
            timeoutMillis = 1_000,
            maxOutputBytes = 64 * 1024,
        )

        val capture = collector.collectForExport(verboseLogging = true)

        assertEquals("V", requestedPriority)
        assertEquals(logcat, capture.content)
    }

    private class FakeProcess(output: ByteArray) : Process() {
        private val input = ByteArrayInputStream(output)
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            alive = false
            return true
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            alive = false
        }
        override fun destroyForcibly(): Process {
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
