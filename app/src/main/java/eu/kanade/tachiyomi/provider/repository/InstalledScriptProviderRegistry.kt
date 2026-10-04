package eu.kanade.tachiyomi.provider.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.ProviderLocalConfiguration
import tachiyomi.core.provider.supplychain.ProviderLocalConfigurationStore
import tachiyomi.core.provider.supplychain.sha256Hex
import tachiyomi.domain.tsuzuki.provider.ProviderBrowserPermission
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderSettingDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderSettingType
import tachiyomi.domain.tsuzuki.provider.ProviderStoragePermission
import tachiyomi.domain.tsuzuki.provider.ProviderVersion

class InstalledScriptProviderRegistry(
    private val artifactStore: ProviderArtifactStore,
    private val configurationStore: ProviderLocalConfigurationStore,
    private val parser: ProviderPackageParser = ProviderPackageParser(),
) : ProviderRegistry {

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun observeChanges(): Flow<Unit> = changes.asSharedFlow()

    override fun providers(): List<ProviderRegistration> =
        artifactStore.listInstalled()
            .map(::registrationFor)
            .sortedBy { registration -> registration.descriptor.name.lowercase() }

    fun setEnabled(
        providerId: ProviderId,
        enabled: Boolean,
    ) {
        val registration = registration(providerId)
            ?: throw IllegalArgumentException("Provider is not installed")
        if (
            enabled &&
            registration.lifecycleStatus in setOf(
                ProviderLifecycleStatus.BLOCKED,
                ProviderLifecycleStatus.INVALID,
            )
        ) {
            throw IllegalStateException("Blocked or invalid Provider cannot be enabled")
        }

        val current = configurationStore.get(providerId.value)
            ?: ProviderLocalConfiguration(providerId = providerId.value)
        configurationStore.save(current.copy(enabled = enabled))
        changes.tryEmit(Unit)
    }

    fun setEnabledContentLanguages(
        providerId: ProviderId,
        languages: Set<String>?,
    ) {
        val registration = registration(providerId)
            ?: throw IllegalArgumentException("Provider is not installed")
        val declared = registration.descriptor.contentLanguages
        if (languages != null && !declared.containsAll(languages)) {
            throw IllegalArgumentException("Provider content-language selection exceeds manifest authority")
        }

        val current = configurationStore.get(providerId.value)
            ?: ProviderLocalConfiguration(providerId = providerId.value)
        configurationStore.save(
            current.copy(enabledContentLanguages = languages),
        )
        changes.tryEmit(Unit)
    }

    fun configuration(providerId: ProviderId): ProviderLocalConfiguration =
        configurationStore.get(providerId.value)
            ?: ProviderLocalConfiguration(providerId = providerId.value)

    fun invalidate() {
        changes.tryEmit(Unit)
    }

    private fun registrationFor(
        stored: tachiyomi.core.provider.supplychain.StoredProviderArtifact,
    ): ProviderRegistration {
        val providerId = ProviderId(stored.providerId)
        val parsed = runCatching {
            parser.parse(artifactStore.readCurrentArtifactForInspection(stored.providerId))
        }.getOrNull()

        if (
            parsed == null ||
            parsed.manifest.id != stored.providerId ||
            parsed.manifest.version.code != stored.versionCode
        ) {
            return invalidRegistration(stored)
        }

        val manifest = parsed.manifest
        val configuration = configurationStore.get(stored.providerId)
            ?: ProviderLocalConfiguration(providerId = stored.providerId)
        val activeLanguages = configuration.enabledContentLanguages
            ?.intersect(manifest.contentLanguages)
            ?: manifest.contentLanguages

        val lifecycle = when {
            stored.revoked -> ProviderLifecycleStatus.BLOCKED
            !configuration.enabled -> ProviderLifecycleStatus.DISABLED
            else -> ProviderLifecycleStatus.ENABLED
        }

        val descriptor = ProviderDescriptor(
            id = providerId,
            name = manifest.name,
            version = ProviderVersion(
                name = manifest.version.name,
                code = manifest.version.code,
            ),
            origin = ProviderOrigin.Repository(stored.repositoryId),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = manifest.capabilities
                .map { capability ->
                    ProviderCapabilityRef(
                        id = capability.id,
                        version = capability.version,
                    )
                }
                .toSet(),
            permissions = ProviderPermissionSet(
                network = manifest.permissions.network?.let { permission ->
                    ProviderNetworkPermission(
                        origins = permission.origins,
                        localNetwork = permission.localNetwork,
                    )
                },
                browser = manifest.permissions.browser?.let { permission ->
                    ProviderBrowserPermission(permission.origins)
                },
                storage = ProviderStoragePermission(
                    enabled = manifest.permissions.storage.enabled,
                ),
                secrets = manifest.permissions.secrets,
            ),
            settings = manifest.settings.map { setting ->
                ProviderSettingDescriptor(
                    key = setting.key,
                    label = setting.label,
                    type = setting.type.toSettingType(),
                    required = setting.required,
                    options = setting.options,
                )
            },
            contentLanguages = manifest.contentLanguages,
        )

        return ProviderRegistration(
            descriptor = descriptor,
            lifecycleStatus = lifecycle,
            facets = activeLanguages
                .sorted()
                .map { language -> ProviderFacetRef(providerId, language) },
            configurationFingerprint = configurationFingerprint(
                stored = stored,
                enabled = configuration.enabled,
                activeLanguages = activeLanguages,
            ),
        )
    }

    private fun invalidRegistration(
        stored: tachiyomi.core.provider.supplychain.StoredProviderArtifact,
    ): ProviderRegistration {
        val providerId = ProviderId(stored.providerId)
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = providerId,
                name = providerId.value,
                version = ProviderVersion(
                    name = stored.versionCode.toString(),
                    code = stored.versionCode,
                ),
                origin = ProviderOrigin.Repository(stored.repositoryId),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = emptySet(),
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = emptySet(),
            ),
            lifecycleStatus = if (stored.revoked) {
                ProviderLifecycleStatus.BLOCKED
            } else {
                ProviderLifecycleStatus.INVALID
            },
            configurationFingerprint = configurationFingerprint(
                stored = stored,
                enabled = false,
                activeLanguages = emptySet(),
            ),
        )
    }

    private fun configurationFingerprint(
        stored: tachiyomi.core.provider.supplychain.StoredProviderArtifact,
        enabled: Boolean,
        activeLanguages: Set<String>,
    ): String {
        val material = buildString {
            append(stored.providerId)
            append('|')
            append(stored.repositoryId)
            append('|')
            append(stored.versionCode)
            append('|')
            append(stored.sha256)
            append('|')
            append(if (stored.revoked) '1' else '0')
            append('|')
            append(if (enabled) '1' else '0')
            append('|')
            activeLanguages.sorted().forEach { language ->
                append(language)
                append(',')
            }
        }
        return sha256Hex(material.encodeToByteArray())
    }

    private fun String.toSettingType(): ProviderSettingType = when (this) {
        "string" -> ProviderSettingType.STRING
        "boolean" -> ProviderSettingType.BOOLEAN
        "select" -> ProviderSettingType.SELECT
        "secret" -> ProviderSettingType.SECRET
        else -> error("Unsupported Provider setting type")
    }
}
