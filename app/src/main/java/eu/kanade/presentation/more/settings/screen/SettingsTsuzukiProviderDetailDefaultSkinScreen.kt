package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.more.settings.widget.SettingsPreferenceSwitchIndicator
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTag
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SettingsTsuzukiProviderDetailDefaultSkinScreen(
    private val providerIdValue: String,
) : Screen() {

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val registry = remember { context.appGraph.installedScriptProviderRegistry }
        val providerId = remember(providerIdValue) { ProviderId(providerIdValue) }
        val colors = defaultSkinColors()
        var registration by remember { mutableStateOf<ProviderRegistration?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        suspend fun reload() {
            registration = withContext(Dispatchers.IO) { registry.registration(providerId) }
        }

        LaunchedEffect(registry, providerId) {
            reload()
            registry.observeChanges().collect { reload() }
        }

        SettingsCanvas {
            Column(modifier = Modifier.fillMaxSize()) {
                val current = registration
                SettingsPageHeader(
                    title = current?.descriptor?.name
                        ?: stringResource(MR.strings.tsuzuki_providers_title),
                    navigateUp = navigator::pop,
                )

                if (current == null) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_providers_no_installed),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = DefaultSkinSpacing.PageHorizontal),
                    )
                    return@Column
                }

                val descriptor = current.descriptor
                val activeLanguages = current.facets.map { it.facetId }.toSet()
                val canChange = current.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                    current.lifecycleStatus != ProviderLifecycleStatus.INVALID
                val permissionRows = buildList {
                    descriptor.permissions.network?.let { permission ->
                        add(
                            "HTTP" to buildString {
                                append(permission.origins.sorted().joinToString())
                                if (permission.localNetwork) {
                                    if (isNotBlank()) append(" · ")
                                    append(stringResource(MR.strings.tsuzuki_providers_permission_local_network))
                                }
                            },
                        )
                    }
                    descriptor.permissions.browser?.let { permission ->
                        add(
                            stringResource(MR.strings.tsuzuki_providers_permission_browser) to
                                permission.origins.sorted().joinToString(),
                        )
                    }
                    if (descriptor.permissions.storage.enabled) {
                        add(stringResource(MR.strings.tsuzuki_providers_permission_storage) to null)
                    }
                    if (descriptor.permissions.secrets.isNotEmpty()) {
                        add(
                            stringResource(MR.strings.tsuzuki_providers_permission_secrets) to
                                descriptor.permissions.secrets.sorted().joinToString(),
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Row),
                ) {
                    errorMessage?.let { message ->
                        item(key = "error") {
                            SettingsGroup {
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    item(key = "status") {
                        SettingsGroup {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = listOf(
                                            descriptor.version.name,
                                            (descriptor.origin as? ProviderOrigin.Repository)?.repositoryId.orEmpty(),
                                        ).filter(String::isNotBlank).joinToString(" · "),
                                        color = colors.secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = providerLifecycleLabelDefaultSkinDetail(current.lifecycleStatus),
                                        color = colors.foreground,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .semantics { contentDescription = descriptor.name }
                                        .toggleable(
                                            value = current.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                                            enabled = canChange,
                                            role = Role.Switch,
                                        ) { enabled ->
                                            scope.launch {
                                                runProviderUiCatching {
                                                    withContext(Dispatchers.IO) {
                                                        registry.setEnabled(providerId, enabled)
                                                    }
                                                }.onFailure { error -> errorMessage = error.message }
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SettingsPreferenceSwitchIndicator(
                                        checked = current.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                                        enabled = canChange,
                                    )
                                }
                            }
                        }
                    }

                    if (descriptor.capabilities.isNotEmpty()) {
                        item(key = "capabilities") {
                            SettingsGroup(title = stringResource(MR.strings.tsuzuki_providers_capabilities)) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    descriptor.capabilities
                                        .sortedWith(compareBy({ it.id }, { it.version }))
                                        .forEach { capability ->
                                            DefaultSkinTag(text = "${capability.id} · v${capability.version}")
                                        }
                                }
                            }
                        }
                    }

                    if (permissionRows.isNotEmpty()) {
                        item(key = "permissions") {
                            SettingsGroup(title = stringResource(MR.strings.tsuzuki_providers_permissions)) {
                                permissionRows.forEachIndexed { index, row ->
                                    ProviderDetailInfoRow(title = row.first, summary = row.second)
                                    if (index < permissionRows.lastIndex) SettingsGroupDivider()
                                }
                            }
                        }
                    }

                    if (descriptor.contentLanguages.isNotEmpty()) {
                        item(key = "languages") {
                            SettingsGroup(title = stringResource(MR.strings.tsuzuki_providers_languages)) {
                                descriptor.contentLanguages.sorted().forEachIndexed { index, language ->
                                    val selected = language in activeLanguages
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            text = language,
                                            modifier = Modifier.weight(1f),
                                            color = colors.foreground,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .semantics { contentDescription = language }
                                                .toggleable(
                                                    value = selected,
                                                    enabled = canChange,
                                                    role = Role.Switch,
                                                ) { enabled ->
                                                    val next = activeLanguages.toMutableSet().apply {
                                                        if (enabled) add(language) else remove(language)
                                                    }
                                                    val selection = if (next == descriptor.contentLanguages) {
                                                        null
                                                    } else {
                                                        next.toSet()
                                                    }
                                                    scope.launch {
                                                        runProviderUiCatching {
                                                            withContext(Dispatchers.IO) {
                                                                registry.setEnabledContentLanguages(
                                                                    providerId = providerId,
                                                                    languages = selection,
                                                                )
                                                            }
                                                        }.onFailure { error -> errorMessage = error.message }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            SettingsPreferenceSwitchIndicator(checked = selected, enabled = canChange)
                                        }
                                    }
                                    if (index < descriptor.contentLanguages.size - 1) SettingsGroupDivider()
                                }
                            }
                        }
                    }

                    val hasSecretNotice = descriptor.settings.any { it.type == ProviderSettingType.SECRET } ||
                        descriptor.permissions.secrets.isNotEmpty()
                    item(key = "settings") {
                        SettingsGroup(title = stringResource(MR.strings.tsuzuki_providers_settings)) {
                            if (descriptor.settings.isEmpty()) {
                                ProviderDetailInfoRow(
                                    title = stringResource(MR.strings.tsuzuki_providers_no_settings),
                                    summary = null,
                                )
                            } else {
                                descriptor.settings.forEachIndexed { index, setting ->
                                    val summary = buildList {
                                        add(setting.type.name.lowercase().replaceFirstChar { it.uppercase() })
                                        if (setting.required) {
                                            add(stringResource(MR.strings.tsuzuki_providers_setting_required))
                                        }
                                        if (setting.options.isNotEmpty()) add(setting.options.joinToString())
                                    }.joinToString(" · ")
                                    ProviderDetailInfoRow(title = setting.label, summary = summary)
                                    if (index < descriptor.settings.lastIndex || hasSecretNotice) {
                                        SettingsGroupDivider()
                                    }
                                }
                            }
                            if (hasSecretNotice) {
                                if (descriptor.settings.isEmpty()) SettingsGroupDivider()
                                ProviderDetailInfoRow(
                                    title = stringResource(MR.strings.tsuzuki_providers_permission_secrets),
                                    summary = stringResource(MR.strings.tsuzuki_providers_secret_notice),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailInfoRow(
    title: String,
    summary: String?,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = colors.foreground,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        summary?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                color = colors.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun providerLifecycleLabelDefaultSkinDetail(status: ProviderLifecycleStatus): String = stringResource(
    when (status) {
        ProviderLifecycleStatus.ENABLED -> MR.strings.tsuzuki_providers_enabled
        ProviderLifecycleStatus.DISABLED -> MR.strings.tsuzuki_providers_disabled
        ProviderLifecycleStatus.BLOCKED -> MR.strings.tsuzuki_providers_blocked
        ProviderLifecycleStatus.INVALID -> MR.strings.tsuzuki_providers_invalid
    },
)
