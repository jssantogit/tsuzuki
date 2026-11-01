package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderSourceEditionQualification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourceAssociation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourceAssociationRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkVerification

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProviderWorkSourceAssociationRepositoryImpl(
    private val database: Database,
) : ProviderWorkSourceAssociationRepository {

    override suspend fun observe(association: ProviderWorkSourceAssociation): Boolean {
        database.tsuzuki_provider_work_sourcesQueries.observeTsuzukiProviderWorkSource(
            canonicalTitleId = association.canonicalTitleId,
            providerId = association.ref.providerId.value,
            facetId = association.ref.facetId.orEmpty(),
            externalWorkId = association.ref.externalWorkId,
            workVerification = association.workVerification.name,
            editionQualification = association.editionQualification.name,
            observedAt = association.observedAt,
        )
        // SQLite's source identity key guarantees that a rival title can never
        // silently steal this HID, even if two collectors race concurrently.
        return get(association.ref)?.canonicalTitleId == association.canonicalTitleId
    }

    override suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation? =
        database.tsuzuki_provider_work_sourcesQueries.getTsuzukiProviderWorkSource(
            providerId = ref.providerId.value,
            facetId = ref.facetId.orEmpty(),
            externalWorkId = ref.externalWorkId,
            mapper = ::mapAssociation,
        ).awaitAsOneOrNull()

    override suspend fun getByTitle(canonicalTitleId: String): List<ProviderWorkSourceAssociation> =
        database.tsuzuki_provider_work_sourcesQueries.getTsuzukiProviderWorkSourcesByTitle(
            canonicalTitleId = canonicalTitleId,
            mapper = ::mapAssociation,
        ).awaitAsList()

    private fun mapAssociation(
        canonicalTitleId: String,
        providerId: String,
        facetId: String,
        externalWorkId: String,
        workVerification: String,
        editionQualification: String,
        observedAt: Long,
    ): ProviderWorkSourceAssociation = ProviderWorkSourceAssociation(
        canonicalTitleId = canonicalTitleId,
        ref = ProviderBindingRef(
            providerId = ProviderId(providerId),
            facetId = facetId.ifBlank { null },
            externalWorkId = externalWorkId,
        ),
        workVerification = ProviderWorkVerification.valueOf(workVerification),
        editionQualification = ProviderSourceEditionQualification.valueOf(editionQualification),
        observedAt = observedAt,
    )
}
