package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderProvisionalCursorCheckpoint
import tachiyomi.domain.tsuzuki.provider.reading.ProviderProvisionalCursorProgress
import tachiyomi.domain.tsuzuki.provider.reading.ProviderProvisionalCursorRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProviderProvisionalCursorRepositoryImpl(
    private val database: Database,
) : ProviderProvisionalCursorRepository {

    override suspend fun get(
        canonicalTitleId: String,
        ref: ProviderBindingRef,
        providerVersionCode: Long,
        configurationFingerprint: String,
    ): ProviderProvisionalCursorCheckpoint? =
        database.tsuzuki_provider_provisional_cursorsQueries.getTsuzukiProviderProvisionalCursor(
            canonicalTitleId = canonicalTitleId,
            providerId = ref.providerId.value,
            facetId = ref.facetId.orEmpty(),
            externalWorkId = ref.externalWorkId,
            providerVersionCode = providerVersionCode,
            configurationFingerprint = configurationFingerprint,
            mapper = { cursor, pages ->
                ProviderProvisionalCursorCheckpoint(
                    canonicalTitleId = canonicalTitleId,
                    ref = ref,
                    providerVersionCode = providerVersionCode,
                    configurationFingerprint = configurationFingerprint,
                    nextCursor = ProviderCursor(cursor),
                    completedPages = pages.toInt(),
                )
            },
        ).awaitAsOneOrNull()

    override suspend fun commit(progress: ProviderProvisionalCursorProgress) {
        val queries = database.tsuzuki_provider_provisional_cursorsQueries
        val versionCode = progress.providerVersionCode
        val next = progress.nextCursor
        if (next == null) {
            // A newly scanned single-page source has no prior checkpoint to clear.
            val expectedCursor = progress.expectedCursor?.value ?: return
            queries.finishTsuzukiProviderProvisionalCursor(
                canonicalTitleId = progress.canonicalTitleId,
                providerId = progress.ref.providerId.value,
                facetId = progress.ref.facetId.orEmpty(),
                externalWorkId = progress.ref.externalWorkId,
                providerVersionCode = versionCode,
                configurationFingerprint = progress.configurationFingerprint,
                expectedCursor = expectedCursor,
            )
        } else {
            queries.advanceTsuzukiProviderProvisionalCursor(
                canonicalTitleId = progress.canonicalTitleId,
                providerId = progress.ref.providerId.value,
                facetId = progress.ref.facetId.orEmpty(),
                externalWorkId = progress.ref.externalWorkId,
                providerVersionCode = versionCode,
                configurationFingerprint = progress.configurationFingerprint,
                expectedCursor = progress.expectedCursor?.value,
                nextCursor = next.value,
                completedPages = progress.completedPages.toLong(),
                updatedAt = progress.recordedAt,
            )
        }
    }
}
