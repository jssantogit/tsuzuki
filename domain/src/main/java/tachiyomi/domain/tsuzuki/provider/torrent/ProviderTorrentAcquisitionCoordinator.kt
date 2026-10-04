package tachiyomi.domain.tsuzuki.provider.torrent

import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry

sealed interface ProviderTorrentAcquisitionState {
    data class Ready(
        val route: TorrentAcquisitionRoute,
        val providerId: ProviderId,
        val resource: TorrentReadableResource,
    ) : ProviderTorrentAcquisitionState

    data class Pending(
        val route: TorrentAcquisitionRoute,
        val providerId: ProviderId,
        val jobId: String,
    ) : ProviderTorrentAcquisitionState {
        init {
            require(jobId.isNotBlank()) { "Provider acquisition job ID must not be blank" }
        }
    }

    data class Failure(
        val reason: TorrentAcquisitionFailure,
    ) : ProviderTorrentAcquisitionState
}

class ProviderTorrentAcquisitionCoordinator(
    private val registry: ProviderRegistry,
    private val debrid: DebridResolveGateway,
    private val p2p: P2pAcquireGateway,
) {

    suspend fun acquire(
        request: TorrentAcquisitionRequest,
        preference: TorrentAcquisitionPreference,
        directP2pAllowed: Boolean,
    ): ProviderTorrentAcquisitionState {
        registry.awaitReady()

        val debridProviders = registry.enabled(ProviderCapabilities.DebridResolveV1)
            .map { it.id }
            .sortedBy(ProviderId::value)
        val p2pProviders = registry.enabled(ProviderCapabilities.AcquisitionP2pV1)
            .map { it.id }
            .sortedBy(ProviderId::value)

        val decision = TorrentAcquisitionPolicy.resolve(
            preference = preference,
            hasUsableDebrid = debridProviders.isNotEmpty(),
            directP2pAllowed = directP2pAllowed,
        )

        val routes = when (decision) {
            is TorrentAcquisitionDecision.Routes -> decision.ordered
            TorrentAcquisitionDecision.DirectP2pConsentRequired ->
                return ProviderTorrentAcquisitionState.Failure(
                    TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
                )
            TorrentAcquisitionDecision.Unavailable ->
                return ProviderTorrentAcquisitionState.Failure(
                    TorrentAcquisitionFailure.UNAVAILABLE,
                )
        }

        var lastFailure = TorrentAcquisitionFailure.UNAVAILABLE

        for (route in routes) {
            when (route) {
                TorrentAcquisitionRoute.DEBRID -> {
                    for (providerId in debridProviders) {
                        when (val result = debrid.resolve(providerId, request)) {
                            is ProviderCallResult.Success -> when (val state = result.value) {
                                is DebridResolveState.Ready ->
                                    return ProviderTorrentAcquisitionState.Ready(
                                        route = route,
                                        providerId = providerId,
                                        resource = state.resource,
                                    )
                                is DebridResolveState.Pending ->
                                    return ProviderTorrentAcquisitionState.Pending(
                                        route = route,
                                        providerId = providerId,
                                        jobId = state.jobId,
                                    )
                            }
                            is ProviderCallResult.Failure -> {
                                lastFailure = result.error.code.toAcquisitionFailure()
                            }
                        }
                    }
                }

                TorrentAcquisitionRoute.DIRECT_P2P -> {
                    if (!directP2pAllowed) {
                        return ProviderTorrentAcquisitionState.Failure(
                            TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
                        )
                    }
                    for (providerId in p2pProviders) {
                        when (val result = p2p.acquire(providerId, request)) {
                            is ProviderCallResult.Success -> when (val state = result.value) {
                                is P2pAcquireState.Ready ->
                                    return ProviderTorrentAcquisitionState.Ready(
                                        route = route,
                                        providerId = providerId,
                                        resource = state.resource,
                                    )
                                is P2pAcquireState.Pending ->
                                    return ProviderTorrentAcquisitionState.Pending(
                                        route = route,
                                        providerId = providerId,
                                        jobId = state.jobId,
                                    )
                            }
                            is ProviderCallResult.Failure -> {
                                lastFailure = result.error.code.toAcquisitionFailure()
                            }
                        }
                    }
                }
            }
        }

        return ProviderTorrentAcquisitionState.Failure(lastFailure)
    }

    private fun ProviderErrorCode.toAcquisitionFailure(): TorrentAcquisitionFailure = when (this) {
        ProviderErrorCode.AUTH_REQUIRED -> TorrentAcquisitionFailure.AUTH_REQUIRED
        ProviderErrorCode.PERMISSION_DENIED -> TorrentAcquisitionFailure.PERMISSION_DENIED
        ProviderErrorCode.UNAVAILABLE -> TorrentAcquisitionFailure.UNAVAILABLE
        ProviderErrorCode.TIMEOUT,
        ProviderErrorCode.RUNTIME_DIED,
        ProviderErrorCode.NETWORK_POLICY,
        ProviderErrorCode.NETWORK_ERROR,
        ->
            TorrentAcquisitionFailure.NETWORK_ERROR
        ProviderErrorCode.HOST_API_UNSUPPORTED,
        ProviderErrorCode.SCRIPT_ERROR,
        ProviderErrorCode.BROWSER_ERROR,
        ProviderErrorCode.RESOURCE_LIMIT,
        ProviderErrorCode.MALFORMED_RESULT,
        ProviderErrorCode.ACQUISITION_FAILED,
        ->
            TorrentAcquisitionFailure.ACQUISITION_FAILED
    }
}
