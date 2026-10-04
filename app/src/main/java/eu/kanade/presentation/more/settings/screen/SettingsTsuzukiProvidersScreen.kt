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
import eu.kanade.tachiyomi.provider.repository.InstalledScriptProviderRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.core.provider.supplychain.EnrolledProviderRepository
import tachiyomi.core.provider.supplychain.ProviderRepositoryCatalogEntry
import tachiyomi.core.provider.supplychain.ProviderRepositoryEnrollment
import tachiyomi.core.provider.supplychain.ProviderRepositoryEntryStatus
import tachiyomi.core.provider.supplychain.ProviderRepositoryManager
import tachiyomi.core.provider.supplychain.ProviderRepositorySigningKey
import tachiyomi.core.provider.supplychain.ProviderRepositorySnapshot
import tachiyomi.core.provider.supplychain.providerRepositoryKeyFingerprint
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SettingsTsuzukiProvidersScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val manager = remember { context.appGraph.providerRepositoryManager }
        val registry = remember { context.appGraph.installedScriptProviderRegistry }

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var repositories by remember { mutableStateOf(manager.repositories()) }
        var snapshots by remember { mutableStateOf<Map<String, ProviderRepositorySnapshot>>(emptyMap()) }
        var installed by remember { mutableStateOf<List<ProviderRegistration>>(emptyList()) }
        var refreshing by remember { mutableStateOf(false) }
        var operations by remember { mutableStateOf(emptySet<String>()) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showRepositories by remember { mutableStateOf(false) }
        var showAddRepository by remember { mutableStateOf(false) }
        var repositoryToRemove by remember { mutableStateOf<EnrolledProviderRepository?>(null) }

        suspend fun reloadInstalled() {
            installed = withContext(Dispatchers.Default) { registry.providers() }
        }

        suspend fun reloadRepositories(refreshRemote: Boolean) {
            repositories = manager.repositories()
            if (!refreshRemote) return

            refreshing = true
            val next = snapshots.toMutableMap()
            var lastError: String? = null
            repositories.forEach { repository ->
                val repositoryId = repository.enrollment.repositoryId
                runCatching {
                    manager.refresh(repositoryId)
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
            reloadInstalled()
        }

        LaunchedEffect(Unit) {
            reloadInstalled()
            reloadRepositories(refreshRemote = repositories.isNotEmpty())
        }
        LaunchedEffect(registry) {
            registry.observeChanges().collect {
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
                    action()
                    registry.invalidate()
                    reloadInstalled()
                    snapshots = repositories.mapNotNull { repository ->
                        val id = repository.enrollment.repositoryId
                        manager.snapshot(id)?.let { id to it }
                    }.toMap()
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
                        manager = manager,
                        operationKeys = operations,
                        onOpen = { providerId ->
                            navigator.push(SettingsTsuzukiProviderDetailScreen(providerId))
                        },
                        onEnabledChange = { providerId, enabled ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        registry.setEnabled(ProviderId(providerId), enabled)
                                    }
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
                onAdd = { showAddRepository = true },
                onRefresh = { repositoryId ->
                    scope.launch {
                        refreshing = true
                        errorMessage = null
                        runCatching {
                            manager.refresh(repositoryId)
                        }.onSuccess { snapshot ->
                            snapshots = snapshots + (repositoryId to snapshot)
                            registry.invalidate()
                            reloadInstalled()
                        }.onFailure { error ->
                            errorMessage = error.message
                        }
                        refreshing = false
                    }
                },
                onRemove = { repository ->
                    repositoryToRemove = repository
                },
            )
        }

        if (showAddRepository) {
            AddProviderRepositoryDialog(
                onDismiss = { showAddRepository = false },
                onConfirm = { repository ->
                    runCatching {
                        manager.enroll(repository)
                    }.onSuccess {
                        repositories = manager.repositories()
                        showAddRepository = false
                        scope.launch {
                            runCatching {
                                manager.refresh(repository.enrollment.repositoryId)
                            }.onSuccess { snapshot ->
                                snapshots = snapshots +
                                    (repository.enrollment.repositoryId to snapshot)
                            }.onFailure { error ->
                                errorMessage = error.message
                            }
                        }
                    }.onFailure { error ->
                        errorMessage = error.message
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
                            runCatching {
                                manager.removeRepository(repositoryId)
                            }.onSuccess {
                                repositories = manager.repositories()
                                snapshots = snapshots - repositoryId
                                repositoryToRemove = null
                            }.onFailure { error ->
                                errorMessage = error.message
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

class SettingsTsuzukiProviderDetailScreen(
    private val providerIdValue: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val registry = remember { context.appGraph.installedScriptProviderRegistry }
        val providerId = remember(providerIdValue) { ProviderId(providerIdValue) }
        var registration by remember { mutableStateOf(registry.registration(providerId)) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        suspend fun reload() {
            registration = withContext(Dispatchers.Default) {
                registry.registration(providerId)
            }
        }

        LaunchedEffect(registry, providerId) {
            reload()
            registry.observeChanges().collect {
                reload()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            registration?.descriptor?.name
                                ?: stringResource(MR.strings.tsuzuki_providers_title),
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) {
                            Text(stringResource(MR.strings.tsuzuki_navigation_back))
                        }
                    },
                )
            },
        ) { contentPadding ->
            val current = registration
            if (current == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(MR.strings.tsuzuki_providers_no_installed))
                }
            } else {
                val descriptor = current.descriptor
                val activeLanguages = current.facets.map { it.facetId }.toSet()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                ) {
                    errorMessage?.let { message ->
                        item(key = "error") {
                            ListItem(headlineContent = { Text(message) })
                        }
                    }

                    item(key = "status") {
                        ListItem(
                            headlineContent = { Text(descriptor.name) },
                            supportingContent = {
                                val repository = (descriptor.origin as? ProviderOrigin.Repository)
                                    ?.repositoryId
                                    .orEmpty()
                                Text(
                                    "${descriptor.version.name} · $repository · " +
                                        providerLifecycleLabel(current.lifecycleStatus),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = current.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                                    enabled = current.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                                        current.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    registry.setEnabled(providerId, enabled)
                                                }
                                            }.onFailure { error ->
                                                errorMessage = error.message
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }

                    item(key = "capabilities_header") {
                        ProviderSectionHeader(stringResource(MR.strings.tsuzuki_providers_capabilities))
                    }
                    items(
                        items = descriptor.capabilities.sortedWith(compareBy({ it.id }, { it.version })),
                        key = { capability -> "${capability.id}@${capability.version}" },
                    ) { capability ->
                        ListItem(
                            headlineContent = { Text(capability.id) },
                            supportingContent = { Text("v${capability.version}") },
                        )
                    }

                    item(key = "permissions_header") {
                        ProviderSectionHeader(stringResource(MR.strings.tsuzuki_providers_permissions))
                    }
                    descriptor.permissions.network?.let { permission ->
                        item(key = "permission_network") {
                            ListItem(
                                headlineContent = { Text("HTTP") },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(permission.origins.sorted().joinToString())
                                            if (permission.localNetwork) append(" · local network")
                                        },
                                    )
                                },
                            )
                        }
                    }
                    descriptor.permissions.browser?.let { permission ->
                        item(key = "permission_browser") {
                            ListItem(
                                headlineContent = { Text("Browser") },
                                supportingContent = {
                                    Text(permission.origins.sorted().joinToString())
                                },
                            )
                        }
                    }
                    if (descriptor.permissions.storage.enabled) {
                        item(key = "permission_storage") {
                            ListItem(headlineContent = { Text("Storage") })
                        }
                    }
                    if (descriptor.permissions.secrets.isNotEmpty()) {
                        item(key = "permission_secrets") {
                            ListItem(
                                headlineContent = { Text("Secrets") },
                                supportingContent = {
                                    Text(descriptor.permissions.secrets.sorted().joinToString())
                                },
                            )
                        }
                    }

                    if (descriptor.contentLanguages.isNotEmpty()) {
                        item(key = "languages_header") {
                            ProviderSectionHeader(stringResource(MR.strings.tsuzuki_providers_languages))
                        }
                        items(
                            items = descriptor.contentLanguages.sorted(),
                            key = { language -> "language:$language" },
                        ) { language ->
                            val selected = language in activeLanguages
                            ListItem(
                                headlineContent = { Text(language) },
                                trailingContent = {
                                    Switch(
                                        checked = selected,
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
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        registry.setEnabledContentLanguages(
                                                            providerId = providerId,
                                                            languages = selection,
                                                        )
                                                    }
                                                }.onFailure { error ->
                                                    errorMessage = error.message
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    }

                    item(key = "settings_header") {
                        ProviderSectionHeader(stringResource(MR.strings.tsuzuki_providers_settings))
                    }
                    if (descriptor.settings.isEmpty()) {
                        item(key = "settings_empty") {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(MR.strings.tsuzuki_providers_no_settings))
                                },
                            )
                        }
                    } else {
                        items(
                            items = descriptor.settings,
                            key = { setting -> setting.key },
                        ) { setting ->
                            ListItem(
                                headlineContent = { Text(setting.label) },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(setting.type.name.lowercase())
                                            if (setting.required) append(" · required")
                                            if (setting.options.isNotEmpty()) {
                                                append(" · ")
                                                append(setting.options.joinToString())
                                            }
                                        },
                                    )
                                },
                            )
                        }
                        if (
                            descriptor.settings.any { it.type == ProviderSettingType.SECRET } ||
                            descriptor.permissions.secrets.isNotEmpty()
                        ) {
                            item(key = "secret_notice") {
                                ListItem(
                                    headlineContent = { Text("Secrets") },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                MR.strings.tsuzuki_providers_secret_notice,
                                            ),
                                        )
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

@Composable
private fun InstalledProvidersList(
    registrations: List<ProviderRegistration>,
    manager: ProviderRepositoryManager,
    operationKeys: Set<String>,
    onOpen: (String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
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
            val canRollback = remember(providerId, registration.configurationFingerprint) {
                runCatching { manager.previous(providerId) }.getOrNull() != null
            }
            ListItem(
                headlineContent = { Text(descriptor.name) },
                supportingContent = {
                    Text(
                        "${descriptor.version.name} · " +
                            providerLifecycleLabel(registration.lifecycleStatus),
                    )
                },
                trailingContent = {
                    Switch(
                        checked = registration.lifecycleStatus == ProviderLifecycleStatus.ENABLED,
                        enabled = registration.lifecycleStatus != ProviderLifecycleStatus.BLOCKED &&
                            registration.lifecycleStatus != ProviderLifecycleStatus.INVALID,
                        onCheckedChange = { enabled ->
                            onEnabledChange(providerId, enabled)
                        },
                    )
                },
                modifier = Modifier.clickable { onOpen(providerId) },
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
    var trusted by remember { mutableStateOf(false) }

    val signingKey = remember(keyId, publicKeyBase64) {
        ProviderRepositorySigningKey(
            keyId = keyId.trim(),
            publicKeyBase64 = publicKeyBase64.trim(),
        )
    }
    val fingerprint = remember(signingKey) {
        runCatching { providerRepositoryKeyFingerprint(signingKey) }.getOrNull()
    }
    val canConfirm = displayName.isNotBlank() &&
        repositoryId.isNotBlank() &&
        indexUrl.isNotBlank() &&
        keyId.isNotBlank() &&
        publicKeyBase64.isNotBlank() &&
        fingerprint != null &&
        trusted

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
                        checked = trusted,
                        onCheckedChange = { trusted = it },
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

private fun providerLifecycleLabel(status: ProviderLifecycleStatus): String = when (status) {
    ProviderLifecycleStatus.ENABLED -> "Enabled"
    ProviderLifecycleStatus.DISABLED -> "Disabled"
    ProviderLifecycleStatus.BLOCKED -> "Blocked"
    ProviderLifecycleStatus.INVALID -> "Invalid"
}

private fun providerRepositoryStatusLabel(status: ProviderRepositoryEntryStatus): String = when (status) {
    ProviderRepositoryEntryStatus.AVAILABLE -> "Available"
    ProviderRepositoryEntryStatus.INSTALLED -> "Installed"
    ProviderRepositoryEntryStatus.UPDATE_AVAILABLE -> "Update available"
    ProviderRepositoryEntryStatus.INSTALLED_NEWER -> "Installed version is newer"
    ProviderRepositoryEntryStatus.REVOKED -> "Revoked"
    ProviderRepositoryEntryStatus.INCOMPATIBLE -> "Incompatible"
    ProviderRepositoryEntryStatus.ORIGIN_CONFLICT -> "Origin conflict"
}
