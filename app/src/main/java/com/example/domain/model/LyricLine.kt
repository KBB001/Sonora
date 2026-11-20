package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LyricLine(
    val timeMs: Long,
    val text: String
)

@Immutable
data class LyricsResult(
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String? = null,
    val isSynced: Boolean = false
)
