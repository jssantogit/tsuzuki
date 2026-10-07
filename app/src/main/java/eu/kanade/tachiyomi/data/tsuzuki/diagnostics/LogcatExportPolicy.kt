package eu.kanade.tachiyomi.data.tsuzuki.diagnostics

private const val PROVIDER_RUNTIME_LOG_PREFIX = "provider_runtime_log providerId="
private const val LOGCAT_SECTION_PREFIX = "--------- beginning of "

private val LOGCAT_ENTRY = Regex(
    """^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+[+-]\d{4}\s+\d+\s+\d+\s+([VDIWEF])\s+.*?:\s?(.*)$""",
)

/**
 * Builds the Logcat view embedded in exported diagnostics.
 *
 * Verbose exports preserve the existing full V+ capture. Normal exports collect from I+ so
 * provider runtime diagnostics are available, then retain only the historical E/F records plus
 * the provider-neutral structured runtime marker. This keeps normal INFO/WARN noise out of reports.
 */
fun BoundedLogcatCollector.collectForExport(verboseLogging: Boolean): LogcatCapture {
    val capture = collect(if (verboseLogging) "V" else "I")
    if (verboseLogging || capture.content.isEmpty()) return capture

    val filtered = filterNonVerboseExport(capture.content)
    return if (capture.failures.isEmpty()) {
        LogcatCapture.complete(filtered)
    } else {
        LogcatCapture.partial(filtered, capture.failures)
    }
}

private fun filterNonVerboseExport(content: String): String {
    val hadTrailingNewline = content.endsWith('\n')
    val lines = content.split('\n').let { split ->
        if (hadTrailingNewline) split.dropLast(1) else split
    }
    val kept = mutableListOf<String>()
    var keepCurrentRecord = false

    lines.forEach { line ->
        if (line.startsWith(LOGCAT_SECTION_PREFIX)) {
            kept += line
            keepCurrentRecord = false
            return@forEach
        }

        val match = LOGCAT_ENTRY.matchEntire(line)
        if (match != null) {
            val priority = match.groupValues[1].single()
            val message = match.groupValues[2]
            keepCurrentRecord = priority == 'E' ||
                priority == 'F' ||
                (priority == 'I' && message.startsWith(PROVIDER_RUNTIME_LOG_PREFIX))
            if (keepCurrentRecord) kept += line
        } else if (keepCurrentRecord) {
            // Preserve stack traces and other continuation lines for an accepted record.
            kept += line
        }
    }

    if (kept.isEmpty()) return ""
    return buildString {
        append(kept.joinToString("\n"))
        if (hadTrailingNewline) append('\n')
    }
}
