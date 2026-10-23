package tachiyomi.domain.tsuzuki.integration.model

/** Returns the factual year from a year, year-month, or valid ISO calendar date. */
fun metadataStartYear(startDate: String?): String? {
    val date = startDate?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val yearText = date.take(4)
    if (yearText.length != 4 || yearText.any { it !in '0'..'9' }) return null
    val year = yearText.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
    if (date.length == 4) return yearText
    if ((date.length != 7 && date.length != 10) || date[4] != '-') return null

    val monthText = date.substring(5, 7)
    if (monthText.any { it !in '0'..'9' }) return null
    val month = monthText.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    if (date.length == 7) return yearText
    if (date[7] != '-') return null

    val dayText = date.substring(8, 10)
    if (dayText.any { it !in '0'..'9' }) return null
    val day = dayText.toIntOrNull() ?: return null
    val maxDay = when (month) {
        2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    if (day !in 1..maxDay) return null
    return yearText
}
