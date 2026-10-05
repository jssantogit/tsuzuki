package tachiyomi.domain.tsuzuki.interactor

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

class CanonicalTitleNameMaterializationTest {

    @Test
    fun `catalog materialization preserves alternate titles without changing canonical identity`() = runTest {
        val canonicalRepository = FakeCanonicalTitleRepository()
        val names = FakeTitleNameObservationRepository()
        val materializeCanonicalTitle = MaterializeCanonicalTitle(
            repository = canonicalRepository,
            idFactory = { "title-1" },
            clock = { 1_000L },
        )
        val interactor = MaterializeCanonicalTitleFromCatalog(
            materializeCanonicalTitle = materializeCanonicalTitle,
            reportedChapterCountRepository = null,
            clock = { 2_000L },
            titleNameObservationRepository = names,
        )

        val title = interactor.execute(
            CatalogItem(
                provider = "anilist",
                providerId = "154587",
                title = "Frieren: Beyond Journey's End",
                titles = linkedMapOf(
                    "english" to "Frieren: Beyond Journey's End",
                    "romaji" to "Sousou no Frieren",
                    "romaji_duplicate" to " sousou NO FRIEREN ",
                    "native" to "葬送のフリーレン",
                    "empty" to "   ",
                ),
            ),
        )

        title.displayTitle shouldBeTitle "Frieren: Beyond Journey's End"
        title.identityState shouldBeState CanonicalIdentityState.RESOLVED
        names.values.map { Triple(it.provider, it.sourceKey, it.value) }
            .shouldContainExactly(
                Triple("anilist", "romaji", "Sousou no Frieren"),
                Triple("anilist", "native", "葬送のフリーレン"),
            )
    }

    @Test
    fun `catalog materialization preserves a new provider primary title when canonical display stays stable`() = runTest {
        val canonicalRepository = FakeCanonicalTitleRepository()
        val existing = CanonicalTitle(
            id = "title-existing",
            displayTitle = "Frieren: Beyond Journey's End",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        )
        canonicalRepository.insert(existing)
        canonicalRepository.addExternalIdentity(
            ExternalIdentity(
                canonicalTitleId = existing.id,
                provider = "kitsu",
                externalId = "k1",
                verified = true,
                createdAt = 100L,
            ),
        )
        val names = FakeTitleNameObservationRepository()
        val interactor = MaterializeCanonicalTitleFromCatalog(
            materializeCanonicalTitle = MaterializeCanonicalTitle(
                repository = canonicalRepository,
                idFactory = { "title-new" },
                clock = { 1_000L },
            ),
            reportedChapterCountRepository = null,
            clock = { 2_000L },
            titleNameObservationRepository = names,
        )

        val title = interactor.execute(
            CatalogItem(
                provider = "myanimelist",
                providerId = "52991",
                title = "Sousou no Frieren",
                titles = mapOf("english" to "Frieren: Beyond Journey's End"),
                externalIds = mapOf("kitsu" to "k1"),
            ),
        )

        title.id shouldBeTitle existing.id
        title.displayTitle shouldBeTitle "Frieren: Beyond Journey's End"
        names.values.map { Triple(it.provider, it.sourceKey, it.value) }
            .shouldContainExactly(
                Triple("myanimelist", "primary", "Sousou no Frieren"),
            )
    }

    private infix fun String.shouldBeTitle(expected: String) {
        check(this == expected) { "Expected title <$expected>, got <$this>" }
    }

    private infix fun CanonicalIdentityState.shouldBeState(expected: CanonicalIdentityState) {
        check(this == expected) { "Expected state <$expected>, got <$this>" }
    }

    private class FakeTitleNameObservationRepository : TitleNameObservationRepository {
        val values = mutableListOf<TitleNameObservation>()

        override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> =
            values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(observation: TitleNameObservation) {
            values.removeAll {
                it.canonicalTitleId == observation.canonicalTitleId &&
                    it.provider == observation.provider &&
                    it.sourceKey == observation.sourceKey &&
                    it.value == observation.value
            }
            values += observation
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        private val titles = mutableMapOf<String, CanonicalTitle>()
        private val identities = mutableListOf<ExternalIdentity>()
        private val flow = MutableStateFlow<CanonicalTitle?>(null)

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flow

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val canonicalTitleId = identities
                .firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return canonicalTitleId?.let(titles::get)
        }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            insert(title)
            addExternalIdentity(identity)
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
            flow.value = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            identities += identity
        }
    }
}
