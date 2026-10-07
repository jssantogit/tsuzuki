package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderRuntimeLogSinkTest {

    @Test
    fun `provider runtime log keeps provider identity and forwards message`() {
        val lines = mutableListOf<String>()
        val sink = ProviderRuntimeLogSink { line -> lines += line }

        sink.info(
            providerId = "app.tsuzuki.nyaa",
            message = "{\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\"}",
        )

        lines shouldBe listOf(
            "Provider[app.tsuzuki.nyaa] {\"event\":\"nyaa_discovery\",\"phase\":\"primary_narrow\"}",
        )
    }
}
