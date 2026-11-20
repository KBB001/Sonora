package com.example.core.common

import java.util.Locale

object DurationFormatter {
    fun formatSeconds(totalSeconds: Long): String {
        if (totalSeconds <= 0L) return "0:00"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun formatMillis(totalMillis: Long): String {
        return formatSeconds((totalMillis.coerceAtLeast(0L)) / 1000L)
    }
}
