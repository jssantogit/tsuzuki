package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.DefaultSkinChoiceChip
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextField
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.core.provider.supplychain.EnrolledProviderRepository
import tachiyomi.core.provider.supplychain.ProviderRepositoryCatalogEntry
import tachiyomi.core.provider.supplychain.ProviderRepositoryEnrollment
import tachiyomi.core.provider.supplychain.ProviderRepositoryEntryStatus
import tachiyomi.core.provider.supplychain.ProviderRepositorySigningKey
import tachiyomi.core.provider.supplychain.ProviderRepositorySnapshot
import tachiyomi.core.provider.supplychain.providerRepositoryKeyFingerprint
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Default Skin public Provider platform surface.
 *
 * Metadata integrations are intentionally excluded here. They are configured through
 * [SettingsTsuzukiIntegrationsScreen]; this screen owns repository-delivered content Providers.
 */
class SettingsTsuzukiProvidersDefaultSkinScreen : Screen() {

    @Composable
    override fun Content() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val manager = remember { context.appGraph.providerRepositoryManager }
        val providerRegistry = remember { context.appGraph.providerRegistry }
        val scriptRegistry = remember { context.appGraph.installedScriptProviderRegistry }
        val colors = defaultSkinColors()

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var repositories by remember { mutableStateOf<List<EnrolledProviderRepository>>(emptyList()) }
        var snapshots by remember { mutableStateOf<Map<String, ProviderRepositorySnapshot>>(emptyMap()) }
        var installed by remember { mutableStateOf<List<ProviderRegistration>>(emptyList()) }
        var rollbackProviderIds by remember { mutableStateOf(emptySet<String>()) }
        var refreshing by remember { mutableStateOf(false) }
        var operations by remember { mutableStateOf(emptySet<String>()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showRepositories by remember { mutableStateOf(false) }
        var showAddRepository by remember { mutableStateOf(false) }
        var repositoryToRemove by remember { mutableStateOf<EnrolledProviderRepository?>(null) }

        suspend fun reloadInstalled() {
            val state = withContext(Dispatchers.IO) {
                providerRegistry.awaitReady()
                val registrations = providerRegistry.providers()
                    .filter { it.descriptor.origin is ProviderOrigin.Repository }
                val rollbackIds = registrations
                    .asSequence()
                    .map { it.descriptor.id.value }
                    .filter(manager::canRollback)
                    .toSet()
                registrations to rollbackIds
            }
            installed = state.first
            rollbackProviderIds = state.second
        }

        suspend fun reloadRepositories(refreshRemote: Boolean) {
            repositories = withContext(Dispatchers.IO) { manager.repositories() }
            if (!refreshRemote) return

            refreshing = true
            val next = snapshots.toMutableMap()
            var lastError: String? = null
            repositories.forEach { repository ->
                val repositoryId = repository.enrollment.repositoryId
                runProviderUiCatching {
                    withContext(Dispatchers.IO) { manager.refresh(repositoryId) }
                }.onSuccess { snapshot ->
                    next[repositoryId] = snapshot
                }.onFailure { error ->
                    lastError = error.message
                }
            }
            snapshots = next.filterKeys { id -> repositories.any { it.enrollment.repositoryId == id } }
            errorMessage = lastError
            refreshing = false
            scriptRegistry.invalidate()
            reloadInstalled()
        }

        fun runProviderOperation(key: String, action: suspend () -> Unit) {
            scope.launch {
                operations += key
                errorMessage = null
                try {
                    withContext(Dispatchers.IO) { action() }
                    scriptRegistry.invalidate()
                    reloadInstalled()
                    snapshots = withContext(Dispatchers.IO) {
                        repositories.mapNotNull { repository ->
                            val id = repository.enrollment.repositoryId
                            manager.snapshot(id)?.let { id to it }
                        }.toMap()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    errorMessage = error.message
                } finally {
                    operations -= key
                }
            }
        }

        LaunchedEffect(Unit) {
            reloadInstalled()
            reloadRepositories(refreshRemote = true)
        }
        LaunchedEffect(providerRegistry) {
            providerRegistry.observeChanges().collect { reloadInstalled() }
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
                    text = stringResource(MR.strings.tsuzuki_providers_title),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DefaultSkinSpacing.PageHorizontal),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DefaultSkinChoiceChip(
                    text = stringResource(MR.strings.tsuzuki_providers_installed),
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                )
                DefaultSkinChoiceChip(
                    text = stringResource(MR.strings.tsuzuki_providers_discover),
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                )
                Box(Modifier.weight(1f))
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_providers_repositories),
                    onClick = { showRepositories = true },
                )
            }

