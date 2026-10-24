package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        source shouldContain "screenModel::syncNow"
        source shouldContain "screenModel.logout"
        source shouldContain "screenModel.clearFeedback()"
    }

    @Test
    fun `connected account requires explicit sign out confirmation`() {
        val source = readAccountScreenSource()

        source shouldContain "showSignOutConfirmation"
        source shouldContain "PreferenceControlSheet("
        source shouldContain "onSignOut = { showSignOutConfirmation = true }"
        source shouldContain "onDismissRequest = { showSignOutConfirmation = false }"
        source.split("screenModel.logout()").size.shouldBe(2)
        source shouldNotContain "AlertDialog("
    }

    @Test
    fun `account uses shared settings shell and grouped default skin surfaces`() {
        val source = readAccountScreenSource()

        source shouldContain "SettingsCanvas {"
        source shouldContain "SettingsPageHeader("
        source shouldContain "SettingsGroup(title = stringResource(mode.groupTitleResource()))"
        source shouldContain "SettingsGroup(title = stringResource(MR.strings.tsuzuki_account_title))"
        source shouldContain "SettingsGroup(title = stringResource(MR.strings.tsuzuki_account_sync_section))"
        source shouldNotContain "SettingsGroup(title = stringResource(MR.strings.drive_sync_status))"
        source shouldContain "defaultSkinColors()"
        source shouldNotContain "AccountSurface("
        source shouldNotContain "Scaffold("
        source shouldNotContain "AppBar("
        source shouldNotContain "surfaceContainerLowest"
    }

    @Test
    fun `account contains no stock material fields buttons or dialogs`() {
        val source = readAccountScreenSource()

        listOf("OutlinedTextField(", "OutlinedButton(", "AlertDialog(").forEach { source shouldNotContain it }
        Regex("(?m)^\\s*Button\\(").containsMatchIn(source).shouldBeFalse()
        source shouldContain "DefaultSkinFormField("
        source shouldContain "DefaultSkinPrimaryButton("
    }

    @Test
    fun `shared account field uses default skin surface and text roles`() {
        val source = readDefaultSkinPrimitivesSource()
            .substringAfter("fun DefaultSkinFormField(")
            .substringBefore("\n@Composable")

        source shouldContain "defaultSkinColors()"
        source shouldContain "color = colors.surface"
        source shouldContain "contentColor = colors.foreground"
        source shouldContain "colors.outline"
        source shouldContain "cursorBrush = SolidColor(colors.foreground)"
        source shouldContain "Modifier.heightIn(min = 48.dp)"
    }

    @Test
    fun `connected account shows only runtime identity and sync state`() {
        val source = readAccountScreenSource()

        source shouldContain "email = account.account.email"
        source shouldContain "state.isSyncing"
        source shouldContain "screenModel::syncNow"
        source shouldNotContain "lastSync"
        source shouldNotContain "timestamp"
        source shouldNotContain "displayName"
        source shouldNotContain "avatar"
        source shouldNotContain "username"
        source shouldNotContain "AccountSyncDetail"
    }

    @Test
    fun `entry and account share field presentation without changing entry behavior`() {
        val source = readEntryScreenSource()

        source shouldContain "DefaultSkinFormField("
        source shouldContain "isPassword = true"
        source shouldContain "passwordVisible = passwordVisible"
        source shouldContain "onTogglePassword = { passwordVisible = !passwordVisible }"
        source shouldContain "TsuzukiEntryError.INVALID_INPUT"
        source shouldContain "onLogin ="
        source shouldContain "onRegister ="
    }

    private fun readAccountScreenSource(): String {
        return readSource(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiAccountScreen.kt"),
        )
    }

    private fun readEntryScreenSource(): String = readSource(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/entry/TsuzukiEntryScreen.kt"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/entry/TsuzukiEntryScreen.kt"),
    )

    private fun readDefaultSkinPrimitivesSource(): String = readSource(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/components/DefaultSkinPrimitives.kt"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/components/DefaultSkinPrimitives.kt"),
    )

    private fun readSource(vararg candidates: Path): String {
        val source = candidates.firstOrNull(Files::exists)
            ?: error("Expected source was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
