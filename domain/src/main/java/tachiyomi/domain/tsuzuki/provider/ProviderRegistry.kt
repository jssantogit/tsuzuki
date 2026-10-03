package tachiyomi.domain.tsuzuki.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ProviderRegistry {
    suspend fun awaitReady() = Unit

    fun observeChanges(): Flow<Unit> = emptyFlow()

    fun providers(): List<ProviderRegistration>

    fun registration(providerId: ProviderId): ProviderRegistration? =
        providers().firstOrNull { it.descriptor.id == providerId }

    fun enabled(capability: ProviderCapabilityRef): List<ProviderDescriptor> =
        providers()
            .asSequence()
            .filter { it.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
            .map { it.descriptor }
            .filter { capability in it.capabilities }
            .toList()

    fun facets(providerId: ProviderId): List<ProviderFacetRef> =
        registration(providerId)?.facets.orEmpty()

    fun configurationFingerprint(providerId: ProviderId): String =
        registration(providerId)?.configurationFingerprint.orEmpty()
}

class DefaultProviderRegistry(
    private val registrations: () -> List<ProviderRegistration>,
    private val changes: Flow<Unit> = emptyFlow(),
) : ProviderRegistry {

    override fun observeChanges(): Flow<Unit> = changes

    override fun providers(): List<ProviderRegistration> {
        val snapshot = registrations()
        val ids = snapshot.map { it.descriptor.id }
        check(ids.distinct().size == ids.size) { "Provider registry contains duplicate Provider IDs" }
        return snapshot
    }
}
