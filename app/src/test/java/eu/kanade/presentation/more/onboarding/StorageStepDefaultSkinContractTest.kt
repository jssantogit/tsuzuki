package eu.kanade.presentation.more.onboarding

import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StorageStepDefaultSkinContractTest {

    @Test
    fun `storage onboarding does not revive retired Mihon help route`() {
        val source = readSource()

        source shouldNotContain "SettingsDataScreen.HELP_URL"
        source shouldNotContain "LocalUriHandler"
        source shouldNotContain "onboarding_storage_help_info"
        source shouldNotContain "onboarding_storage_help_action"
    }

    private fun readSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/onboarding/StorageStep.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/onboarding/StorageStep.kt"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("StorageStep.kt was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
