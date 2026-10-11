package eu.kanade.tachiyomi.ui.tsuzuki.settings

import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiAccountFormMode
import eu.kanade.presentation.more.settings.screen.showsPassword
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsTsuzukiAccountContractTest {

    @Test
    fun `recovery form never asks for a password`() {
        SettingsTsuzukiAccountFormMode.LOGIN.showsPassword.shouldBeTrue()
        SettingsTsuzukiAccountFormMode.REGISTER.showsPassword.shouldBeTrue()
        SettingsTsuzukiAccountFormMode.RECOVERY.showsPassword.shouldBeFalse()
    }

    @Test
    fun `account presentation keeps existing runtime owners`() {
        val source = readAccountScreenSource()

        source shouldContain "screenModel.login"
        source shouldContain "screenModel.register"
        source shouldContain "screenModel.sendPasswordRecovery"
        source shouldContain "screenModel.syncNow"
        source shouldContain "screenModel.logout"
    }

    @Test
    fun `connected account requires explicit sign out confirmation`() {
        val source = readAccountScreenSource()

        source shouldContain "showSignOutConfirmation"
        source shouldContain "AlertDialog("
    }

    private fun readAccountScreenSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("SettingsTsuzukiAccountScreen.kt was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
