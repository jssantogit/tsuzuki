package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

class AvailableSupabaseSyncAdaptersTest {

    @Test
    fun `opt-in v2 namespace cannot change previously persisted enum order`() {
        SyncDocumentKind.READING_PROGRESS.ordinal shouldBe 3
        SyncDocumentKind.FALLBACK_PROGRESS.ordinal shouldBe 13
        SyncDocumentKind.READING_PROGRESS_V2.ordinal shouldBe 14
        (SyncDocumentKind.READING_PROGRESS_V2 in SyncDocumentKind.supabaseDurableKinds) shouldBe false
    }

    @Test
    fun `target durable Supabase domains exclude source mappings and Drive manifest`() {
        SyncDocumentKind.supabaseDurableKinds shouldBe listOf(
            SyncDocumentKind.TITLES,
            SyncDocumentKind.LIBRARY,
            SyncDocumentKind.READING_PROGRESS,
            SyncDocumentKind.CHAPTER_UPDATE_STATE,
            SyncDocumentKind.CONTINUE_READING_STATE,
            SyncDocumentKind.COLLECTIONS,
            SyncDocumentKind.CHAPTER_OVERRIDES,
            SyncDocumentKind.INTEGRATION_SETTINGS,
            SyncDocumentKind.CONTENT_PREFERENCES,
            SyncDocumentKind.ADDON_STATE,
        )
    }
}
