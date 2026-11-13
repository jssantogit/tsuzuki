package tachiyomi.core.provider.supplychain

/**
 * New discovery is a list of installable choices, not a second copy of the
 * installed-provider registry. In particular, UPDATE_AVAILABLE belongs in the
 * Installed tab, where the user can update their existing signed origin.
 *
 * Obsolete MangaAll source adapters must never be offered as separate official
 * packages. They can still be displayed in Installed for explicit removal;
 * filtering does not delete signed artifacts, enrollment or user data.
 */
fun visibleProviderRepositoryEntries(
    entries: List<ProviderRepositoryCatalogEntry>,
    installedProviderIds: Set<String> = emptySet(),
): List<ProviderRepositoryCatalogEntry> {
    val unifiedRepositories = entries.asSequence()
        .filter { it.descriptor.providerId == UNIFIED_MANGAALL_ID }
        .filter { it.status != ProviderRepositoryEntryStatus.REVOKED }
        .map { it.repositoryId }
        .toSet()

    return entries.filterNot { entry ->
        val id = entry.descriptor.providerId
        // Registry IDs cover stale repository snapshots; version/status cover a
        // just-completed install before registry observation reaches Compose.
        id in installedProviderIds ||
            entry.installedVersionCode != null ||
            entry.status in INSTALLED_STATUSES ||
            (
                id in LEGACY_MANGAALL_ADAPTER_IDS &&
                    (
                        entry.repositoryId == OFFICIAL_REPOSITORY_ID ||
                            entry.repositoryId in unifiedRepositories
                        )
                )
    }
}

/** Same-provider signed upgrades remain available exclusively under Installed. */
fun availableInstalledProviderUpdates(
    entries: List<ProviderRepositoryCatalogEntry>,
    installedProviderIds: Set<String>,
): List<ProviderRepositoryCatalogEntry> =
    entries.filter { entry ->
        entry.descriptor.providerId in installedProviderIds &&
            entry.status == ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
    }.distinctBy { it.descriptor.providerId }

private const val UNIFIED_MANGAALL_ID = "app.tsuzuki.mangaall"
private const val OFFICIAL_REPOSITORY_ID = "app.tsuzuki.providers"
private val LEGACY_MANGAALL_ADAPTER_IDS = setOf(
    "app.tsuzuki.mangaall.mangadex",
    "app.tsuzuki.mangaall.mangadot",
)
private val INSTALLED_STATUSES = setOf(
    ProviderRepositoryEntryStatus.INSTALLED,
    ProviderRepositoryEntryStatus.UPDATE_AVAILABLE,
    ProviderRepositoryEntryStatus.INSTALLED_NEWER,
)
