package tachiyomi.domain.tsuzuki.provider.torrent

import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderId

fun interface TorrentCandidateMetadataGateway {
    suspend fun hydrate(
        providerId: ProviderId,
        candidate: TorrentCandidate,
    ): ProviderCallResult<TorrentCandidate>

    companion object {
        val Passthrough = TorrentCandidateMetadataGateway { _, candidate ->
            ProviderCallResult.Success(candidate)
        }
    }
}
