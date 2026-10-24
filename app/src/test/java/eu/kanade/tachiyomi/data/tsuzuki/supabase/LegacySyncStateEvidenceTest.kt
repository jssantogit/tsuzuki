package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

class LegacySyncStateEvidenceTest {

    @Test
    fun `unsent local changes alone are ambiguous and must prevent silent account binding`() = runTest {
        val evidence = LegacySyncStateEvidence(
            hasCursor = { false },
            hasPending = { false },
            hasAcceptedBase = { false },
            hasConflict = { false },
            hasOutbox = { it == SyncDocumentKind.LIBRARY },
        )

        evidence.hasUnscopedState() shouldBe true
    }

    @Test
    fun `empty clean installation can enroll its first cloud account`() = runTest {
        val evidence = LegacySyncStateEvidence(
            hasCursor = { false },
            hasPending = { false },
            hasAcceptedBase = { false },
            hasConflict = { false },
            hasOutbox = { false },
        )

        evidence.hasUnscopedState() shouldBe false
    }

    @Test
    fun `prior cursor or pending work always prevents silent rebinding`() = runTest {
        val cursor = LegacySyncStateEvidence(
            hasCursor = { it == SyncDocumentKind.LIBRARY },
            hasPending = { false },
            hasAcceptedBase = { false },
            hasConflict = { false },
            hasOutbox = { false },
        )
        val pending = LegacySyncStateEvidence(
            hasCursor = { false },
            hasPending = { it == SyncDocumentKind.READING_PROGRESS },
            hasAcceptedBase = { false },
            hasConflict = { false },
            hasOutbox = { false },
        )

        cursor.hasUnscopedState() shouldBe true
        pending.hasUnscopedState() shouldBe true
    }
}
