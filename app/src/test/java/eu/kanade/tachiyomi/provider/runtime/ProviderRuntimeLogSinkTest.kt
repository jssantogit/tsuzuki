package eu.kanade.tachiyomi.provider.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRuntimeLogSinkTest {

    @Test
    fun `provider runtime log keeps provider identity and forwards message`() {
        val lines = mutableListOf<String>()
        val sink = ProviderRuntimeLogSink(lines::add)

        sink.info(
            providerId = "app.tsuzuki.nyaa",
            message = "{\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\"}",
        )

        assertEquals(
            listOf(
                "Provider[app.tsuzuki.nyaa] {\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\"}",
            ),
            lines,
        )
    }
}
