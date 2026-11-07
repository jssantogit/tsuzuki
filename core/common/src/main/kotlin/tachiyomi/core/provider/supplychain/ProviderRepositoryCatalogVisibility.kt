package tachiyomi.core.provider.supplychain

/**
 * Preserve immutable legacy MangaAll adapters in the signed repository for older
 * installations, but offer only the unified MangaAll package to new users.
 *
 * This is presentation-only filtering: it never deletes installed packages,
 * modifies the verified signed index, or changes the user's trust origin.
 */
fun visibleProviderRepositoryEntries(
    entries: List<ProviderRepositoryCatalogEntry>,
): List<ProviderRepositoryCatalogEntry> {
    val availableUnifiedRepositories = entries.asSequence()
        .filter { it.descriptor.providerId == UNIFIED_MANGAALL_ID }
        .filter { it.status in DISCOVERABLE_UNIFIED_STATUSES }
        .map { it.repositoryId }
        .toSet()
    if (availableUnifiedRepositories.isEmpty()) return entries

    return entries.filterNot { entry ->
        entry.repositoryId in availableUnifiedRepositories &&
            entry.descriptor.providerId in LEGACY_MANGAALL_ADAPTER_IDS
    }
}

private const val UNIFIED_MANGAALL_ID = "app.tsuzuki.mangaall"
private val LEGACY_MANGAALL_ADAPTER_IDS = setOf(
    "app.tsuzuki.mangaall.mangadex",
    "app.tsuzuki.mangaall.mangadot",
)
private val DISCOVERABLE_UNIFIED_STATUSES = setOf(
    ProviderRepositoryEntryStatus.AVAILABLE,
    ProviderRepositoryEntryStatus.INSTALLED,
    ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
    ProviderRepositoryEntryStatus.INSTALLED_NEWER,
)
