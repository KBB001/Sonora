package com.example.core.common

object SemverComparator {

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remote = parse(remoteTag) ?: return false
        val current = parse(currentVersion) ?: return false

        val maxParts = maxOf(remote.numbers.size, current.numbers.size)
        for (i in 0 until maxParts) {
            val r = remote.numbers.getOrElse(i) { 0 }
            val c = current.numbers.getOrElse(i) { 0 }
            if (r != c) return r > c
        }

        // Same core numbers: stable release (empty prerelease) > prerelease tag
        return when {
            remote.preRelease == null && current.preRelease != null -> true
            remote.preRelease != null && current.preRelease == null -> false
            remote.preRelease != null && current.preRelease != null ->
                remote.preRelease > current.preRelease
            else -> false
        }
    }

    fun normalizeDisplayVersion(raw: String): String {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        return if (cleaned.isBlank()) "0.0.0" else "v$cleaned"
    }

    private data class ParsedSemver(
        val numbers: List<Int>,
        val preRelease: String?
    )

    private fun parse(raw: String): ParsedSemver? {
        val withoutPrefix = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
        if (withoutPrefix.isBlank()) return null

        val corePart = withoutPrefix.substringBefore("-")
        val prePart = withoutPrefix.substringAfter("-", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }

        val numbers = corePart.split(".")
            .mapNotNull { segment ->
                val digits = segment.takeWhile { it.isDigit() }
                digits.toIntOrNull()
            }
        if (numbers.isEmpty()) return null

        return ParsedSemver(numbers = numbers, preRelease = prePart)
    }
}
