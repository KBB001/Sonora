package com.example.data.remote

import com.example.domain.model.LyricLine
import com.example.domain.model.LyricsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSeconds: Long
    ): Result<LyricsResult> = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanTrackQuery(title)
            val cleanArtist = cleanArtistQuery(artist)
            if (cleanTitle.isBlank()) {
                return@withContext Result.failure(NoSuchElementException("Текст песни не найден"))
            }

            // 1. Exact lookup via LRCLIB /api/get
            val getUrl = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", cleanTitle)
                .addQueryParameter("artist_name", cleanArtist)
                .apply {
                    if (durationSeconds > 0) {
                        addQueryParameter("duration", durationSeconds.toString())
                    }
                }
                .build()

            fetchJsonObject(getUrl.toString())?.let { json ->
                parseLyricsJson(json)?.let { return@withContext Result.success(it) }
            }

            // 2. Fallback search via LRCLIB /api/search
            val searchUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$cleanArtist $cleanTitle".trim())
                .build()

            fetchJsonArray(searchUrl.toString())?.let { array ->
                for (i in 0 until minOf(array.length(), 5)) {
                    val item = array.optJSONObject(i) ?: continue
                    parseLyricsJson(item)?.let { return@withContext Result.success(it) }
                }
            }

            Result.failure(NoSuchElementException("Текст песни не найден"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchJsonObject(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SonoraMusicPlayer/1.0")
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) null else JSONObject(body)
        }
    }

    private fun fetchJsonArray(url: String): JSONArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SonoraMusicPlayer/1.0")
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) null else JSONArray(body)
        }
    }

    private fun parseLyricsJson(json: JSONObject): LyricsResult? {
        val syncedRaw = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plainRaw = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }

        if (!syncedRaw.isNullOrBlank()) {
            val parsedLines = parseLrcLines(syncedRaw)
            if (parsedLines.isNotEmpty()) {
                return LyricsResult(
                    lines = parsedLines,
                    plainLyrics = plainRaw,
                    isSynced = true
                )
            }
        }
        if (!plainRaw.isNullOrBlank()) {
            val fallbackLines = plainRaw.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(timeMs = 0L, text = it) }
            return LyricsResult(
                lines = fallbackLines,
                plainLyrics = plainRaw,
                isSynced = false
            )
        }
        return null
    }

    internal fun parseLrcLines(lrc: String): List<LyricLine> {
        val timestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val parsed = mutableListOf<LyricLine>()
        lrc.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@forEach
            val matches = timestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach
            val cleanText = timestampRegex.replace(trimmed, "").trim()
            if (cleanText.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fracRaw = match.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100L
                    2 -> fracRaw.toLong() * 10L
                    else -> fracRaw.take(3).toLong()
                }
                val totalMs = minutes * 60_000L + seconds * 1_000L + millis
                parsed += LyricLine(timeMs = totalMs, text = cleanText)
            }
        }
        return parsed.sortedBy { it.timeMs }
    }

    private fun cleanTrackQuery(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""\s*\((?:official|lyrics|audio|video|hd|4k|visualizer)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[(?:official|lyrics|audio|video|hd|4k|visualizer)[^]]*]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–|]\s*(?:official|lyrics|audio|video).*""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtistQuery(rawArtist: String): String {
        return rawArtist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""VEVO$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
