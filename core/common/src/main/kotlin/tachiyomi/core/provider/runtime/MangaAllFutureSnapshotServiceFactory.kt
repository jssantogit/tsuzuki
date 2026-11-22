package tachiyomi.core.provider.runtime

/**
 * NONSHIPPING. An Android Host may create this typed future-only snapshot
 * service only after independently validating its installed Provider package,
 * code11 manifest permission and exact source scope. Merely constructing the
 * object grants nothing to QuickJs; Binder and module allowlists are separate.
 *
 * Keep the implementation and storage write path internal to core/common.
 */
object MangaAllFutureSnapshotServiceFactory {
    fun create(
        owner: ProviderResourceOwner,
        http: ProviderHttpHostService,
        resources: ProviderResourceStore,
        storage: FileProviderStorageHostService,
    ): ProviderMangaAllChapterSnapshotHostService =
        MangaAllFutureChapterSnapshotHostBridge(
            MangaAllFutureHostChapterSnapshot(
                owner = owner,
                http = http,
                resources = resources,
                storage = storage,
            ),
        )
}
