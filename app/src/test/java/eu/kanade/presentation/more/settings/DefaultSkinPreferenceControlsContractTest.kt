package eu.kanade.presentation.more.settings

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinPreferenceControlsContractTest {

    @Test
    fun `preference dispatcher uses Default Skin slider and retains model callbacks`() {
        val source = readSource("PreferenceItem.kt")

        source shouldNotContain "BaseSliderItem("
        source shouldContain "SliderPreferenceWidget("
        source shouldContain "valueRange = item.valueRange"
        source shouldContain "steps = item.steps"
        source shouldContain "item.valueString.takeUnless { it.isNullOrEmpty() } ?: item.value.toString()"
        source shouldContain "if (item.onValueChanged(newValue))"
        source shouldContain "item.preference.set(newValue)"
        source shouldContain "item.internalOnValueChanged(newValue!!)"
        source shouldContain "item.internalOnValueChanged(newValues)"
        source shouldContain "item.internalSet(newValues)"
        source shouldContain "val accepted = item.onValueChanged(it)"
        source shouldContain "if (accepted) item.preference.set(it)"
    }

    @Test
    fun `preference rows use semantic Default Skin colors without clipping supporting text`() {
        val base = readSource("widget/BasePreferenceWidget.kt")
        val highlight = readSource("widget/PreferenceHighlight.kt")
        val text = readSource("widget/TextPreferenceWidget.kt")

        base shouldContain "defaultSkinColors()"
        base shouldContain "colors.foreground"
        base shouldContain "colors.secondary"
        base shouldNotContain "MaterialTheme.colorScheme.surfaceTint"
        base shouldContain "settingsPreferenceHighlightBackground(highlighted)"
        highlight shouldContain "colors.surfaceMuted"
        base shouldNotContain "TextOverflow.Ellipsis"
        base shouldNotContain ".alpha(if (enabled) 1f else 0.38f)"
        text shouldContain "defaultSkinColors()"
        text shouldContain "colors.secondary"
        text shouldNotContain "secondaryItemAlpha()"
        text shouldNotContain "maxLines = 10"
    }

    @Test
    fun `boolean preference remains one accessible row toggle with a custom indicator`() {
        val base = readSource("widget/BasePreferenceWidget.kt")
        val switch = readSource("widget/SwitchPreferenceWidget.kt")

        base shouldContain "Modifier.toggleable("
        base shouldContain "value = toggleableState"
        base shouldContain "role = Role.Switch"
        switch shouldContain "toggleableState = checked"
        switch shouldContain "SettingsPreferenceSwitchIndicator(checked = checked, enabled = enabled)"
        switch shouldContain "onClick = { onCheckedChanged(!checked) }"
        switch shouldContain "defaultSkinColors()"
        switch shouldContain "colors.accent"
        switch shouldContain "colors.onAccent"
        switch shouldNotContain "androidx.compose.material3.Switch"
    }

    @Test
    fun `slider presents the existing value and range with Default Skin colors`() {
        val slider = readSource("widget/SliderPreferenceWidget.kt")

        slider shouldContain "Slider("
        slider shouldContain "valueRange = valueRange"
        slider shouldContain "steps = steps"
        slider shouldContain "valueString"
        slider shouldContain "enabled = enabled"
        slider shouldContain "SliderDefaults.colors("
        slider shouldContain "defaultSkinColors()"
        slider shouldContain "colors.accent"
        slider shouldContain "colors.outline"
        slider shouldNotContain "TextOverflow.Ellipsis"
    }

    @Test
    fun `single and multi selection use one scrollable Default Skin sheet family`() {
        val single = readSource("widget/ListPreferenceWidget.kt")
        val multi = readSource("widget/MultiSelectListPreferenceWidget.kt")
        val sheet = readSource("widget/PreferenceControlSheet.kt")

        single shouldNotContain "AlertDialog("
        multi shouldNotContain "AlertDialog("
        single shouldContain "PreferenceControlSheet("
        multi shouldContain "PreferenceControlSheet("
        single shouldContain "selected = value == current.key"
        single shouldContain "onValueChange(current.key!!)"
        sheet shouldContain "onSelectionChange(!selected)"
        single shouldContain "ScrollbarLazyColumn("
        single shouldContain "PreferenceOptionRow("
        multi shouldContain "remember {"
        multi shouldContain "values.contains(it)"
        multi shouldContain "onValuesChange(selected.toMutableSet())"
        multi shouldContain "ScrollbarLazyColumn("
        multi shouldContain "PreferenceOptionRow("
        multi shouldContain "toggleable = true"
        multi.substringAfter("ScrollbarLazyColumn(") shouldNotContain "onValuesChange("
        sheet shouldContain "ModalBottomSheet("
        sheet shouldContain "defaultSkinColors()"
        sheet shouldContain "Modifier.selectable("
        sheet shouldContain "Modifier.toggleable("
        sheet shouldNotContain "AlertDialog("
        sheet shouldNotContain "RadioButton("
        sheet shouldNotContain "LabeledCheckbox("
        sheet shouldNotContain "TextOverflow.Ellipsis"
    }

    @Test
    fun `edit text keeps validation and only dismisses after accepted confirmation`() {
        val source = readSource("widget/EditTextPreferenceWidget.kt")

        source shouldNotContain "OutlinedTextField("
        source shouldNotContain "AlertDialog("
        source shouldContain "PreferenceControlSheet("
        source shouldContain "onConfirm: suspend (String) -> Boolean"
        source shouldContain "BasicTextField("
        source shouldContain "TextFieldValue(value)"
        source shouldContain "textFieldValue.text.isBlank()"
        source shouldContain "textFieldValue.text != value && textFieldValue.text.isNotBlank()"
        source shouldContain "if (onConfirm(textFieldValue.text))"
        source shouldContain "onDismissRequest()"
        source shouldContain "tsuzuki_providers_setting_required"
        source shouldContain "error(requiredMessage)"
        source.substringAfter("DefaultSkinSecondaryButton(")
            .substringBefore("DefaultSkinPrimaryButton(") shouldNotContain "onConfirm("
        source shouldContain "tsuzuki_action_clear"
        source shouldContain "defaultSkinColors()"
    }

    @Test
    fun `informational preference uses secondary semantic treatment without a nested surface`() {
        val source = readSource("widget/InfoWidget.kt")
        val implementation = source.substringAfter("internal fun InfoWidget(text: String)")
            .substringBefore("@PreviewLightDark")

        implementation shouldContain "defaultSkinColors()"
        implementation shouldContain "colors.secondary"
        implementation shouldNotContain "MaterialTheme.padding"
        implementation shouldNotContain "secondaryItemAlpha()"
        implementation shouldNotContain "Surface("
        implementation shouldContain "text = text"
    }

    @Test
    fun `theme previews keep their own theme colors inside the preview surface`() {
        val source = readSource("widget/AppThemePreferenceWidget.kt")

        source shouldContain "TachiyomiTheme("
        source shouldContain ".background(MaterialTheme.colorScheme.background)"
        source shouldContain "MaterialTheme.colorScheme.primary"
        source shouldContain "MaterialTheme.colorScheme.tertiary"
    }

    @Test
    fun `F1A shared Settings group composition remains the Preference shell`() {
        val source = readSource("components/SettingsPresentation.kt")
        val preferenceScreen = readSource("PreferenceScreen.kt")

        source shouldContain "internal fun SettingsGroup("
        source shouldContain "color = colors.surface"
        source shouldContain "SettingsGroupDivider("
        source shouldContain "defaultSkinColors()"
        preferenceScreen shouldContain "SettingsGroup("
        preferenceScreen shouldContain "SettingsGroupDivider("
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
