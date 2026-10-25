package tachiyomi.domain.tsuzuki.chapter.inventory

/** A synthetic, Host-authorized commit is never a provider/source binding. */
sealed interface EditorialUnitCommitResult {
    data class Applied(val chapterIds: List<String>, val inserted: Int) : EditorialUnitCommitResult

    data object Refused : EditorialUnitCommitResult
}

/**
 * An explicit transactional boundary. No default implementation and no passive
 * calls from Details or metadata refresh. Host authorization must be checked
 * both against current persistent scope and again before committing.
 */
interface EditorialUnitCommitRepository {
    suspend fun commit(
        batch: InventoryEvidenceBatch,
        authorization: HostEditorialEditionAuthorization,
    ): EditorialUnitCommitResult
}
