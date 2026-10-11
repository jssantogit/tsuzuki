package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTag
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvas)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DefaultSkinSpacing.PageHorizontal, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_navigation_back),
                    onClick = navigator::pop,
                )
                DefaultSkinPageTitle(
                    text = registration?.descriptor?.name
                        ?: stringResource(MR.strings.tsuzuki_providers_title),
                    modifier = Modifier.weight(1f),
                )
            }

            val current = registration
            if (current == null) {
                Text(
                    text = stringResource(MR.strings.tsuzuki_providers_no_installed),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(DefaultSkinSpacing.PageHorizontal),
                )
                return@Column
            }

            val descriptor = current.descriptor
            val activeLanguages = current.facets.map { it.facetId }.toSet()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = DefaultSkinSpacing.PageHorizontal,
                    end = DefaultSkinSpacing.PageHorizontal,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Row),
            ) {
                errorMessage?.let { message ->
                    item(key = "error") {
                        DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(16.dp),
                                color = colors.secondary,
                            )
                        }
                    }
                }

                item(key = "status") {
                    DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = descriptor.name,
                                    color = colors.foreground,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val repository = (descriptor.origin as? ProviderOrigin.Repository)
                                    ?.repositoryId
                                    .orEmpty()
                                Text(
                                    text = listOf(
                                        descriptor.version.name,
                                        repository,
                                        providerLifecycleLabelDefaultSkinDetail(current.lifecycleStatus),
                                    ).filter(String::isNotBlank).joinToString(" · "),
                                    color = colors.secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = current.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                                enabled = current.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                                    current.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        runProviderUiCatching {
                                            withContext(Dispatchers.IO) {
                                                registry.setEnabled(providerId, enabled)
                                            }
                                        }.onFailure { error -> errorMessage = error.message }
                                    }
                                },
                            )
                        }
                    }
                }

                item(key = "capabilities_header") {
                    DefaultSkinSectionLabel(stringResource(MR.strings.tsuzuki_providers_capabilities))
                }
                item(key = "capabilities") {
                    DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                        FlowRow(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            descriptor.capabilities
                                .sortedWith(compareBy({ it.id }, { it.version }))
                                .forEach { capability ->
                                    DefaultSkinTag(
                                        text = "${capability.id} · v${capability.version}",
                                    )
                                }
                        }
                    }
                }

                item(key = "permissions_header") {
                    DefaultSkinSectionLabel(stringResource(MR.strings.tsuzuki_providers_permissions))
                }
                descriptor.permissions.network?.let { permission ->
                    item(key = "permission_network") {
                        ProviderDetailInfoCard(
                            title = "HTTP",
                            summary = buildString {
                                append(permission.origins.sorted().joinToString())
                                if (permission.localNetwork) {
                                    if (isNotBlank()) append(" · ")
                                    append(stringResource(MR.strings.tsuzuki_providers_permission_local_network))
                                }
                            },
                        )
                    }
                }
                descriptor.permissions.browser?.let { permission ->
                    item(key = "permission_browser") {
                        ProviderDetailInfoCard(
                            title = stringResource(MR.strings.tsuzuki_providers_permission_browser),
                            summary = permission.origins.sorted().joinToString(),
                        )
                    }
                }
                if (descriptor.permissions.storage.enabled) {
                    item(key = "permission_storage") {
                        ProviderDetailInfoCard(
                            title = stringResource(MR.strings.tsuzuki_providers_permission_storage),
                            summary = null,
                        )
                    }
                }
                if (descriptor.permissions.secrets.isNotEmpty()) {
                    item(key = "permission_secrets") {
                        ProviderDetailInfoCard(
                            title = stringResource(MR.strings.tsuzuki_providers_permission_secrets),
                            summary = descriptor.permissions.secrets.sorted().joinToString(),
                        )
                    }
                }

                if (descriptor.contentLanguages.isNotEmpty()) {
                    item(key = "languages_header") {
                        DefaultSkinSectionLabel(stringResource(MR.strings.tsuzuki_providers_languages))
                    }
                    items(
                        items = descriptor.contentLanguages.sorted(),
                        key = { language -> "language:$language" },
                    ) { language ->
                        val selected = language in activeLanguages
                        DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = language,
                                    modifier = Modifier.weight(1f),
                                    color = colors.foreground,
                                )
                                Switch(
                                    checked = selected,
                                    enabled = current.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                                        current.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                                    onCheckedChange = { enabled ->
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
                                )
                            }
                        }
                    }
                }

                item(key = "settings_header") {
                    DefaultSkinSectionLabel(stringResource(MR.strings.tsuzuki_providers_settings))
                }
                if (descriptor.settings.isEmpty()) {
                    item(key = "settings_empty") {
                        ProviderDetailInfoCard(
                            title = stringResource(MR.strings.tsuzuki_providers_no_settings),
                            summary = null,
                        )
                    }
                } else {
                    items(
                        items = descriptor.settings,
                        key = { setting -> setting.key },
                    ) { setting ->
                        val summary = buildList {
                            add(setting.type.name.lowercase().replaceFirstChar { it.uppercase() })
                            if (setting.required) {
                                add(stringResource(MR.strings.tsuzuki_providers_setting_required))
                            }
                            if (setting.options.isNotEmpty()) add(setting.options.joinToString())
                        }.joinToString(" · ")
                        ProviderDetailInfoCard(
                            title = setting.label,
                            summary = summary,
                        )
                    }
                    if (
                        descriptor.settings.any { it.type == ProviderSettingType.SECRET } ||
                        descriptor.permissions.secrets.isNotEmpty()
                    ) {
                        item(key = "secret_notice") {
                            ProviderDetailInfoCard(
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

@Composable
private fun ProviderDetailInfoCard(
    title: String,
    summary: String?,
) {
    val colors = defaultSkinColors()
    DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
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
