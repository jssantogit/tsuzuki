package tachiyomi.domain.tsuzuki.provider.torrent

import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

fun interface TorrentHttpFileMaterializer {
    suspend fun materialize(
        providerId: ProviderId,
        request: TorrentAcquisitionRequest,
        resource: TorrentReadableResource.HttpFile,
    ): ProviderCallResult<PreparedChapterContent.CanonicalDownload>
}

sealed interface ProviderTorrentReaderState {
    data class Ready(
        val route: TorrentAcquisitionRoute,
        val providerId: ProviderId,
        val content: PreparedChapterContent,
    ) : ProviderTorrentReaderState

    data class Pending(
        val route: TorrentAcquisitionRoute,
        val providerId: ProviderId,
        val jobId: String,
    ) : ProviderTorrentReaderState

    data class Failure(
        val reason: TorrentAcquisitionFailure,
    ) : ProviderTorrentReaderState
}

class PrepareProviderTorrentForReader(
    private val coordinator: ProviderTorrentAcquisitionCoordinator,
    private val httpMaterializer: TorrentHttpFileMaterializer,
) {

    suspend fun prepare(
        request: TorrentAcquisitionRequest,
        preference: TorrentAcquisitionPreference,
        directP2pAllowed: Boolean,
    ): ProviderTorrentReaderState =
        when (
            val state = coordinator.acquire(
                request = request,
                preference = preference,
                directP2pAllowed = directP2pAllowed,
            )
        ) {
            is ProviderTorrentAcquisitionState.Failure ->
                ProviderTorrentReaderState.Failure(state.reason)

            is ProviderTorrentAcquisitionState.Pending ->
                ProviderTorrentReaderState.Pending(
                    route = state.route,
                    providerId = state.providerId,
                    jobId = state.jobId,
                )

            is ProviderTorrentAcquisitionState.Ready ->
                when (val resource = state.resource) {
                    is TorrentReadableResource.LocalArchive ->
                        ProviderTorrentReaderState.Ready(
                            route = state.route,
                            providerId = state.providerId,
                            content = PreparedChapterContent.CanonicalDownload(
                                uri = resource.uri,
                                format = resource.format.name,
                            ),
                        )

                    is TorrentReadableResource.HttpFile ->
                        when (
                            val materialized = httpMaterializer.materialize(
                                providerId = state.providerId,
                                request = request,
                                resource = resource,
                            )
                        ) {
                            is ProviderCallResult.Success ->
                                ProviderTorrentReaderState.Ready(
                                    route = state.route,
                                    providerId = state.providerId,
                                    content = materialized.value,
                                )

                            is ProviderCallResult.Failure ->
                                ProviderTorrentReaderState.Failure(
                                    materialized.error.code.toAcquisitionFailure(),
                                )
                        }
                }
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
