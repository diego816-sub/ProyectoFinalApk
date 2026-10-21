package com.diego.pasoapaso.ui

import android.view.View
import android.content.Context
import com.diego.pasoapaso.R
import com.diego.pasoapaso.data.Walk
import java.time.format.FormatStyle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun View.applySystemInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

fun kilometers(meters: Double): String = String.format(Locale.getDefault(), "%.2f", meters / 1000)
fun elapsedText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
fun Context.dateText(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(resources.configuration.locales[0]))

// Un nombre vacío identifica el título automático; los nombres del usuario no se traducen.
fun Context.walkName(walk: Walk): String = walk.name.ifBlank {
    getString(R.string.default_walk_name, dateText(walk.startedAt))
}
