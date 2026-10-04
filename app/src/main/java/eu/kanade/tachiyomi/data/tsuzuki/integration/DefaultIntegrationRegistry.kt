package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.integration.model.IntegrationManifest
import tachiyomi.domain.tsuzuki.integration.model.IntegrationPolicy
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import java.security.MessageDigest
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultIntegrationRegistry(
    settingsRepository: IntegrationSettingsRepository,
    private val searchProviders: Set<SearchProvider> = emptySet(),
    private val discoveryProviders: Set<DiscoveryProvider> = emptySet(),
    private val metadataProviders: Set<MetadataProvider> = emptySet(),
    private val chapterEvidenceProviders: Set<ChapterEvidenceProvider> = emptySet(),
    private val ratingsProviders: Set<RatingsProvider> = emptySet(),
    private val trackingProviders: Set<TrackingProvider> = emptySet(),
    private val userListProviders: Set<UserListProvider> = emptySet(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : IntegrationRegistry {

    private val integrationManifests: List<IntegrationManifest> = DefaultIntegrationManifests.all
    private val settings = MutableStateFlow<List<IntegrationSettings>>(emptyList())
    private val ready = CompletableDeferred<Unit>()

    init {
        settingsRepository.observeAll()
            .onEach {
                settings.value = it
                if (!ready.isCompleted) {
                    ready.complete(Unit)
                }
            }
            .launchIn(scope)
    }

    override suspend fun awaitReady() {
        ready.await()
    }

    override fun observeChanges(): Flow<Unit> = settings
        .drop(1)
        .map { Unit }

    override fun manifests(): List<IntegrationManifest> = integrationManifests

    override fun configurationFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        settings.value
            .sortedWith(compareBy({ it.integrationId.value }, IntegrationSettings::updatedAt))
            .forEach { value ->
                val token = buildString {
                    append(value.integrationId.value)
                    append('\u0000')
                    append(value.enabled)
                    append('\u0000')
                    append(value.updatedAt)
                    append('\u0000')
                    append(value.configJson)
                }.encodeToByteArray()
                digest.update(token.size.toString().encodeToByteArray())
                digest.update(':'.code.toByte())
                digest.update(token)
                digest.update(';'.code.toByte())
            }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override fun isGlobalCapabilityActive(
        integrationId: tachiyomi.domain.tsuzuki.integration.IntegrationId,
        capability: IntegrationCapability,
    ): Boolean {
        val manifest = integrationManifests.firstOrNull { it.integrationId == integrationId } ?: return false
        return manifest.allowsGlobalResolution(capability) &&
            integrationId.value in enabledIntegrationIds(capability)
    }

    override fun searchProviders(): List<SearchProvider> = allowedProviders(
        providers = searchProviders,
        capability = IntegrationCapability.SEARCH,
    )

    override fun discoveryProviders(): List<DiscoveryProvider> = allowedProviders(
        providers = discoveryProviders,
        capability = IntegrationCapability.DISCOVERY,
    )

    override fun metadataProviders(): List<MetadataProvider> =
        metadataProviders(IntegrationCapability.METADATA_BASIC)

    override fun metadataProviders(capability: IntegrationCapability): List<MetadataProvider> = allowedProviders(
        providers = metadataProviders,
        capability = capability,
    )

    override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> {
        val enabledIds = enabledIntegrationIds("chapter_evidence")
        return chapterEvidenceProviders.filter { it.producerId in enabledIds }
    }

    override fun ratingsProviders(): List<RatingsProvider> = allowedProviders(
        providers = ratingsProviders,
        capability = IntegrationCapability.RATINGS,
    )

    override fun trackingProviders(): List<TrackingProvider> {
        val enabledIds = enabledIntegrationIds(IntegrationCapability.TRACKING)
        return trackingProviders.filter { it.integrationId.value in enabledIds }
    }

    override fun userListProviders(): List<UserListProvider> = allowedProviders(
        providers = userListProviders,
        capability = IntegrationCapability.USER_LISTS,
    )

    private fun <T> allowedProviders(
        providers: Set<T>,
        capability: IntegrationCapability,
    ): List<T> where T : Any {
        val enabledIds = enabledIntegrationIds(capability)
        return providers.filter { provider ->
            val id = when (provider) {
                is SearchProvider -> provider.integrationId
                is DiscoveryProvider -> provider.integrationId
                is MetadataProvider -> provider.integrationId
                is RatingsProvider -> provider.integrationId
                is UserListProvider -> provider.integrationId
                else -> return@filter false
            }
            if (id.value !in enabledIds) return@filter false
            isGlobalCapabilityActive(id, capability)
        }
    }

    private fun enabledIntegrationIds(capability: IntegrationCapability): Set<String> = enabledIntegrationIds(
        capability.configKey,
    )

    private fun enabledIntegrationIds(capability: String): Set<String> = settings.value
        .groupBy { it.integrationId.value }
        .mapNotNull { (integrationId, values) ->
            values.maxByOrNull(IntegrationSettings::updatedAt)
                ?.takeIf(IntegrationSettings::enabled)
                ?.takeIf { it.capabilityEnabled(capability) }
                ?.let { integrationId }
        }
        .toSet()

    private fun IntegrationSettings.capabilityEnabled(capability: String): Boolean =
        IntegrationSettingsConfig.decode(configJson).capabilityEnabled(capability)
}

class BuiltinIntegrationProviderRegistry(
    private val integrationRegistry: IntegrationRegistry,
    private val settingsRepository: IntegrationSettingsRepository,
    private val providerVersion: ProviderVersion,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ProviderRegistry {

    private val settings = MutableStateFlow<List<IntegrationSettings>>(emptyList())
    private val ready = CompletableDeferred<Unit>()

    init {
        settingsRepository.observeAll()
            .onEach { values ->
                settings.value = values
                if (!ready.isCompleted) {
                    ready.complete(Unit)
                }
            }
            .launchIn(scope)
    }

    override suspend fun awaitReady() {
        integrationRegistry.awaitReady()
        ready.await()
    }

    override fun observeChanges(): Flow<Unit> = settings
        .drop(1)
        .map { Unit }

    override fun providers(): List<ProviderRegistration> {
        val latest = settings.value
            .groupBy { it.integrationId }
            .mapValues { (_, rows) -> rows.maxByOrNull(IntegrationSettings::updatedAt) }

        return integrationRegistry.manifests().map { manifest ->
            val persisted = latest[manifest.integrationId]
            val declared = manifest.capabilities.keys
                .flatMapTo(linkedSetOf()) { it.toProviderCapabilities() }
            val config = IntegrationSettingsConfig.decode(persisted?.configJson)
            val enabledCapabilities = if (persisted?.enabled == true) {
                manifest.capabilities
                    .flatMapTo(linkedSetOf()) { (capability, policy) ->
                        if (!config.capabilityEnabled(capability.configKey)) {
                            emptyList()
                        } else {
                            capability.toProviderCapabilities()
                                .filter { providerCapability ->
                                    policy.policy.allowsProviderLookup(providerCapability)
                                }
                        }
                    }
            } else {
                emptySet()
            }

            val descriptor = ProviderDescriptor(
                id = ProviderId(manifest.integrationId.value),
                name = manifest.displayName,
                version = providerVersion,
                origin = ProviderOrigin.Builtin,
                runtime = ProviderRuntimeKind.BUILTIN,
                capabilities = declared,
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = emptySet(),
            )
            ProviderRegistration(
                descriptor = descriptor,
                lifecycleStatus = if (persisted?.enabled == true) {
                    ProviderLifecycleStatus.ENABLED
                } else {
                    ProviderLifecycleStatus.DISABLED
                },
                configurationFingerprint = manifest.configurationFingerprint(persisted),
                enabledCapabilities = enabledCapabilities,
            )
        }
    }

    suspend fun setEnabled(
        providerId: ProviderId,
        enabled: Boolean,
    ) {
        val manifest = integrationRegistry.manifests()
            .firstOrNull { it.integrationId.value == providerId.value }
            ?: throw IllegalArgumentException("Unknown built-in Provider ID")
        val current = settingsRepository.get(manifest.integrationId)
        settingsRepository.upsert(
            IntegrationSettings(
                integrationId = manifest.integrationId,
                enabled = enabled,
                configJson = current?.configJson ?: "{}",
                updatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun IntegrationManifest.configurationFingerprint(
        settings: IntegrationSettings?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            integrationId.value,
            providerVersion.name,
            providerVersion.code.toString(),
            settings?.enabled?.toString().orEmpty(),
            settings?.updatedAt?.toString().orEmpty(),
            settings?.configJson.orEmpty(),
        ).forEach { token ->
            val bytes = token.encodeToByteArray()
            digest.update(bytes.size.toString().encodeToByteArray())
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(';'.code.toByte())
        }
        capabilities.entries
            .sortedBy { it.key.name }
            .forEach { (capability, policy) ->
                digest.update(capability.name.encodeToByteArray())
                digest.update('='.code.toByte())
                digest.update(policy.policy.name.encodeToByteArray())
                digest.update(';'.code.toByte())
            }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun IntegrationCapability.toProviderCapabilities(): Set<ProviderCapabilityRef> = when (this) {
        IntegrationCapability.SEARCH -> setOf(ProviderCapabilities.CatalogSearchV1)
        IntegrationCapability.DISCOVERY -> setOf(ProviderCapabilities.CatalogDiscoverV1)
        IntegrationCapability.METADATA_BASIC -> setOf(ProviderCapabilities.MetadataBasicV1)
        IntegrationCapability.METADATA_ARTWORK -> setOf(ProviderCapabilities.MetadataArtworkV1)
        IntegrationCapability.METADATA_EDITORIAL -> setOf(ProviderCapabilities.MetadataEditorialV1)
        IntegrationCapability.METADATA_STAFF -> setOf(ProviderCapabilities.MetadataStaffV1)
        IntegrationCapability.RATINGS -> setOf(ProviderCapabilities.RatingsReadV1)
        IntegrationCapability.RELATIONS -> setOf(ProviderCapabilities.RelationsReadV1)
        IntegrationCapability.CROSSWALK -> setOf(ProviderCapabilities.MetadataCrosswalkV1)
        IntegrationCapability.TRACKING -> setOf(ProviderCapabilities.AccountTrackingV1)
        IntegrationCapability.USER_LISTS -> setOf(ProviderCapabilities.AccountListsV1)
        IntegrationCapability.REMOTE_LIBRARY -> setOf(ProviderCapabilities.LibraryRemoteV1)
        IntegrationCapability.READING_CONTENT -> setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        )
        IntegrationCapability.DOWNLOADS -> setOf(ProviderCapabilities.DownloadsManageV1)
    }

    private fun IntegrationPolicy.allowsProviderLookup(
        capability: ProviderCapabilityRef,
    ): Boolean = when (this) {
        IntegrationPolicy.ALLOWED,
        IntegrationPolicy.ALLOWED_WITH_ATTRIBUTION,
        -> true
        IntegrationPolicy.USER_OWNED_DATA -> capability in USER_OWNED_SCOPED_CAPABILITIES
        IntegrationPolicy.COMMERCIAL_RESTRICTION,
        IntegrationPolicy.PERMISSION_REQUIRED,
        IntegrationPolicy.UNVERIFIED,
        -> false
    }

    private companion object {
        val USER_OWNED_SCOPED_CAPABILITIES = setOf(
            ProviderCapabilities.AccountTrackingV1,
            ProviderCapabilities.AccountListsV1,
            ProviderCapabilities.LibraryRemoteV1,
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
            ProviderCapabilities.DownloadsManageV1,
        )
    }
}
