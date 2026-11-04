package tachiyomi.core.provider.supplychain

/**
 * MangaAll groups existing signed SCRIPT sources under one user-facing product.
 * IDs, network grants, bindings and reader capability remain source-isolated.
 * MangaFire keeps its existing working standalone ID and Reader flow.
 * MangaBall remains paused pending independent API authorization.
 *
 * Do not infer membership from a prefix: other signed packages must not be
 * able to impersonate a MangaAll source by choosing a similar Provider ID.
 */
object MangaAllFamilyCatalog {
    const val FAMILY_ID = "app.tsuzuki.mangaall"

    val activeSourceProviderIds: List<String> = listOf(
        "$FAMILY_ID.mangadex",
        "$FAMILY_ID.mangadot",
        "app.tsuzuki.mangafire",
    )

    // MangaBall remains a known, paused identity, not an installable source.
    val sourceProviderIds: List<String> = activeSourceProviderIds + "$FAMILY_ID.mangaball"

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
            "MangaAll install requires exactly the three currently active source identities"
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
