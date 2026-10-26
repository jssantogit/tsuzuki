package tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.chapter.inventory.HostEditorialEditionAuthorization
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope

/**
 * Production Host boundary remains DENY ALL until an independently vetted
 * publisher/edition authority can replace this single binding. No producer
 * claim or local user setting can turn a callback into proof of ownership.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DisabledEditorialEditionHostAuthority : HostEditorialEditionAuthorization {
    override fun approvedScope(producerId: String, canonicalTitleId: String): InventoryScope? = null
}
