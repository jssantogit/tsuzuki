package eu.kanade.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderRecovery
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderSourceCardRecoveryTest {
    @Test
    fun `edition explanation is not shown when a readable source is listed`() {
        shouldShowReaderSourceRecovery(ReaderRecovery.EDITION_CONFIRMATION_REQUIRED, true) shouldBe false
        shouldShowReaderSourceRecovery(ReaderRecovery.EDITION_CONFIRMATION_REQUIRED, false) shouldBe true
    }

    @Test
    fun `real source failures stay visible`() {
        shouldShowReaderSourceRecovery(ReaderRecovery.SOURCE_UNAVAILABLE, true) shouldBe true
        shouldShowReaderSourceRecovery(null, true) shouldBe false
    }
}
