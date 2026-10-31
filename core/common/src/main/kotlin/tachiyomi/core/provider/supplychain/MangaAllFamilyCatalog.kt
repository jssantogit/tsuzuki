package tachiyomi.core.provider.supplychain

/**
 * User-facing MangaAll is one product. Its three reviewed SCRIPT source identities
 * remain distinct for network grants, bindings and evidence producer IDs.
 * MangaBall is paused pending independent API authorization, so the current
 * installable family consists only of MangaDex and MangaDot.
 *
 * Do not infer membership from a prefix: other signed packages must not be
 * able to impersonate a MangaAll source by choosing a similar Provider ID.
 */
object MangaAllFamilyCatalog {
    const val FAMILY_ID = "app.tsuzuki.mangaall"

    val sourceProviderIds: List<String> = listOf(
        "$FAMILY_ID.mangadex",
        "$FAMILY_ID.mangaball",
        "$FAMILY_ID.mangadot",
    )

    // Do not erase the reserved MangaBall identity or silently install it.
    // The family is currently complete with only the two authorized-for-work sources.
    val activeSourceProviderIds: List<String> = listOf(
        "$FAMILY_ID.mangadex",
        "$FAMILY_ID.mangadot",
    )

    private val ids = sourceProviderIds.toSet()
    private val activeIds = activeSourceProviderIds.toSet()
    private val installable = setOf(
        ProviderRepositoryEntryStatus.AVAILABLE,
        ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
    )
    private val usable = installable + setOf(
        ProviderRepositoryEntryStatus.INSTALLED,
        ProviderRepositoryEntryStatus.INSTALLED_NEWER,
    )

    fun contains(providerId: String): Boolean = providerId in ids

    fun isActiveInstallSet(providerIds: List<String>): Boolean =
        providerIds.size == activeIds.size && providerIds.toSet() == activeIds

    fun requireExactInstallSet(providerIds: List<String>) {
        require(isActiveInstallSet(providerIds)) {
            "MangaAll install requires exactly the two currently active source identities"
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
                val ordered = activeSourceProviderIds.mapNotNull { id ->
                    members.singleOrNull { it.descriptor.providerId == id }
                }
                // Paused source entries are recognized but excluded. Duplicate
                // active identities still fail closed via singleOrNull.
                val complete = ordered.size == activeIds.size
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
    val familyId: String get() = MangaAllFamilyCatalog.FAMILY_ID

    val installableIds: List<String>
        get() = if (!canInstall) {
            emptyList()
        } else {
            entries
                .filter {
                    it.status == ProviderRepositoryEntryStatus.AVAILABLE ||
                        it.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
                }
                .map { it.descriptor.providerId }
        }
}
