package tachiyomi.domain.tsuzuki.profile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.profile.model.countCurrentlyReading

class ProfileReadingProjectionTest {

    @Test
    fun `local reading counts`() {
        countCurrentlyReading(listOf(title("local", LibraryStatus.READING))) shouldBe 1
    }

    @Test
    fun `external only reading counts`() {
        countCurrentlyReading(listOf(title("external", external = listOf("mal" to LibraryStatus.READING)))) shouldBe 1
    }

    @Test
    fun `local and external reading for one canonical title counts once`() {
        countCurrentlyReading(
            listOf(title("shared", LibraryStatus.READING, listOf("mal" to LibraryStatus.READING))),
        ) shouldBe 1
    }

    @Test
    fun `multiple external reading memberships count once`() {
        countCurrentlyReading(
            listOf(
                title("shared", external = listOf("mal" to LibraryStatus.READING, "kitsu" to LibraryStatus.READING)),
            ),
        ) shouldBe 1
    }

    @Test
    fun `non reading statuses do not count`() {
        val statuses = listOf(
            LibraryStatus.PLANNING,
            LibraryStatus.COMPLETED,
            LibraryStatus.ON_HOLD,
            LibraryStatus.DROPPED,
        )
        countCurrentlyReading(statuses.map { title(it.name, it, listOf("mal" to it)) }) shouldBe 0
        countCurrentlyReading(emptyList()) shouldBe 0
    }

    @Test
    fun `mixed origins and conflicting statuses use combined canonical status set`() {
        countCurrentlyReading(
            listOf(
                title("one", LibraryStatus.READING, listOf("mal" to LibraryStatus.COMPLETED)),
                title("two", LibraryStatus.PLANNING, listOf("kitsu" to LibraryStatus.READING)),
                title("three", external = listOf("mal" to LibraryStatus.READING, "kitsu" to LibraryStatus.READING)),
                title("four", LibraryStatus.COMPLETED),
                title("five", external = listOf("mal" to null)),
            ),
        ) shouldBe 3
    }

    private fun title(
        id: String,
        local: LibraryStatus? = null,
        external: List<Pair<String, LibraryStatus?>> = emptyList(),
    ) = UnifiedLibraryTitle(
        title = CanonicalTitle(id, id, CanonicalIdentityState.RESOLVED, 0L, 0L),
        localEntry = local?.let { CanonicalLibraryEntry(id, it, true, 0L, 0L) },
        externalMemberships = external.map { (provider, status) ->
            ExternalLibraryMembership(
                canonicalTitleId = id,
                provider = provider,
                externalId = id,
                listKey = "$provider:list",
                status = status,
                remoteStatus = null,
                progress = null,
                score = null,
                syncedAt = 0L,
            )
        },
    )
}
