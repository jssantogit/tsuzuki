package eu.kanade.tachiyomi.data.backup.create

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AutomaticBackupFilenameSafetyTest {

    @Test
    fun `multiple backups created in the same minute never reuse a filename`() {
        val filenames = List(10) { BackupCreator.getFilename() }

        filenames.toSet().size shouldBe filenames.size
    }
    @Test
    fun `legacy and nonce-bearing backup filenames both remain eligible for retention`() {
        val pattern = AutomaticBackupFileNames.pattern("org.example.tsuzuki")
        pattern.matches("org.example.tsuzuki_2026-10-23_13-45.tachibk") shouldBe true
        pattern.matches(
            AutomaticBackupFileNames.generate(
                "org.example.tsuzuki",
                java.util.Date(0),
                "0123456789abcdef0123456789abcdef",
            ),
        ) shouldBe true
        pattern.matches("orgXexampleXtsuzuki_2026-10-23_13-45.tachibk") shouldBe false
        pattern.matches("org.example.tsuzuki_2026-10-23_13-45_other.tachibk") shouldBe false
    }

}
