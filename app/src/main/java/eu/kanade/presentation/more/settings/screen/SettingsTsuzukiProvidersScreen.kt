package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
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
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionPreference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SettingsTsuzukiProvidersScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val manager = remember { context.appGraph.providerRepositoryManager }
        val providerRegistry = remember { context.appGraph.providerRegistry }
        val builtinRegistry = remember { context.appGraph.builtinIntegrationProviderRegistry }
        val scriptRegistry = remember { context.appGraph.installedScriptProviderRegistry }

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
                val rollbackIds = registrations
                    .asSequence()
                    .filter { it.descriptor.origin is ProviderOrigin.Repository }
                    .map { registration -> registration.descriptor.id.value }
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
                    withContext(Dispatchers.IO) {
                        manager.refresh(repositoryId)
                    }
                }.onSuccess { snapshot ->
                    next[repositoryId] = snapshot
                }.onFailure { error ->
                    lastError = error.message ?: "Repository refresh failed"
                }
            }
            snapshots = next.filterKeys { id ->
                repositories.any { it.enrollment.repositoryId == id }
            }
            errorMessage = lastError
            refreshing = false
            scriptRegistry.invalidate()
            reloadInstalled()
        }

        LaunchedEffect(Unit) {
            reloadInstalled()
            reloadRepositories(refreshRemote = true)
        }
        LaunchedEffect(providerRegistry) {
            providerRegistry.observeChanges().collect {
                reloadInstalled()
            }
        }

        fun runProviderOperation(
            key: String,
            action: suspend () -> Unit,
        ) {
            scope.launch {
                operations += key
                errorMessage = null
                try {
                    withContext(Dispatchers.IO) {
                        action()
                    }
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
                    errorMessage = error.message ?: "Provider operation failed"
                } finally {
                    operations -= key
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.strings.tsuzuki_providers_title)) },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = !refreshing,
                            onClick = {
                                scope.launch {
                                    reloadRepositories(refreshRemote = true)
                                }
                            },
                        ) {
                            Text(stringResource(MR.strings.tsuzuki_providers_refresh))
                        }
                        TextButton(onClick = { showRepositories = true }) {
                            Text(stringResource(MR.strings.tsuzuki_providers_repositories))
                        }
                    },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderTabButton(
                        selected = selectedTab == 0,
                        text = stringResource(MR.strings.tsuzuki_providers_installed),
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 0 },
                    )
                    ProviderTabButton(
                        selected = selectedTab == 1,
                        text = stringResource(MR.strings.tsuzuki_providers_discover),
                        modifier = Modifier.weight(1f),
                        onClick = { selectedTab = 1 },
                    )
                }

                ListItem(
                    headlineContent = {
                        Text(stringResource(MR.strings.tsuzuki_provider_acquisition_title))
                    },
                    supportingContent = {
                        Text(stringResource(MR.strings.tsuzuki_provider_acquisition_summary))
                    },
                    modifier = Modifier.clickable {
                        navigator.push(SettingsTsuzukiTorrentAcquisitionScreen())
                    },
                )
                HorizontalDivider()

                if (refreshing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                errorMessage?.let { message ->
                    ListItem(headlineContent = { Text(message) })
                    HorizontalDivider()
                }

                if (selectedTab == 0) {
                    InstalledProvidersList(
                        registrations = installed,
                        rollbackProviderIds = rollbackProviderIds,
                        operationKeys = operations,
                        onOpen = { registration ->
                            val providerId = registration.descriptor.id.value
                            when (registration.descriptor.origin) {
                                ProviderOrigin.Builtin ->
                                    navigator.push(SettingsTsuzukiIntegrationDetailScreen(providerId))
                                is ProviderOrigin.Repository ->
                                    navigator.push(SettingsTsuzukiProviderDetailScreen(providerId))
                            }
                        },
                        onEnabledChange = { registration, enabled ->
                            scope.launch {
                                runProviderUiCatching {
                                    withContext(Dispatchers.IO) {
                                        when (registration.descriptor.origin) {
                                            ProviderOrigin.Builtin ->
                                                builtinRegistry.setEnabled(
                                                    registration.descriptor.id,
                                                    enabled,
                                                )
                                            is ProviderOrigin.Repository ->
                                                scriptRegistry.setEnabled(
                                                    registration.descriptor.id,
                                                    enabled,
                                                )
                                        }
                                    }
                                }.onSuccess {
                                    reloadInstalled()
                                }.onFailure { error ->
                                    errorMessage = error.message
                                }
                            }
                        },
                        onRollback = { providerId ->
                            runProviderOperation("rollback:$providerId") {
                                manager.rollback(providerId)
                            }
                        },
                    )
                } else {
                    DiscoverProvidersList(
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
                    )
                }
            }
        }

        if (showRepositories) {
            ProviderRepositoriesDialog(
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
                            withContext(Dispatchers.IO) {
                                manager.refresh(repositoryId)
                            }
                        }.onSuccess { snapshot ->
                            snapshots = snapshots + (repositoryId to snapshot)
                            scriptRegistry.invalidate()
                            reloadInstalled()
                        }.onFailure { error ->
                            errorMessage = error.message
                        }
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
            AddProviderRepositoryDialog(
                onDismiss = { showAddRepository = false },
                onConfirm = { repository ->
                    scope.launch {
                        runProviderUiCatching {
                            withContext(Dispatchers.IO) {
                                manager.enroll(repository)
                            }
                        }.onSuccess {
                            repositories = withContext(Dispatchers.IO) {
                                manager.repositories()
                            }
                            showAddRepository = false
                            runProviderUiCatching {
                                withContext(Dispatchers.IO) {
                                    manager.refresh(repository.enrollment.repositoryId)
                                }
                            }.onSuccess { snapshot ->
                                snapshots = snapshots +
                                    (repository.enrollment.repositoryId to snapshot)
                                scriptRegistry.invalidate()
                                reloadInstalled()
                            }.onFailure { error ->
                                errorMessage = error.message
                            }
                        }.onFailure { error ->
                            errorMessage = error.message
                        }
                    }
                },
            )
        }

        repositoryToRemove?.let { repository ->
            AlertDialog(
                onDismissRequest = { repositoryToRemove = null },
                title = { Text(stringResource(MR.strings.tsuzuki_providers_repository_remove)) },
                text = {
                    Text(stringResource(MR.strings.tsuzuki_providers_repository_remove_confirm))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val repositoryId = repository.enrollment.repositoryId
                            scope.launch {
                                runProviderUiCatching {
                                    withContext(Dispatchers.IO) {
                                        manager.removeRepository(repositoryId)
                                    }
                                }.onSuccess {
                                    repositories = withContext(Dispatchers.IO) {
                                        manager.repositories()
                                    }
                                    snapshots = snapshots - repositoryId
                                    repositoryToRemove = null
                                }.onFailure { error ->
                                    errorMessage = error.message
                                }
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.action_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { repositoryToRemove = null }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

class SettingsTsuzukiTorrentAcquisitionScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val preferences = remember { context.appGraph.providerTorrentPreferences }
        val managedFiles = remember { context.appGraph.providerManagedFileStore }
        val p2pJobs = remember { context.appGraph.providerP2pJobManager }

        var acquisitionPreference by remember {
            mutableStateOf(preferences.acquisitionPreference.get())
        }
        var directP2pAllowed by remember {
            mutableStateOf(preferences.directP2pAllowed.get())
        }
        val temporaryStorageClearedMessage =
            stringResource(MR.strings.tsuzuki_provider_temporary_storage_cleared)
        val p2pStoppedMessage =
            stringResource(MR.strings.tsuzuki_provider_p2p_stopped)
        var statusMessage by remember { mutableStateOf<String?>(null) }

        fun setPreference(value: TorrentAcquisitionPreference) {
            preferences.acquisitionPreference.set(value)
            acquisitionPreference = value
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(MR.strings.tsuzuki_provider_acquisition_title))
                    },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                )
            },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                item(key = "route_header") {
                    ProviderSectionHeader(
                        stringResource(MR.strings.tsuzuki_provider_acquisition_route),
                    )
                }

                item(key = "route_debrid_then_p2p") {
                    TorrentAcquisitionPreferenceRow(
                        selected = acquisitionPreference ==
                            TorrentAcquisitionPreference.DEBRID_THEN_P2P,
                        title = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_debrid_then_p2p,
                        ),
                        summary = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_debrid_then_p2p_summary,
                        ),
                        onClick = {
                            setPreference(TorrentAcquisitionPreference.DEBRID_THEN_P2P)
                        },
                    )
                }

                item(key = "route_debrid_only") {
                    TorrentAcquisitionPreferenceRow(
                        selected = acquisitionPreference ==
                            TorrentAcquisitionPreference.DEBRID_ONLY,
                        title = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_debrid_only,
                        ),
                        summary = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_debrid_only_summary,
                        ),
                        onClick = {
                            setPreference(TorrentAcquisitionPreference.DEBRID_ONLY)
                        },
                    )
                }

                item(key = "route_p2p_only") {
                    TorrentAcquisitionPreferenceRow(
                        selected = acquisitionPreference ==
                            TorrentAcquisitionPreference.P2P_ONLY,
                        title = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_p2p_only,
                        ),
                        summary = stringResource(
                            MR.strings.tsuzuki_provider_acquisition_p2p_only_summary,
                        ),
                        onClick = {
                            setPreference(TorrentAcquisitionPreference.P2P_ONLY)
                        },
                    )
                }

                item(key = "direct_p2p") {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(stringResource(MR.strings.tsuzuki_provider_direct_p2p))
                        },
                        supportingContent = {
                            Text(stringResource(MR.strings.tsuzuki_provider_direct_p2p_warning))
                        },
                        trailingContent = {
                            Switch(
                                checked = directP2pAllowed,
                                onCheckedChange = { enabled ->
                                    preferences.directP2pAllowed.set(enabled)
                                    directP2pAllowed = enabled
                                },
                            )
                        },
                    )
                }

                item(key = "temporary_storage") {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(stringResource(MR.strings.tsuzuki_provider_temporary_storage))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    MR.strings.tsuzuki_provider_temporary_storage_summary,
                                ),
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            p2pJobs.cancelAll()
                                            managedFiles.clearAll()
                                        }
                                        statusMessage = temporaryStorageClearedMessage
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        MR.strings.tsuzuki_provider_clear_temporary_storage,
                                    ),
                                )
                            }
                        },
                    )
                }

                item(key = "active_p2p") {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(MR.strings.tsuzuki_provider_active_p2p))
                        },
                        supportingContent = {
                            Text(stringResource(MR.strings.tsuzuki_provider_active_p2p_summary))
                        },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    p2pJobs.cancelAll()
                                    statusMessage = p2pStoppedMessage
                                },
                            ) {
                                Text(stringResource(MR.strings.tsuzuki_provider_stop_p2p))
                            }
                        },
                    )
                }

                statusMessage?.let { message ->
                    item(key = "status") {
                        HorizontalDivider()
                        ListItem(headlineContent = { Text(message) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TorrentAcquisitionPreferenceRow(
    selected: Boolean,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun InstalledProvidersList(
    registrations: List<ProviderRegistration>,
    rollbackProviderIds: Set<String>,
    operationKeys: Set<String>,
    onOpen: (ProviderRegistration) -> Unit,
    onEnabledChange: (ProviderRegistration, Boolean) -> Unit,
    onRollback: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (registrations.isEmpty()) {
            item(key = "empty") {
                ListItem(
                    headlineContent = {
                        Text(stringResource(MR.strings.tsuzuki_providers_no_installed))
                    },
                )
            }
        }

        items(
            items = registrations,
            key = { it.descriptor.id.value },
        ) { registration ->
            val descriptor = registration.descriptor
            val providerId = descriptor.id.value
            val canRollback = providerId in rollbackProviderIds
            ListItem(
                headlineContent = { Text(descriptor.name) },
                supportingContent = {
                    Text(
                        "${descriptor.version.name} · " +
                            providerLifecycleLabel(registration.lifecycleStatus),
                    )
                },
                trailingContent = if (descriptor.origin is ProviderOrigin.Repository) {
                    {
                        Switch(
                            checked = registration.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                            enabled = registration.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                                registration.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                            onCheckedChange = { enabled ->
                                onEnabledChange(registration, enabled)
                            },
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.clickable { onOpen(registration) },
            )
            if (canRollback) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = "rollback:$providerId" !in operationKeys,
                        onClick = { onRollback(providerId) },
                    ) {
                        Text(stringResource(MR.strings.tsuzuki_providers_rollback))
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DiscoverProvidersList(
    snapshots: List<ProviderRepositorySnapshot>,
    operationKeys: Set<String>,
    onInstall: (ProviderRepositoryCatalogEntry) -> Unit,
) {
    val entries = snapshots
        .flatMap { snapshot -> snapshot.entries }
        .sortedWith(compareBy({ it.descriptor.providerId }, { it.repositoryId }))

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                ListItem(
                    headlineContent = {
                        Text(stringResource(MR.strings.tsuzuki_providers_no_discover))
                    },
                )
            }
        }

        items(
            items = entries,
            key = { entry -> "${entry.repositoryId}:${entry.descriptor.providerId}" },
        ) { entry ->
            val operationKey = "install:${entry.repositoryId}:${entry.descriptor.providerId}"
            ListItem(
                headlineContent = { Text(entry.descriptor.providerId) },
                supportingContent = {
                    Text(
                        "${entry.descriptor.versionName} · ${entry.repositoryId} · " +
                            providerRepositoryStatusLabel(entry.status),
                    )
                },
                trailingContent = {
                    when (entry.status) {
                        ProviderRepositoryEntryStatus.AVAILABLE,
                        ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
                        -> Button(
                            enabled = operationKey !in operationKeys,
                            onClick = { onInstall(entry) },
                        ) {
                            Text(
                                stringResource(
                                    if (entry.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE) {
                                        MR.strings.tsuzuki_providers_update
                                    } else {
                                        MR.strings.tsuzuki_providers_install
                                    },
                                ),
                            )
                        }
                        else -> Unit
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProviderRepositoriesDialog(
    repositories: List<EnrolledProviderRepository>,
    snapshots: Map<String, ProviderRepositorySnapshot>,
    refreshing: Boolean,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onRefresh: (String) -> Unit,
    onRemove: (EnrolledProviderRepository) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.tsuzuki_providers_repositories)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (repositories.isEmpty()) {
                    Text(stringResource(MR.strings.tsuzuki_providers_no_repositories))
                }
                repositories.forEach { repository ->
                    val id = repository.enrollment.repositoryId
                    Column {
                        Text(repository.displayName)
                        Text(id)
                        Text(repository.enrollment.indexUrl)
                        Text(
                            "${stringResource(MR.strings.tsuzuki_providers_repository_fingerprint)}: " +
                                repository.keyFingerprintSha256,
                        )
                        snapshots[id]?.let { snapshot ->
                            Text("sequence ${snapshot.sequence} · ${snapshot.entries.size} Providers")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                enabled = !refreshing,
                                onClick = { onRefresh(id) },
                            ) {
                                Text(stringResource(MR.strings.tsuzuki_providers_refresh))
                            }
                            TextButton(onClick = { onRemove(repository) }) {
                                Text(stringResource(MR.strings.action_remove))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd) {
                Text(stringResource(MR.strings.tsuzuki_providers_add_repository))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_close))
            }
        },
    )
}

@Composable
private fun AddProviderRepositoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (EnrolledProviderRepository) -> Unit,
) {
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(MR.strings.tsuzuki_providers_repository_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repositoryId,
                    onValueChange = { repositoryId = it },
                    label = { Text(stringResource(MR.strings.tsuzuki_providers_repository_id)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = indexUrl,
                    onValueChange = { indexUrl = it },
                    label = {
                        Text(stringResource(MR.strings.tsuzuki_providers_repository_index_url))
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = keyId,
                    onValueChange = { keyId = it },
                    label = { Text(stringResource(MR.strings.tsuzuki_providers_repository_key_id)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = publicKeyBase64,
                    onValueChange = { publicKeyBase64 = it },
                    label = {
                        Text(stringResource(MR.strings.tsuzuki_providers_repository_public_key))
                    },
                )
                fingerprint?.let {
                    Text(
                        "${stringResource(MR.strings.tsuzuki_providers_repository_fingerprint)}: $it",
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
            TextButton(
                enabled = canConfirm,
                onClick = {
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
            ) {
                Text(stringResource(MR.strings.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun ProviderTabButton(
    selected: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !selected,
        modifier = modifier,
    ) {
        Text(text)
    }
}

@Composable
private fun ProviderSectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun providerLifecycleLabel(status: ProviderLifecycleStatus): String =
    stringResource(
        when (status) {
            ProviderLifecycleStatus.ENABLED -> MR.strings.tsuzuki_providers_enabled
            ProviderLifecycleStatus.DISABLED -> MR.strings.tsuzuki_providers_disabled
            ProviderLifecycleStatus.BLOCKED -> MR.strings.tsuzuki_providers_blocked
            ProviderLifecycleStatus.INVALID -> MR.strings.tsuzuki_providers_invalid
        },
    )

@Composable
private fun providerRepositoryStatusLabel(status: ProviderRepositoryEntryStatus): String =
    stringResource(
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

internal fun providerRepositoryTrustConfirmationToken(
    displayName: String,
    repositoryId: String,
    indexUrl: String,
    keyId: String,
    keyFingerprint: String,
): String = listOf(
    displayName.trim(),
    repositoryId.trim(),
    indexUrl.trim(),
    keyId.trim(),
    keyFingerprint.lowercase(),
).joinToString(separator = "|")

internal suspend fun <T> runProviderUiCatching(
    block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
