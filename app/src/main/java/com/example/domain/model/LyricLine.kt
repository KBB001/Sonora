package com.example.domain.model

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String? = null,
    val isSynced: Boolean = false
)
