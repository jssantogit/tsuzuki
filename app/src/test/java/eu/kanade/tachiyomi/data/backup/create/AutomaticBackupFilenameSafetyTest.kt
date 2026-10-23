package eu.kanade.tachiyomi.data.backup.create

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutomaticBackupFilenameSafetyTest {

    @Test
    fun `multiple backups created in the same minute never reuse a filename`() {
        val filenames = List(10) { BackupCreator.getFilename() }

        filenames.toSet().size shouldBe filenames.size
    }
}
