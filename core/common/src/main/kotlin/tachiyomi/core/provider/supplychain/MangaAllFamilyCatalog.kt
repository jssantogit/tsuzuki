package tachiyomi.core.provider.supplychain

/**
 * User-facing MangaAll is one product. These are three separate Host SCRIPT
 * identities because grants, reading bindings and evidence producer IDs must
 * never be shared between the source adapters.
 *
 * Do not infer membership from a prefix: other signed packages must not be
 * able to impersonate a MangaAll source by choosing a similar Provider ID.
 */
object MangaAllFamilyCatalog {
    const val familyId = "app.tsuzuki.mangaall"

    val sourceProviderIds: List<String> = listOf(
        "$familyId.mangadex",
        "$familyId.mangaball",
        "$familyId.mangadot",
    )

    private val ids = sourceProviderIds.toSet()
    private val installable = setOf(
        ProviderRepositoryEntryStatus.AVAILABLE,
        ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
    )
    private val usable = installable + setOf(
        ProviderRepositoryEntryStatus.INSTALLED,
        ProviderRepositoryEntryStatus.INSTALLED_NEWER,
    )

    fun contains(providerId: String): Boolean = providerId in ids

    fun requireExactInstallSet(providerIds: List<String>) {
        require(providerIds.size == ids.size && providerIds.toSet() == ids) {
            "MangaAll requires exactly the three reviewed source identities"
        }
    }

    fun ordinaryEntries(entries: List<ProviderRepositoryCatalogEntry>): List<ProviderRepositoryCatalogEntry> =
        entries.filterNot { contains(it.descriptor.providerId) }

    fun discoverGroups(entries: List<ProviderRepositoryCatalogEntry>): List<MangaAllFamilyGroup> =
        entries
            .filter { contains(it.descriptor.providerId) }
            .groupBy { it.repositoryId }
            .toSortedMap()
            .map { (repositoryId, members) ->
                // Duplicate entries for the same source are ambiguous, even if
                // their signatures happen to match; never pick one implicitly.
                val distinct = members.map { it.descriptor.providerId }.toSet()
                val ordered = sourceProviderIds.mapNotNull { id ->
                    members.singleOrNull { it.descriptor.providerId == id }
                }
                val complete = distinct == ids && ordered.size == ids.size && members.size == ids.size
                MangaAllFamilyGroup(
                    repositoryId = repositoryId,
                    entries = ordered,
                    isComplete = complete,
                    canInstall = complete && ordered.all { it.status in usable } &&
                        ordered.any { it.status in installable },
                )
            }
}

data class MangaAllFamilyGroup(
    val repositoryId: String,
    val entries: List<ProviderRepositoryCatalogEntry>,
    val isComplete: Boolean,
    val canInstall: Boolean,
) {
    val familyId: String get() = MangaAllFamilyCatalog.familyId

    val installableIds: List<String>
        get() = if (!canInstall) emptyList() else entries
            .filter { it.status == ProviderRepositoryEntryStatus.AVAILABLE ||
                it.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE }
            .map { it.descriptor.providerId }
}
