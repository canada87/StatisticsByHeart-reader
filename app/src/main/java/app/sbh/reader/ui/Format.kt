package app.sbh.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sbh.reader.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Current instant refreshed every minute, so "N min fa" labels stay truthful while the screen is open. */
@Composable
fun rememberNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    return now
}

/** "12 minuti fa", "3 ore fa", "2 giorni fa". */
@Composable
fun agoText(instant: Instant, now: Instant): String {
    val d = Duration.between(instant, now).coerceAtLeast(Duration.ZERO)
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.ago_now)
        d.toHours() < 1 -> pluralStringResource(R.plurals.ago_minutes, d.toMinutes().toInt(), d.toMinutes().toInt())
        d.toDays() < 1 -> pluralStringResource(R.plurals.ago_hours, d.toHours().toInt(), d.toHours().toInt())
        else -> pluralStringResource(R.plurals.ago_days, d.toDays().toInt(), d.toDays().toInt())
    }
}

private val dateTimeFmt: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
private val dateFmt: DateTimeFormatter
    get() = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())

fun formatDateTime(i: Instant): String = dateTimeFmt.format(i.atZone(ZoneId.systemDefault()))
fun formatDate(i: Instant): String = dateFmt.format(i.atZone(ZoneId.systemDefault()))

fun formatCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

fun formatDuration(d: Duration): String {
    val s = d.seconds
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}
