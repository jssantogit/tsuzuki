package eu.kanade.presentation.more.settings

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PreferenceEnabledSemanticsTest {

    @Test
    fun `disabled preferences remain visible and become non interactive`() {
        val itemSource = readSource("PreferenceItem.kt")
        val baseSource = readSource("widget/BasePreferenceWidget.kt")

        itemSource shouldContain "LocalPreferenceEnabled provides enabled"
        itemSource shouldNotContain "visible = enabled"
        baseSource shouldContain "LocalPreferenceEnabled.current"
        baseSource shouldContain "enabled && onClick != null"
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/$relativePath"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/$relativePath"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$relativePath was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
