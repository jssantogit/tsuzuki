package eu.kanade.tachiyomi.data.backup.create

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Filenames must be unique even if several automatic backups run in the same
 * minute. The random suffix also prevents accidentally reusing an older file
 * URI and then treating it as newly owned after a failed write.
 *
 * Keep the older minute-only pattern eligible for automatic retention.
 */
internal object AutomaticBackupFileNames {
    fun generate(
        applicationId: String,
        timestamp: Date = Date(),
        nonce: String = UUID.randomUUID().toString().replace("-", ""),
    ): String {
        require(nonce.matches(Regex("[0-9a-f]{32}"))) { "Invalid automatic backup filename nonce" }
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ENGLISH).format(timestamp)
        return "${applicationId}_${date}_${nonce}.tachibk"
    }

    fun pattern(applicationId: String): Regex = Regex(
        Regex.escape(applicationId) +
            """_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}(?:-\d{2}-\d{3}_[0-9a-f]{32})?\.tachibk""",
    )
}
