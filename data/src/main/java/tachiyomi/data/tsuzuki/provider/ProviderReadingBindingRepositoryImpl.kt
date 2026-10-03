package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ProviderReadingBindingRepositoryImpl(
    private val database: Database,
) : ProviderReadingBindingRepository {

    override suspend fun get(
        canonicalTitleId: String,
        providerId: ProviderId,
        facetId: String?,
    ): ProviderReadingBinding? =
        database.tsuzuki_provider_bindingsQueries
            .getTsuzukiProviderBinding(
                canonicalTitleId = canonicalTitleId,
                providerId = providerId.value,
                facetId = facetId.orEmpty(),
                mapper = ::mapBinding,
            )
            .awaitAsOneOrNull()

    override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
        database.tsuzuki_provider_bindingsQueries
            .getTsuzukiProviderBindingsByTitle(
                canonicalTitleId = canonicalTitleId,
                mapper = ::mapBinding,
            )
            .awaitAsList()

    override suspend fun upsert(binding: ProviderReadingBinding) {
        database.tsuzuki_provider_bindingsQueries.upsertTsuzukiProviderBinding(
            id = binding.id,
            canonicalTitleId = binding.canonicalTitleId,
            providerId = binding.ref.providerId.value,
            facetId = binding.ref.facetId.orEmpty(),
            externalWorkId = binding.ref.externalWorkId,
            verification = binding.verification.name,
            availability = binding.availability.name,
            createdAt = binding.createdAt,
            updatedAt = binding.updatedAt,
        )
    }

    override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
        database.tsuzuki_provider_bindingsQueries.markTsuzukiProviderBindingUnavailable(
            id = bindingId,
            updatedAt = updatedAt,
        )
    }

    private fun mapBinding(
        id: String,
        canonicalTitleId: String,
        providerId: String,
        facetId: String,
        externalWorkId: String,
        verification: String,
        availability: String,
        createdAt: Long,
        updatedAt: Long,
    ) = ProviderReadingBinding(
        id = id,
        canonicalTitleId = canonicalTitleId,
        ref = ProviderBindingRef(
            providerId = ProviderId(providerId),
            facetId = facetId.ifBlank { null },
            externalWorkId = externalWorkId,
        ),
        verification = ProviderBindingVerification.valueOf(verification),
        availability = ProviderBindingAvailability.valueOf(availability),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
