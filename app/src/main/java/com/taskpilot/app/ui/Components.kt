package com.taskpilot.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.taskpilot.app.data.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp, iconSize: Dp = 22.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun EmojiBadge(emoji: String, size: Dp = 48.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color, 6.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- app icons

object AppIcons {
    private val cache = HashMap<String, ImageBitmap?>()
    fun load(ctx: Context, pkg: String): ImageBitmap? = synchronized(cache) {
        cache.getOrPut(pkg) {
            try {
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun label(ctx: Context, pkg: String): String? = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        null
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = if (pkg.isBlank()) null else withContext(Dispatchers.IO) { AppIcons.load(ctx, pkg) }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)))
    } else {
        IconBadge(Icons.Rounded.Android, MaterialTheme.colorScheme.onSurfaceVariant, size)
    }
}

// ---------------------------------------------------------------- formatting

fun fmtTime(h: Int, m: Int): String = LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a"))

private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun fmtDays(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days.isEmpty() -> "No days"
    days == setOf(6, 7) -> "Sat & Sun"
    else -> days.sorted().joinToString(", ") { DAY_SHORT[it - 1] }
}

fun fmtSchedule(s: Schedule): String = "${fmtDays(s.days)} · ${fmtTime(s.hour, s.minute)}"

fun fmtWhen(t: ZonedDateTime): String {
    val now = ZonedDateTime.now()
    val day = when (t.toLocalDate()) {
        now.toLocalDate() -> "Today"
        now.toLocalDate().plusDays(1) -> "Tomorrow"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return "$day, ${t.format(DateTimeFormatter.ofPattern("h:mm a"))}"
}

fun fmtIn(t: ZonedDateTime): String {
    val d = Duration.between(ZonedDateTime.now(), t)
    val mins = d.toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> "in under a minute"
        mins < 60 -> "in $mins min"
        mins < 60 * 24 -> "in ${mins / 60}h ${mins % 60}m"
        else -> "in ${mins / (60 * 24)}d ${(mins % (60 * 24)) / 60}h"
    }
}

fun fmtAgo(ms: Long): String {
    if (ms <= 0) return "never"
    val mins = (System.currentTimeMillis() - ms) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

@Composable
fun rememberToast(): (String) -> Unit {
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    return { msg -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) } }
}

@Composable
fun TwoLine(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
