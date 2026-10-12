package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsNavigationRow
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.more.settings.widget.SettingsPreferenceSwitchIndicator
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextField
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationSettingsItem
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiIntegrationsSettingsState
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowRight
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCategory
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SettingsTsuzukiIntegrationsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiIntegrationsSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val colors = defaultSkinColors()

        SettingsCanvas {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsPageHeader(
                    title = stringResource(MR.strings.tsuzuki_integrations_title),
                    navigateUp = navigator::pop,
                )

                when (val current = state) {
                    TsuzukiIntegrationsSettingsState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.foreground)
                        }
                    }

                    is TsuzukiIntegrationsSettingsState.Loaded -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = DefaultSkinSpacing.Section),
                        ) {
                            IntegrationCategory.entries.forEach { category ->
                                val categoryItems = current.items.filter { it.category == category }
                                if (categoryItems.isEmpty()) return@forEach
                                item(key = "category_${category.name}") {
                                    SettingsGroup(title = stringResource(category.labelRes())) {
                                        categoryItems.forEachIndexed { index, item ->
                                            IntegrationSettingRow(
                                                item = item,
                                                onEnabledChange = {
                                                    screenModel.setEnabled(item.id, it)
                                                },
                                                onOpen = {
                                                    navigator.push(
                                                        SettingsTsuzukiIntegrationDetailScreen(item.id.value),
                                                    )
                                                },
                                            )
                                            if (index < categoryItems.lastIndex) {
                                                SettingsGroupDivider()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class SettingsTsuzukiIntegrationDetailScreen(
    private val integrationId: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiIntegrationsSettingsScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()
        val colors = defaultSkinColors()
        val item = (state as? TsuzukiIntegrationsSettingsState.Loaded)
            ?.items
            ?.firstOrNull { it.id.value == integrationId }
        val tracker = remember(item?.legacyTrackerId) {
            item?.legacyTrackerId?.let { context.appGraph.trackerManager.get(it) }
        }
        var malClientIdConfigured by remember(tracker) {
            mutableStateOf((tracker as? MyAnimeList)?.hasClientId() ?: true)
        }

        SettingsCanvas {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsPageHeader(
                    title = item?.label ?: stringResource(MR.strings.tsuzuki_integration_fallback_title),
                    navigateUp = navigator::pop,
                )

                if (item == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.foreground)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = DefaultSkinSpacing.Section),
                    ) {
                        providerDescriptionRes(item.id.value)?.let { description ->
                            item(key = "provider_summary") {
                                SettingsGroup {
                                    ProviderSummary(
                                        tracker = tracker,
                                        description = stringResource(description),
                                    )
                                }
                            }
                        }

                        when (item.id.value) {
                            "mal" -> item(key = "mal_client_id") {
                                SettingsGroup(title = stringResource(MR.strings.tsuzuki_mal_client_id_title)) {
                                    MalClientIdConfiguration(
                                        tracker = tracker as? MyAnimeList,
                                        onConfiguredChanged = { configured ->
                                            malClientIdConfigured = configured
                                            if (!configured && item.enabled) {
                                                screenModel.setEnabled(item.id, false)
                                            }
                                        },
                                    )
                                }
                            }
                            "shikimori" -> item(key = "shikimori_oauth_app") {
                                SettingsGroup {
                                    ShikimoriCredentialsConfiguration(tracker as? Shikimori)
                                }
                            }
                            "hikka" -> item(key = "hikka_oauth_app") {
                                SettingsGroup {
                                    HikkaCredentialsConfiguration(tracker as? Hikka)
                                }
                            }
                        }

                        if (item.configurableCapabilities.isNotEmpty()) {
                            item(key = "integration_settings") {
                                SettingsGroup(title = stringResource(MR.strings.action_settings)) {
                                    IntegrationToggleRow(
                                        title = if (item.id.value == "tsuzuki") {
                                            stringResource(MR.strings.tsuzuki_integration_enable_tsuzuki)
                                        } else {
                                            stringResource(MR.strings.tsuzuki_integration_use_provider, item.label)
                                        },
                                        checked = item.enabled,
                                        enabled = item.id.value != "mal" || malClientIdConfigured || item.enabled,
                                        onCheckedChange = { screenModel.setEnabled(item.id, it) },
                                    )

                                    val configurable = item.capabilities.filter {
                                        it in item.configurableCapabilities
                                    }
                                    configurable.forEach { capability ->
                                        SettingsGroupDivider()
                                        IntegrationToggleRow(
                                            title = stringResource(capability.labelRes()),
                                            checked = item.capabilityEnabled[capability] ?: true,
                                            enabled = item.enabled,
                                            onCheckedChange = {
                                                screenModel.setCapabilityEnabled(item.id, capability, it)
                                            },
                                            modifier = Modifier.alpha(if (item.enabled) 1f else 0.45f),
                                        )
                                    }
                                }
                            }
                        }

                        if (item.id.value == "tsuzuki") {
                            item(key = "tracking_behavior") {
                                SettingsGroup {
                                    IntegrationActionRow(
                                        title = stringResource(
                                            MR.strings.tsuzuki_integrations_tracking_behavior_title,
                                        ),
                                        summary = stringResource(
                                            MR.strings.tsuzuki_integrations_tracking_behavior_summary,
                                        ),
                                        onClick = { navigator.push(SettingsTsuzukiTrackingBehaviorScreen) },
                                    )
                                }
                            }
                        }

                        if (item.restrictedCapabilities.isNotEmpty()) {
                            item(key = "restricted_summary") {
                                SettingsGroup {
                                    IntegrationInfoRow(
                                        title = stringResource(MR.strings.tsuzuki_integration_catalog_unavailable),
                                        summary = stringResource(
                                            MR.strings.tsuzuki_integration_catalog_unavailable_summary,
                                        ),
                                    )
                                }
                            }
                        }

                        if (item.supportsTracking && item.legacyTrackerId != null) {
                            item(key = "account_tracking") {
                                SettingsGroup {
                                    IntegrationActionRow(
                                        title = stringResource(MR.strings.tsuzuki_integration_account_tracking),
                                        summary = stringResource(
                                            MR.strings.tsuzuki_integration_account_tracking_summary,
                                            item.label,
                                        ),
                                        onClick = {
                                            navigator.push(SettingsTsuzukiTrackingServiceScreen(item.legacyTrackerId))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MalClientIdConfiguration(
    tracker: MyAnimeList?,
    onConfiguredChanged: (Boolean) -> Unit,
) {
    var savedClientId by remember(tracker) { mutableStateOf(tracker?.getClientId().orEmpty()) }
    var clientId by remember(tracker) { mutableStateOf(savedClientId) }
    val colors = defaultSkinColors()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(MR.strings.tsuzuki_mal_client_id_summary),
            color = colors.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        DefaultSkinTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = stringResource(MR.strings.tsuzuki_mal_client_id_title),
            supportingText = stringResource(MR.strings.tsuzuki_mal_client_id_setup),
            modifier = Modifier.fillMaxWidth(),
        )
        DefaultSkinTextAction(
            text = stringResource(MR.strings.action_save),
            onClick = {
                tracker?.setClientId(clientId)
                savedClientId = tracker?.getClientId().orEmpty()
                clientId = savedClientId
                onConfiguredChanged(savedClientId.isNotBlank())
            },
            modifier = Modifier.alpha(if (tracker != null && clientId.trim() != savedClientId) 1f else 0.45f),
        )
    }
}

@Composable
private fun ShikimoriCredentialsConfiguration(tracker: Shikimori?) {
    ApplicationCredentialsConfiguration(
        clientId = tracker?.getClientId().orEmpty(),
        clientSecret = tracker?.getClientSecret().orEmpty(),
        clientIdLabel = stringResource(MR.strings.tsuzuki_oauth_client_id_title),
        clientSecretLabel = stringResource(MR.strings.tsuzuki_oauth_client_secret_title),
        summary = stringResource(MR.strings.tsuzuki_shikimori_credentials_summary),
        setup = stringResource(MR.strings.tsuzuki_shikimori_credentials_setup),
        onSave = { clientId, clientSecret -> tracker?.setApplicationCredentials(clientId, clientSecret) },
    )
}

@Composable
private fun HikkaCredentialsConfiguration(tracker: Hikka?) {
    ApplicationCredentialsConfiguration(
        clientId = tracker?.getClientReference().orEmpty(),
        clientSecret = tracker?.getClientSecret().orEmpty(),
        clientIdLabel = stringResource(MR.strings.tsuzuki_hikka_reference_title),
        clientSecretLabel = stringResource(MR.strings.tsuzuki_hikka_client_secret_title),
        summary = stringResource(MR.strings.tsuzuki_hikka_credentials_summary),
        setup = stringResource(MR.strings.tsuzuki_hikka_credentials_setup),
        onSave = { clientReference, clientSecret ->
            tracker?.setApplicationCredentials(clientReference, clientSecret)
        },
    )
}

@Composable
private fun ApplicationCredentialsConfiguration(
    clientId: String,
    clientSecret: String,
    clientIdLabel: String,
    clientSecretLabel: String,
    summary: String,
    setup: String,
    onSave: (String, String) -> Unit,
) {
    var savedClientId by remember(clientId) { mutableStateOf(clientId) }
    var savedClientSecret by remember(clientSecret) { mutableStateOf(clientSecret) }
    var editedClientId by remember(clientId) { mutableStateOf(clientId) }
    var editedClientSecret by remember(clientSecret) { mutableStateOf(clientSecret) }
    val colors = defaultSkinColors()

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(summary, color = colors.secondary, style = MaterialTheme.typography.bodyMedium)
        DefaultSkinTextField(
            value = editedClientId,
            onValueChange = { editedClientId = it },
            label = clientIdLabel,
            modifier = Modifier.fillMaxWidth(),
        )
        DefaultSkinTextField(
            value = editedClientSecret,
            onValueChange = { editedClientSecret = it },
            label = clientSecretLabel,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            supportingText = setup,
        )
        DefaultSkinTextAction(
            text = stringResource(MR.strings.action_save),
            onClick = {
                onSave(editedClientId, editedClientSecret)
                savedClientId = editedClientId.trim()
                savedClientSecret = editedClientSecret.trim()
                editedClientId = savedClientId
                editedClientSecret = savedClientSecret
            },
            modifier = Modifier.alpha(
                if (
                    editedClientId.trim() != savedClientId ||
                    editedClientSecret.trim() != savedClientSecret
                ) {
                    1f
                } else {
                    0.45f
                },
            ),
        )
    }
}

@Composable
private fun ProviderSummary(tracker: Tracker?, description: String) {
    val colors = defaultSkinColors()
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tracker?.let { TrackLogoIcon(it) }
        Text(
            text = description,
            modifier = Modifier.weight(1f),
            color = colors.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun IntegrationSettingRow(
    item: TsuzukiIntegrationSettingsItem,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val colors = defaultSkinColors()
    val tracker = remember(item.legacyTrackerId) {
        item.legacyTrackerId?.let { context.appGraph.trackerManager.get(it) }
    }
    val malClientIdConfigured = (tracker as? MyAnimeList)?.hasClientId() ?: true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                item.id.value == "tsuzuki" -> IntegrationBrandIcon(providerId = "tsuzuki", size = 32.dp)
                tracker != null -> TrackLogoIcon(tracker)
                else -> Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(colors.surfaceMuted, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.label, color = colors.foreground, fontWeight = FontWeight.Medium)
                if (item.id.value == "mal" && !malClientIdConfigured) {
                    Text(
                        stringResource(MR.strings.tsuzuki_mal_client_id_required),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowRight,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (item.configurableCapabilities.isNotEmpty()) {
            IntegrationSwitch(
                checked = item.enabled,
                enabled = item.enabled || malClientIdConfigured,
                label = item.label,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun IntegrationToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = colors.foreground,
            style = MaterialTheme.typography.bodyLarge,
        )
        IntegrationSwitch(
            checked = checked,
            enabled = enabled,
            label = title,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun IntegrationSwitch(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SettingsPreferenceSwitchIndicator(checked = checked, enabled = enabled)
    }
}

@Composable
private fun IntegrationInfoRow(title: String, summary: String) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = colors.foreground, fontWeight = FontWeight.Medium)
        Text(summary, color = colors.secondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IntegrationActionRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    SettingsNavigationRow(
        title = title,
        subtitle = summary,
        onClick = onClick,
    )
}

private fun IntegrationCategory.labelRes(): StringResource = when (this) {
    IntegrationCategory.GENERAL -> MR.strings.tsuzuki_integrations_category_general
    IntegrationCategory.METADATA_SERVICE -> MR.strings.tsuzuki_integrations_category_metadata
    IntegrationCategory.PERSONAL_SERVER -> MR.strings.tsuzuki_integrations_category_servers
    IntegrationCategory.COMPATIBILITY -> MR.strings.tsuzuki_integrations_category_compatibility
}

private fun IntegrationCapability.labelRes(): StringResource = when (this) {
    IntegrationCapability.SEARCH -> MR.strings.tsuzuki_integration_capability_search
    IntegrationCapability.DISCOVERY -> MR.strings.tsuzuki_integration_capability_discovery
    IntegrationCapability.METADATA_BASIC -> MR.strings.tsuzuki_integration_capability_metadata_basic
    IntegrationCapability.METADATA_ARTWORK -> MR.strings.tsuzuki_integration_capability_metadata_artwork
    IntegrationCapability.METADATA_EDITORIAL -> MR.strings.tsuzuki_integration_capability_metadata_editorial
    IntegrationCapability.METADATA_STAFF -> MR.strings.tsuzuki_integration_capability_metadata_staff
    IntegrationCapability.RATINGS -> MR.strings.tsuzuki_integration_capability_ratings
    IntegrationCapability.RELATIONS -> MR.strings.tsuzuki_integration_capability_relations
    IntegrationCapability.CROSSWALK -> MR.strings.tsuzuki_integration_capability_crosswalk
    IntegrationCapability.TRACKING -> MR.strings.tsuzuki_integration_capability_tracking
    IntegrationCapability.USER_LISTS -> MR.strings.tsuzuki_integration_capability_user_lists
    IntegrationCapability.REMOTE_LIBRARY -> MR.strings.tsuzuki_integration_capability_remote_library
    IntegrationCapability.READING_CONTENT -> MR.strings.tsuzuki_integration_capability_reading_content
    IntegrationCapability.DOWNLOADS -> MR.strings.tsuzuki_integration_capability_downloads
}

private fun providerDescriptionRes(integrationId: String): StringResource? = when (integrationId) {
    "kitsu" -> MR.strings.tsuzuki_integration_provider_kitsu
    "mal" -> MR.strings.tsuzuki_integration_provider_mal
    "mangaupdates" -> MR.strings.tsuzuki_integration_provider_mangaupdates
    "bangumi" -> MR.strings.tsuzuki_integration_provider_bangumi
    "shikimori" -> MR.strings.tsuzuki_integration_provider_shikimori
    "hikka" -> MR.strings.tsuzuki_integration_provider_hikka
    "komga" -> MR.strings.tsuzuki_integration_provider_komga
    "kavita" -> MR.strings.tsuzuki_integration_provider_kavita
    "suwayomi" -> MR.strings.tsuzuki_integration_provider_suwayomi
    else -> null
}
