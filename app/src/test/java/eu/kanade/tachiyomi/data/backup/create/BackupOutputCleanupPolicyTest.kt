package eu.kanade.tachiyomi.data.backup.create

import com.hippo.unifile.UniFile
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class BackupOutputCleanupPolicyTest {

    @Test
    fun `failed manual backup never deletes a caller supplied destination`() {
        val destination = mockk<UniFile>(relaxed = true)

        BackupOutputCleanupPolicy.onFailure(destination, createdByThisBackup = false)

        verify(exactly = 0) { destination.delete() }
    }

    @Test
    fun `validated automatic backup survives errors after retention or timestamp update`() {
        val verifiedArchive = mockk<UniFile>(relaxed = true)

        BackupOutputCleanupPolicy.onFailure(
            verifiedArchive,
            createdByThisBackup = true,
            successfullyValidated = true,
        )

        verify(exactly = 0) { verifiedArchive.delete() }
    }

    @Test
    fun `failed newly created automatic backup removes only its own output`() {
        val newFile = mockk<UniFile>(relaxed = true)

        BackupOutputCleanupPolicy.onFailure(newFile, createdByThisBackup = true)

        verify(exactly = 1) { newFile.delete() }
    }
}