            if (refreshing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = colors.foreground)
                }
            }

            errorMessage?.let { message ->
                DefaultSkinCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DefaultSkinSpacing.PageHorizontal, vertical = 8.dp),
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (selectedTab == 0) {
                InstalledProviderCards(
                    registrations = installed,
                    rollbackProviderIds = rollbackProviderIds,
                    operationKeys = operations,
                    onOpen = { registration ->
                        navigator.push(
                            SettingsTsuzukiProviderDetailDefaultSkinScreen(
                                registration.descriptor.id.value,
                            ),
                        )
                    },
                    onEnabledChange = { registration, enabled ->
                        scope.launch {
                            runProviderUiCatching {
                                withContext(Dispatchers.IO) {
                                    scriptRegistry.setEnabled(registration.descriptor.id, enabled)
                                }
                            }.onSuccess { reloadInstalled() }
                                .onFailure { error -> errorMessage = error.message }
                        }
                    },
                    onRollback = { providerId ->
                        runProviderOperation("rollback:$providerId") { manager.rollback(providerId) }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                DiscoverProviderCards(
                    snapshots = snapshots.values.toList(),
                    operationKeys = operations,
                    onInstall = { entry ->
                        runProviderOperation(
                            "install:${entry.repositoryId}:${entry.descriptor.providerId}",
                        ) {
                            manager.install(
                                repositoryId = entry.repositoryId,
                                providerId = entry.descriptor.providerId,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showRepositories) {
            DefaultSkinRepositoriesDialog(
                repositories = repositories,
                snapshots = snapshots,
                refreshing = refreshing,
                onDismiss = { showRepositories = false },
                onAdd = {
                    showRepositories = false
                    showAddRepository = true
                },
                onRefresh = { repositoryId ->
                    scope.launch {
                        refreshing = true
                        errorMessage = null
                        runProviderUiCatching {
                            withContext(Dispatchers.IO) { manager.refresh(repositoryId) }
                        }.onSuccess { snapshot ->
                            snapshots = snapshots + (repositoryId to snapshot)
                            scriptRegistry.invalidate()
                            reloadInstalled()
                        }.onFailure { error -> errorMessage = error.message }
                        refreshing = false
                    }
                },
                onRemove = { repository ->
                    showRepositories = false
                    repositoryToRemove = repository
                },
            )
        }

        if (showAddRepository) {
            DefaultSkinAddRepositoryDialog(
                onDismiss = { showAddRepository = false },
                onConfirm = { repository ->
                    scope.launch {
                        runProviderUiCatching {
                            withContext(Dispatchers.IO) { manager.enroll(repository) }
                        }.onSuccess {
                            repositories = withContext(Dispatchers.IO) { manager.repositories() }
                            showAddRepository = false
                            runProviderUiCatching {
                                withContext(Dispatchers.IO) {
                                    manager.refresh(repository.enrollment.repositoryId)
                                }
                            }.onSuccess { snapshot ->
                                snapshots = snapshots + (repository.enrollment.repositoryId to snapshot)
                                scriptRegistry.invalidate()
                                reloadInstalled()
                            }.onFailure { error -> errorMessage = error.message }
                        }.onFailure { error -> errorMessage = error.message }
                    }
                },
            )
        }

        repositoryToRemove?.let { repository ->
            AlertDialog(
                onDismissRequest = { repositoryToRemove = null },
                title = { Text(stringResource(MR.strings.tsuzuki_providers_repository_remove)) },
                text = { Text(stringResource(MR.strings.tsuzuki_providers_repository_remove_confirm)) },
                confirmButton = {
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.action_remove),
                        onClick = {
                            val repositoryId = repository.enrollment.repositoryId
                            scope.launch {
                                runProviderUiCatching {
                                    withContext(Dispatchers.IO) { manager.removeRepository(repositoryId) }
                                }.onSuccess {
                                    repositories = withContext(Dispatchers.IO) { manager.repositories() }
                                    snapshots = snapshots - repositoryId
                                    repositoryToRemove = null
                                }.onFailure { error -> errorMessage = error.message }
                            }
                        },
                    )
                },
                dismissButton = {
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.action_cancel),
                        onClick = { repositoryToRemove = null },
                    )
                },
                containerColor = colors.surface,
                titleContentColor = colors.foreground,
                textContentColor = colors.secondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledProviderCards(
    registrations: List<ProviderRegistration>,
    rollbackProviderIds: Set<String>,
    operationKeys: Set<String>,
    onOpen: (ProviderRegistration) -> Unit,
    onEnabledChange: (ProviderRegistration, Boolean) -> Unit,
    onRollback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            DefaultSkinSpacing.PageHorizontal,
            18.dp,
            DefaultSkinSpacing.PageHorizontal,
            32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Row),
    ) {
        if (registrations.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(MR.strings.tsuzuki_providers_no_installed),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(registrations, key = { it.descriptor.id.value }) { registration ->
            val descriptor = registration.descriptor
            val providerId = descriptor.id.value
            DefaultSkinCard(onClick = { onOpen(registration) }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = descriptor.name,
                                color = colors.foreground,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${descriptor.version.name} · ${providerLifecycleLabelDefaultSkin(
                                    registration.lifecycleStatus,
                                )}",
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = registration.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                            enabled = registration.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                                registration.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                            onCheckedChange = { onEnabledChange(registration, it) },
                        )
                    }
                    if (descriptor.capabilities.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            descriptor.capabilities.take(4).forEach { capability ->
                                DefaultSkinChoiceChip(
                                    text = capability.id,
                                    selected = false,
                                    onClick = { onOpen(registration) },
                                )
                            }
                        }
                    }
                    if (providerId in rollbackProviderIds) {
                        DefaultSkinTextAction(
                            text = stringResource(MR.strings.tsuzuki_providers_rollback),
                            onClick = {
                                if ("rollback:$providerId" !in operationKeys) onRollback(providerId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverProviderCards(
    snapshots: List<ProviderRepositorySnapshot>,
    operationKeys: Set<String>,
    onInstall: (ProviderRepositoryCatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    val entries = snapshots.flatMap { it.entries }
        .sortedWith(compareBy({ it.descriptor.providerId }, { it.repositoryId }))

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            DefaultSkinSpacing.PageHorizontal,
            18.dp,
            DefaultSkinSpacing.PageHorizontal,
            32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Row),
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(MR.strings.tsuzuki_providers_no_discover),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(entries, key = { "${it.repositoryId}:${it.descriptor.providerId}" }) { entry ->
            val operationKey = "install:${entry.repositoryId}:${entry.descriptor.providerId}"
            DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = entry.descriptor.providerId,
                        color = colors.foreground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${entry.descriptor.versionName} · ${providerRepositoryStatusLabelDefaultSkin(
                            entry.status,
                        )}",
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = entry.repositoryId,
                        color = colors.secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (
                        entry.status == ProviderRepositoryEntryStatus.AVAILABLE ||
                        entry.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
                    ) {
                        DefaultSkinPrimaryButton(
                            text = stringResource(
                                if (entry.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE) {
                                    MR.strings.tsuzuki_providers_update
                                } else {
                                    MR.strings.tsuzuki_providers_install
                                },
                            ),
                            enabled = operationKey !in operationKeys,
                            onClick = { onInstall(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSkinRepositoriesDialog(
    repositories: List<EnrolledProviderRepository>,
    snapshots: Map<String, ProviderRepositorySnapshot>,
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onRefresh: (String) -> Unit,
    onRemove: (EnrolledProviderRepository) -> Unit,
) {
    val colors = defaultSkinColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.tsuzuki_providers_repositories)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (repositories.isEmpty()) {
                    Text(
                        stringResource(MR.strings.tsuzuki_providers_no_repositories),
                        color = colors.secondary,
                    )
                }
                repositories.forEach { repository ->
                    val id = repository.enrollment.repositoryId
                    DefaultSkinCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(repository.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                repository.enrollment.indexUrl,
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${stringResource(
                                    MR.strings.tsuzuki_providers_repository_fingerprint,
                                )}: ${repository.keyFingerprintSha256}",
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            snapshots[id]?.let { snapshot ->
                                Text(
                                    "${snapshot.entries.size} Providers",
                                    color = colors.secondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DefaultSkinTextAction(
                                    text = stringResource(MR.strings.tsuzuki_providers_refresh),
                                    onClick = { if (!refreshing) onRefresh(id) },
                                )
                                DefaultSkinTextAction(
                                    text = stringResource(MR.strings.action_remove),
                                    onClick = { onRemove(repository) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DefaultSkinTextAction(
                text = stringResource(MR.strings.tsuzuki_providers_add_repository),
                onClick = onAdd,
            )
        },
        dismissButton = {
            DefaultSkinTextAction(
                text = stringResource(MR.strings.action_close),
                onClick = onDismiss,
            )
        },
        containerColor = colors.surface,
        titleContentColor = colors.foreground,
        textContentColor = colors.foreground,
    )
}

@Composable
private fun DefaultSkinAddRepositoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (EnrolledProviderRepository) -> Unit,
) {
    val colors = defaultSkinColors()
    var displayName by remember { mutableStateOf("") }
    var repositoryId by remember { mutableStateOf("") }
    var indexUrl by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var publicKeyBase64 by remember { mutableStateOf("") }
    var confirmedTrustToken by remember { mutableStateOf<String?>(null) }

    val signingKey = remember(keyId, publicKeyBase64) {
        ProviderRepositorySigningKey(
            keyId = keyId.trim(),
            publicKeyBase64 = publicKeyBase64.trim(),
        )
    }
    val fingerprint = remember(signingKey) {
        runCatching { providerRepositoryKeyFingerprint(signingKey) }.getOrNull()
    }
    val trustToken = fingerprint?.let { keyFingerprint ->
        providerRepositoryTrustConfirmationToken(
            displayName = displayName,
            repositoryId = repositoryId,
            indexUrl = indexUrl,
            keyId = keyId,
            keyFingerprint = keyFingerprint,
        )
    }
    val trustConfirmed = trustToken != null && confirmedTrustToken == trustToken
    val canConfirm = displayName.isNotBlank() &&
        repositoryId.isNotBlank() &&
        indexUrl.isNotBlank() &&
        keyId.isNotBlank() &&
        publicKeyBase64.isNotBlank() &&
        fingerprint != null &&
        trustConfirmed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.tsuzuki_providers_add_repository)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultSkinTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = stringResource(MR.strings.tsuzuki_providers_repository_name),
                )
                DefaultSkinTextField(
                    value = repositoryId,
                    onValueChange = { repositoryId = it },
                    label = stringResource(MR.strings.tsuzuki_providers_repository_id),
                )
                DefaultSkinTextField(
                    value = indexUrl,
                    onValueChange = { indexUrl = it },
                    label = stringResource(MR.strings.tsuzuki_providers_repository_index_url),
                )
                DefaultSkinTextField(
                    value = keyId,
                    onValueChange = { keyId = it },
                    label = stringResource(MR.strings.tsuzuki_providers_repository_key_id),
                )
                DefaultSkinTextField(
                    value = publicKeyBase64,
                    onValueChange = { publicKeyBase64 = it },
                    label = stringResource(MR.strings.tsuzuki_providers_repository_public_key),
                    singleLine = false,
                )
                fingerprint?.let {
                    DefaultSkinSectionLabel(
                        stringResource(MR.strings.tsuzuki_providers_repository_fingerprint),
                    )
                    Text(
                        it,
                        color = colors.foreground,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = trustConfirmed,
                        enabled = trustToken != null,
                        onCheckedChange = { checked ->
                            confirmedTrustToken = if (checked) trustToken else null
                        },
                    )
                    Text(stringResource(MR.strings.tsuzuki_providers_repository_trust_confirm))
                }
            }
        },
        confirmButton = {
            DefaultSkinTextAction(
                text = stringResource(MR.strings.action_add),
                onClick = {
                    if (!canConfirm) return@DefaultSkinTextAction
                    onConfirm(
                        EnrolledProviderRepository(
                            displayName = displayName.trim(),
                            enrollment = ProviderRepositoryEnrollment(
                                repositoryId = repositoryId.trim(),
                                indexUrl = indexUrl.trim(),
                                signingKey = signingKey,
                            ),
                        ),
                    )
                },
            )
        },
        dismissButton = {
            DefaultSkinTextAction(
                text = stringResource(MR.strings.action_cancel),
                onClick = onDismiss,
            )
        },
        containerColor = colors.surface,
        titleContentColor = colors.foreground,
        textContentColor = colors.foreground,
    )
}

@Composable
private fun providerLifecycleLabelDefaultSkin(status: ProviderLifecycleStatus): String = stringResource(
    when (status) {
        ProviderLifecycleStatus.ENABLED -> MR.strings.tsuzuki_providers_enabled
        ProviderLifecycleStatus.DISABLED -> MR.strings.tsuzuki_providers_disabled
        ProviderLifecycleStatus.BLOCKED -> MR.strings.tsuzuki_providers_blocked
        ProviderLifecycleStatus.INVALID -> MR.strings.tsuzuki_providers_invalid
    },
)

@Composable
private fun providerRepositoryStatusLabelDefaultSkin(status: ProviderRepositoryEntryStatus): String = stringResource(
    when (status) {
        ProviderRepositoryEntryStatus.AVAILABLE -> MR.strings.tsuzuki_providers_available
        ProviderRepositoryEntryStatus.INSTALLED -> MR.strings.tsuzuki_providers_installed
        ProviderRepositoryEntryStatus.UPDATE_AVAILABLE -> MR.strings.tsuzuki_providers_update_available
        ProviderRepositoryEntryStatus.INSTALLED_NEWER -> MR.strings.tsuzuki_providers_installed_newer
        ProviderRepositoryEntryStatus.REVOKED -> MR.strings.tsuzuki_providers_revoked
        ProviderRepositoryEntryStatus.INCOMPATIBLE -> MR.strings.tsuzuki_providers_incompatible
        ProviderRepositoryEntryStatus.ORIGIN_CONFLICT -> MR.strings.tsuzuki_providers_origin_conflict
    },
)
