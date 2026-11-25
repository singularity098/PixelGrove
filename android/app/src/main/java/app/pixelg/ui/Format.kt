package app.pixelg.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// Capture times are wall-clock seconds (EXIF times usually have no zone), so they're
// formatted as UTC to show the time the camera recorded.

private val day = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
private val dayShort = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val dateTime = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", Locale.getDefault())
private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val monthShort = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

fun localDate(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate()

fun formatDay(seconds: Long): String {
    val date = localDate(seconds)
    return if (date.year == LocalDate.now().year) date.format(dayShort) else date.format(day)
}

fun formatDateTime(seconds: Long): String = Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).format(dateTime)

fun formatMonth(seconds: Long): String = localDate(seconds).format(monthShort)

/** "2024-05" → "May 2024". */
fun formatMonthKey(key: String): String =
    runCatching { LocalDate.parse("$key-01").format(monthYear) }.getOrDefault(key)

/** Real time (not wall-clock), e.g. for "last scan" and trash expiry. */
fun formatInstant(seconds: Long): String =
    Instant.ofEpochSecond(seconds).atZone(java.time.ZoneId.systemDefault()).format(dateTime)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

fun formatDuration(ms: Long?): String {
    if (ms == null) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatCount(n: Long, one: String, many: String = "${one}s"): String =
    "%,d %s".format(n, if (n == 1L) one else many)
