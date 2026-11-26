package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.evidence.ProviderEvidenceMetadataCodec
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelection
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.evidenceProducerId

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaAllSourceSelectionRepositoryImpl(
    private val database: Database,
) : MangaAllSourceSelectionRepository {

    override suspend fun get(canonicalTitleId: String, language: String): MangaAllSourceSelection? =
        database.tsuzuki_mangaall_source_selectionsQueries.getMangaAllSourceSelection(
            canonicalTitleId = canonicalTitleId,
            language = language,
        ) { titleId, facet, workId, selectedAt, catalogIdentityFingerprint ->
            MangaAllSourceSelection(
                canonicalTitleId = titleId,
                ref = ProviderBindingRef(
                    providerId = ProviderId("app.tsuzuki.mangaall"),
                    facetId = facet,
                    externalWorkId = workId,
                ),
                selectedAt = selectedAt,
                catalogIdentityFingerprint = catalogIdentityFingerprint,
            )
        }.awaitAsOneOrNull()

    override suspend fun getLatest(canonicalTitleId: String): MangaAllSourceSelection? =
        database.tsuzuki_mangaall_source_selectionsQueries.getLatestMangaAllSourceSelection(
            canonicalTitleId = canonicalTitleId,
        ) { titleId, facet, workId, selectedAt, catalogIdentityFingerprint ->
            MangaAllSourceSelection(
                canonicalTitleId = titleId,
                ref = ProviderBindingRef(
                    providerId = ProviderId("app.tsuzuki.mangaall"),
                    facetId = facet,
                    externalWorkId = workId,
                ),
                selectedAt = selectedAt,
                catalogIdentityFingerprint = catalogIdentityFingerprint,
            )
        }.awaitAsOneOrNull()

    override suspend fun getRelease(
        canonicalTitleId: String,
        canonicalChapterId: String,
        ref: ProviderBindingRef,
    ): String? {
        val language = ref.facetId ?: return null
        if (ref.providerId.value != "app.tsuzuki.mangaall") return null
        return database.tsuzuki_mangaall_chapter_releasesQueries.getMangaAllChapterRelease(
            canonicalTitleId = canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            language = language,
            externalWorkId = ref.externalWorkId,
        ).awaitAsOneOrNull()
    }

    override suspend fun selectRelease(
        canonicalTitleId: String,
        option: ProviderChapterReadingOption,
        at: Long,
    ): Boolean {
        val ref = option.bindingRef()
        val language = ref.facetId ?: return false
        if (ref.providerId.value != "app.tsuzuki.mangaall" || at < 0) return false
        val choice = get(canonicalTitleId, language) ?: return false
        if (choice.ref != ref || choice.catalogIdentityFingerprint != option.catalogWorkFingerprint) return false
        if (option.catalogWorkFingerprint != null) {
            val observed = database.tsuzuki_chapter_evidenceQueries.getTsuzukiChapterEvidenceByProducerExternalKey(
                producerKind = "PROVIDER",
                producerId = ref.evidenceProducerId(),
                externalChapterKey = option.providerChapterId,
            ).awaitAsOneOrNull() ?: return false
            val proof = ProviderEvidenceMetadataCodec.decode(observed.raw_metadata)?.catalogWorkFingerprint
            if (observed.canonical_title_id != canonicalTitleId ||
                observed.mapped_canonical_chapter_id != option.canonicalChapterId ||
                observed.authority_class != "PROVIDER_PROVISIONAL" ||
                proof != option.catalogWorkFingerprint
            ) {
                return false
            }
        }
        database.tsuzuki_mangaall_chapter_releasesQueries.selectMangaAllChapterRelease(
            canonicalChapterId = option.canonicalChapterId,
            language = language,
            externalWorkId = ref.externalWorkId,
            providerChapterId = option.providerChapterId,
            selectedAt = at,
            canonicalTitleId = canonicalTitleId,
            producerId = ref.evidenceProducerId(),
            catalogIdentityFingerprint = option.catalogWorkFingerprint,
        )
        val stored = get(canonicalTitleId, language) ?: return false
        return stored.ref == ref && stored.catalogIdentityFingerprint == option.catalogWorkFingerprint &&
            getRelease(canonicalTitleId, option.canonicalChapterId, ref) == option.providerChapterId
    }

    override suspend fun select(choice: MangaAllSourceSelection): Boolean {
        val language = choice.ref.facetId ?: return false
        database.tsuzuki_mangaall_source_selectionsQueries.selectMangaAllSource(
            canonicalTitleId = choice.canonicalTitleId,
            language = language,
            externalWorkId = choice.ref.externalWorkId,
            selectedAt = choice.selectedAt,
            catalogIdentityFingerprint = choice.catalogIdentityFingerprint,
        )
        // The atomic INSERT...SELECT checks that the work still belongs
        // to this canonical title. A competing reassignment cannot create a
        // cross-title preference and failed writes cannot replace a binding.
        val stored = get(choice.canonicalTitleId, language) ?: return false
        return stored.ref == choice.ref && stored.catalogIdentityFingerprint == choice.catalogIdentityFingerprint
    }
}
