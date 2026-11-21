package com.example.data.remote

import com.example.domain.model.AudioQuality
import com.example.domain.model.Track
import com.example.domain.model.YouTubeAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class YouTubeUserPlaylist(
    val playlistId: String,
    val title: String,
    val thumbnailUrl: String = "",
    val trackCount: Int = 0
)

data class YouTubeProfileInfo(
    val id: String,
    val name: String,
    val handle: String,
    val email: String,
    val avatarUrl: String
)

@Singleton
class YouTubeMusicAccountApi @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val apiHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchAccountInfo(
        cookieHeader: String,
        visitorData: String = "",
        dataSyncId: String = "",
        authUserIndex: Int = 0
    ): Result<YouTubeProfileInfo> = withContext(Dispatchers.IO) {
        if (!hasAuthCookies(cookieHeader)) {
            return@withContext Result.failure(
                IllegalArgumentException("В cookie отсутствует SAPISID или SID. Войдите в аккаунт YouTube.")
            )
        }
        runCatching {
            val responseJson = runCatching {
                executeInnerTubePost(
                    endpoint = "account/account_menu",
                    cookieHeader = cookieHeader,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    authUserIndex = authUserIndex,
                    payload = JSONObject().put("deviceTheme", "DEVICE_THEME_SELECTED"),
                    useWebClient = false
                )
            }.getOrElse { primaryError ->
                // Fallback to www.youtube.com WEB client if music.youtube.com rejects initial cookies/context
                runCatching {
                    executeInnerTubePost(
                        endpoint = "account/account_menu",
                        cookieHeader = cookieHeader,
                        visitorData = visitorData,
                        dataSyncId = "",
                        authUserIndex = authUserIndex,
                        payload = JSONObject(),
                        useWebClient = true
                    )
                }.getOrElse { throw primaryError }
            }
            parseAccountProfile(responseJson)
                ?: throw IOException("Не удалось получить профиль YouTube. Проверьте авторизацию.")
        }
    }

    suspend fun fetchLikedTracks(account: YouTubeAccount): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val primary = runCatching {
                    browseTrackCollection(account, browseId = "FEmusic_liked_videos")
                }.getOrNull()
                if (!primary.isNullOrEmpty()) return@runCatching primary

                val ytMusicLl = runCatching {
                    browseTrackCollection(account, browseId = "VLLM")
                }.getOrNull()
                if (!ytMusicLl.isNullOrEmpty()) return@runCatching ytMusicLl

                val webLl = runCatching {
                    browseTrackCollection(account, browseId = "VLLM", useWebClient = true)
                }
                when {
                    primary != null -> primary
                    ytMusicLl != null -> ytMusicLl
                    else -> webLl.getOrThrow()
                }
            }
        }

    suspend fun fetchHistoryTracks(account: YouTubeAccount): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            runCatching {
                browseTrackCollection(account, browseId = "FEmusic_history", maxPages = 2)
            }
        }

    suspend fun fetchUserPlaylists(account: YouTubeAccount): Result<List<YouTubeUserPlaylist>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val primary = runCatching {
                    executeInnerTubePost(
                        endpoint = "browse",
                        cookieHeader = account.cookieHeader,
                        visitorData = "",
                        dataSyncId = account.dataSyncId,
                        authUserIndex = account.authUserIndex,
                        payload = JSONObject().put("browseId", "FEmusic_liked_playlists")
                    )
                }.map { parseUserPlaylists(it) }.getOrNull()
                if (!primary.isNullOrEmpty()) return@runCatching primary

                val fallbackJson = executeInnerTubePost(
                    endpoint = "browse",
                    cookieHeader = account.cookieHeader,
                    visitorData = "",
                    dataSyncId = account.dataSyncId,
                    authUserIndex = account.authUserIndex,
                    payload = JSONObject().put("browseId", "FEmusic_library_landing")
                )
                val fallbackPlaylists = parseUserPlaylists(fallbackJson)
                if (fallbackPlaylists.isNotEmpty() || primary == null) {
                    fallbackPlaylists
                } else {
                    primary
                }
            }
        }

    suspend fun fetchPlaylistTracks(account: YouTubeAccount, playlistId: String): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val cleanId = playlistId.removePrefix("VL").trim()
            if (cleanId.isBlank()) return@withContext Result.success(emptyList())
            runCatching {
                val remix = runCatching {
                    browseTrackCollection(account, browseId = "VL$cleanId", maxPages = MAX_PLAYLIST_PAGES)
                }.getOrNull()
                if (!remix.isNullOrEmpty()) return@runCatching remix
                browseTrackCollection(
                    account,
                    browseId = "VL$cleanId",
                    maxPages = MAX_PLAYLIST_PAGES,
                    useWebClient = true
                )
            }
        }

    suspend fun setLikeStatus(
        account: YouTubeAccount,
        videoId: String,
        liked: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return@withContext Result.success(Unit)
        runCatching {
            val endpoint = if (liked) "like/like" else "like/removelike"
            executeInnerTubePost(
                endpoint = endpoint,
                cookieHeader = account.cookieHeader,
                visitorData = account.visitorData,
                dataSyncId = account.dataSyncId,
                authUserIndex = account.authUserIndex,
                payload = JSONObject().put("target", JSONObject().put("videoId", cleanId))
            )
            Unit
        }
    }

    suspend fun createPlaylist(
        account: YouTubeAccount,
        title: String,
        description: String = "",
        videoIds: List<String> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim().ifBlank { "Sonora Playlist" }
        runCatching {
            val payload = JSONObject().apply {
                put("title", cleanTitle)
                put("description", description.trim())
                put("privacyStatus", "PRIVATE")
                if (videoIds.isNotEmpty()) {
                    put("videoIds", JSONArray(videoIds.distinct().take(MAX_CREATE_BATCH)))
                }
            }
            val json = executeInnerTubePost(
                endpoint = "playlist/create",
                cookieHeader = account.cookieHeader,
                visitorData = account.visitorData,
                dataSyncId = account.dataSyncId,
                authUserIndex = account.authUserIndex,
                payload = payload
            )
            val createdId = json.optString("playlistId").trim()
            if (createdId.isBlank()) {
                throw IOException("YouTube не вернул ID созданного плейлиста")
            }
            // Add remaining tracks in chunks if playlist had more than MAX_CREATE_BATCH tracks
            val remaining = videoIds.distinct().drop(MAX_CREATE_BATCH)
            if (remaining.isNotEmpty()) {
                addTracksToPlaylistInternal(account, createdId, remaining)
            }
            createdId
        }
    }

    suspend fun addTracksToPlaylist(
        account: YouTubeAccount,
        playlistId: String,
        videoIds: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL").trim()
        val distinctIds = videoIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanPlaylistId.isBlank() || distinctIds.isEmpty()) {
            return@withContext Result.success(Unit)
        }
        runCatching {
            addTracksToPlaylistInternal(account, cleanPlaylistId, distinctIds)
        }
    }

    suspend fun removeTrackFromPlaylist(
        account: YouTubeAccount,
        playlistId: String,
        videoId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL").trim()
        if (cleanPlaylistId.isBlank() || videoId.isBlank()) return@withContext Result.success(Unit)
        runCatching {
            val payload = JSONObject().apply {
                put("playlistId", cleanPlaylistId)
                put(
                    "actions",
                    JSONArray().put(
                        JSONObject()
                            .put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                            .put("removedVideoId", videoId.trim())
                    )
                )
            }
            executeInnerTubePost(
                endpoint = "browse/edit_playlist",
                cookieHeader = account.cookieHeader,
                visitorData = account.visitorData,
                dataSyncId = account.dataSyncId,
                authUserIndex = account.authUserIndex,
                payload = payload
            )
            Unit
        }
    }

    suspend fun resolveAuthenticatedStreamUrl(
        account: YouTubeAccount,
        videoId: String,
        audioQuality: AudioQuality
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val json = executeInnerTubePost(
                endpoint = "player",
                cookieHeader = account.cookieHeader,
                visitorData = account.visitorData,
                dataSyncId = account.dataSyncId,
                authUserIndex = account.authUserIndex,
                payload = payload,
                useWebClient = true
            )
            val status = json.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") return@runCatching null
            val adaptive = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
                ?: return@runCatching null
            pickBestAudioUrl(adaptive, audioQuality)
        }.getOrNull()
    }

    private fun addTracksToPlaylistInternal(
        account: YouTubeAccount,
        playlistId: String,
        videoIds: List<String>
    ) {
        videoIds.chunked(MAX_EDIT_BATCH).forEach { chunk ->
            val actions = JSONArray()
            chunk.forEach { vid ->
                actions.put(
                    JSONObject()
                        .put("action", "ACTION_ADD_VIDEO")
                        .put("addedVideoId", vid)
                )
            }
            val payload = JSONObject().apply {
                put("playlistId", playlistId)
                put("actions", actions)
            }
            executeInnerTubePost(
                endpoint = "browse/edit_playlist",
                cookieHeader = account.cookieHeader,
                visitorData = account.visitorData,
                dataSyncId = account.dataSyncId,
                authUserIndex = account.authUserIndex,
                payload = payload
            )
        }
    }

    private fun browseTrackCollection(
        account: YouTubeAccount,
        browseId: String,
        maxPages: Int = MAX_LIKED_PAGES,
        useWebClient: Boolean = false
    ): List<Track> {
        val collected = LinkedHashMap<String, Track>()
        var json = executeInnerTubePost(
            endpoint = "browse",
            cookieHeader = account.cookieHeader,
            visitorData = "",
            dataSyncId = account.dataSyncId,
            authUserIndex = account.authUserIndex,
            payload = JSONObject().put("browseId", browseId),
            useWebClient = useWebClient
        )
        extractTracksRecursive(json).forEach { track ->
            collected.putIfAbsent(track.id, track)
        }

        var nextContinuation = extractNextContinuation(json)
        var page = 0
        while (!nextContinuation.isNullOrBlank() && page < maxPages) {
            val nextJson = runCatching {
                executeInnerTubePost(
                    endpoint = "browse",
                    cookieHeader = account.cookieHeader,
                    visitorData = "",
                    dataSyncId = account.dataSyncId,
                    authUserIndex = account.authUserIndex,
                    payload = JSONObject().put("continuation", nextContinuation),
                    useWebClient = useWebClient
                )
            }.getOrNull() ?: break
            val newTracks = extractTracksRecursive(nextJson)
            if (newTracks.isEmpty()) break
            newTracks.forEach { track ->
                collected.putIfAbsent(track.id, track)
            }
            val upcoming = extractNextContinuation(nextJson)
            if (upcoming == nextContinuation) break
            nextContinuation = upcoming
            page++
        }
        return collected.values.toList()
    }

    private fun executeInnerTubePost(
        endpoint: String,
        cookieHeader: String,
        visitorData: String,
        dataSyncId: String,
        authUserIndex: Int,
        payload: JSONObject,
        useWebClient: Boolean = false
    ): JSONObject {
        return try {
            executeInnerTubePostOnce(
                endpoint = endpoint,
                cookieHeader = cookieHeader,
                visitorData = visitorData,
                dataSyncId = dataSyncId,
                authUserIndex = authUserIndex,
                payload = payload,
                useWebClient = useWebClient
            )
        } catch (firstError: HttpStatusException) {
            // Retry once with minimal clean context if stale visitorData or delegatedPageId caused HTTP 400
            if (firstError.code == 400 && (visitorData.isNotBlank() || dataSyncId.isNotBlank())) {
                executeInnerTubePostOnce(
                    endpoint = endpoint,
                    cookieHeader = cookieHeader,
                    visitorData = "",
                    dataSyncId = "",
                    authUserIndex = authUserIndex,
                    payload = payload,
                    useWebClient = useWebClient
                )
            } else {
                throw firstError
            }
        }
    }

    private fun executeInnerTubePostOnce(
        endpoint: String,
        cookieHeader: String,
        visitorData: String,
        dataSyncId: String,
        authUserIndex: Int,
        payload: JSONObject,
        useWebClient: Boolean
    ): JSONObject {
        val origin = if (useWebClient) YOUTUBE_WEB_ORIGIN else YOUTUBE_MUSIC_ORIGIN
        val clientName = if (useWebClient) "WEB" else "WEB_REMIX"
        val clientId = if (useWebClient) "1" else "67"
        val clientVersion = if (useWebClient) "2.20250310.01.00" else "1.20250310.01.00"
        val cleanVisitorData = visitorData.trim().takeIf {
            it.isNotBlank() && !it.contains('%') && !it.contains(' ')
        }.orEmpty()

        val clientJson = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "ru")
            put("gl", "RU")
            if (cleanVisitorData.isNotBlank() && endpoint == "player") {
                put("visitorData", cleanVisitorData)
            }
        }
        val contextJson = JSONObject().put("client", clientJson)
        val delegatedPageId = extractDelegatedBrandPageId(dataSyncId)
        if (delegatedPageId != null) {
            contextJson.put(
                "user",
                JSONObject().put("onBehalfOfUser", delegatedPageId)
            )
        }

        val fullBody = JSONObject(payload.toString()).apply {
            put("context", contextJson)
        }.toString()

        val authHeader = buildSapisidAuthorizationHeader(cookieHeader, origin)
            ?: throw IllegalStateException("Отсутствует cookie SAPISID")

        val requestBuilder = Request.Builder()
            .url("$origin/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("Cookie", normalizeCookieHeader(cookieHeader))
            .header("Authorization", authHeader)
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .header("X-Origin", origin)
            .header("X-Youtube-Client-Name", clientId)
            .header("X-Youtube-Client-Version", clientVersion)
            .header("X-Goog-AuthUser", authUserIndex.coerceAtLeast(0).toString())
            .post(fullBody.toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (cleanVisitorData.isNotBlank() && endpoint == "player") {
            requestBuilder.header("X-Goog-Visitor-Id", cleanVisitorData)
        }
        if (delegatedPageId != null) {
            requestBuilder.header("X-Goog-PageId", delegatedPageId)
        }

        apiHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw HttpStatusException(
                        response.code,
                        "Сессия YouTube истекла или отклонена (HTTP ${response.code}). Войдите заново."
                    )
                }
                val detail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                val msg = if (detail.isNotBlank()) {
                    "Ошибка YouTube API (${response.code}): $detail"
                } else {
                    "Ошибка YouTube API (${response.code})"
                }
                throw HttpStatusException(response.code, msg)
            }
            if (raw.isBlank()) return JSONObject()
            return JSONObject(raw)
        }
    }

    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    private fun pickBestAudioUrl(array: JSONArray, audioQuality: AudioQuality): String? {
        val candidates = mutableListOf<Triple<String, String, Long>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mime = item.optString("mimeType")
            val url = item.optString("url")
            if (url.isBlank() || !mime.startsWith("audio/")) continue
            val bitrate = item.optLong("bitrate", 0L)
            candidates += Triple(url, mime, bitrate)
        }
        if (candidates.isEmpty()) return null
        val maxBitrateBps = audioQuality.maxBitrateKbps.toLong() * 1000L
        val filtered = candidates.filter { it.third <= maxBitrateBps }.ifEmpty { candidates }
        return filtered.maxByOrNull { (_, mime, bitrate) ->
            val formatBonus = if (mime.contains("mp4", ignoreCase = true)) 1_000_000L else 0L
            formatBonus + bitrate
        }?.first
    }

    companion object {
        const val YOUTUBE_MUSIC_ORIGIN = "https://music.youtube.com"
        const val YOUTUBE_WEB_ORIGIN = "https://www.youtube.com"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private const val MAX_LIKED_PAGES = 6
        private const val MAX_PLAYLIST_PAGES = 5
        private const val MAX_CREATE_BATCH = 50
        private const val MAX_EDIT_BATCH = 50

        fun normalizeCookieHeader(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) return ""
            // Handle Netscape cookie file paste if user imports from browser extension
            if (trimmed.contains('\t')) {
                val map = LinkedHashMap<String, String>()
                trimmed.lineSequence().forEach { line ->
                    val clean = line.trim()
                    if (clean.isNotEmpty() && !clean.startsWith("#")) {
                        val parts = clean.split('\t')
                        if (parts.size >= 7) {
                            val name = parts[5].trim()
                            val value = parts[6].trim()
                            if (name.isNotBlank() && value.isNotBlank()) {
                                map[name] = value
                            }
                        }
                    }
                }
                if (map.isNotEmpty()) {
                    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
            }
            return parseCookieMap(trimmed).entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        fun parseCookieMap(cookieHeader: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            cookieHeader.split(';').forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
            return map
        }

        fun extractSapisid(cookieHeader: String): String? {
            val map = parseCookieMap(normalizeCookieHeader(cookieHeader))
            return map["SAPISID"]
                ?: map["__Secure-3PAPISID"]
                ?: map["__Secure-1PAPISID"]
        }

        fun hasAuthCookies(cookieHeader: String): Boolean {
            val map = parseCookieMap(normalizeCookieHeader(cookieHeader))
            val hasSapisid = map.containsKey("SAPISID") ||
                map.containsKey("__Secure-3PAPISID") ||
                map.containsKey("__Secure-1PAPISID")
            val hasSid = map.containsKey("SID") ||
                map.containsKey("__Secure-3PSID") ||
                map.containsKey("__Secure-1PSID") ||
                map.containsKey("HSID")
            return hasSapisid && hasSid
        }

        fun extractDelegatedBrandPageId(dataSyncId: String): String? {
            val clean = dataSyncId.trim()
            if (!clean.contains("||")) return null
            val parts = clean.split("||", limit = 2)
            val pageId = parts.getOrNull(0)?.trim().orEmpty()
            val ownerId = parts.getOrNull(1)?.trim().orEmpty()
            // Personal accounts have empty ownerId ("12345||") or identical ids; only Brand accounts delegate
            return pageId.takeIf { it.isNotBlank() && ownerId.isNotBlank() && it != ownerId }
        }

        fun buildSapisidHashHeader(
            sapisid: String,
            origin: String = YOUTUBE_MUSIC_ORIGIN,
            timestampSeconds: Long = System.currentTimeMillis() / 1000L,
            scheme: String = "SAPISIDHASH"
        ): String {
            val input = "$timestampSeconds $sapisid $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "$scheme ${timestampSeconds}_$hex"
        }

        fun buildSapisidAuthorizationHeader(
            cookieHeader: String,
            origin: String = YOUTUBE_MUSIC_ORIGIN,
            timestampSeconds: Long = System.currentTimeMillis() / 1000L
        ): String? {
            val map = parseCookieMap(normalizeCookieHeader(cookieHeader))
            val sapisid = map["SAPISID"]
            val p1 = map["__Secure-1PAPISID"]
            val p3 = map["__Secure-3PAPISID"]
            val primary = sapisid ?: p3 ?: p1 ?: return null

            val tokens = mutableListOf<String>()
            tokens += buildSapisidHashHeader(primary, origin, timestampSeconds, "SAPISIDHASH")
            if (!p1.isNullOrBlank()) {
                tokens += buildSapisidHashHeader(p1, origin, timestampSeconds, "SAPISID1PHASH")
            }
            if (!p3.isNullOrBlank()) {
                tokens += buildSapisidHashHeader(p3, origin, timestampSeconds, "SAPISID3PHASH")
            }
            return tokens.joinToString(" ")
        }

        fun parseAccountProfile(root: JSONObject): YouTubeProfileInfo? {
            val header = findFirstObjectByKey(root, "activeAccountHeaderRenderer")
                ?: findFirstObjectByKey(root, "googleAccountHeaderRenderer")
                ?: return null

            val name = readRunsText(header.optJSONObject("accountName"))
                .ifBlank { readRunsText(header.optJSONObject("name")) }
                .ifBlank { "YouTube Account" }

            val handle = readRunsText(header.optJSONObject("channelHandle"))
            val email = readRunsText(header.optJSONObject("email"))
            val avatarUrl = header.optJSONObject("accountPhoto")
                ?.optJSONArray("thumbnails")
                ?.let { pickLargestThumbnailUrl(it) }
                .orEmpty()

            val stableId = (handle.ifBlank { email }.ifBlank { name })
                .lowercase()
                .replace(Regex("[^a-z0-9@._-]"), "_")

            return YouTubeProfileInfo(
                id = "yt_$stableId",
                name = name,
                handle = handle,
                email = email,
                avatarUrl = avatarUrl
            )
        }

        fun extractTracksRecursive(root: JSONObject): List<Track> {
            val listItems = mutableListOf<JSONObject>()
            collectObjectsByKey(root, "musicResponsiveListItemRenderer", listItems)
            val tracks = mutableListOf<Track>()

            listItems.forEach { item ->
                parseResponsiveListItemTrack(item)?.let { tracks += it }
            }

            if (tracks.isEmpty()) {
                val twoRowItems = mutableListOf<JSONObject>()
                collectObjectsByKey(root, "musicTwoRowItemRenderer", twoRowItems)
                twoRowItems.forEach { item ->
                    parseTwoRowTrack(item)?.let { tracks += it }
                }
            }

            if (tracks.isEmpty()) {
                val playlistVideoItems = mutableListOf<JSONObject>()
                collectObjectsByKey(root, "playlistVideoRenderer", playlistVideoItems)
                playlistVideoItems.forEach { item ->
                    parsePlaylistVideoRenderer(item)?.let { tracks += it }
                }
            }
            return tracks.distinctBy { it.id }
        }

        fun parseUserPlaylists(root: JSONObject): List<YouTubeUserPlaylist> {
            val result = LinkedHashMap<String, YouTubeUserPlaylist>()

            val twoRowItems = mutableListOf<JSONObject>()
            collectObjectsByKey(root, "musicTwoRowItemRenderer", twoRowItems)
            twoRowItems.forEach { item ->
                val browseId = item.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    .orEmpty()
                if (browseId.startsWith("VL") && browseId != "VLLM" && browseId != "VLSE") {
                    val playlistId = browseId.removePrefix("VL")
                    val title = readRunsText(item.optJSONObject("title")).ifBlank { "YouTube Playlist" }
                    val subtitle = readRunsText(item.optJSONObject("subtitle"))
                    val count = Regex("(\\d+)").find(subtitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val thumb = item.optJSONObject("thumbnailRenderer")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?.let { pickLargestThumbnailUrl(it) }
                        .orEmpty()
                    if (playlistId.isNotBlank() && title != "Liked music" && title != "Понравившиеся") {
                        result.putIfAbsent(
                            playlistId,
                            YouTubeUserPlaylist(
                                playlistId = playlistId,
                                title = title,
                                thumbnailUrl = thumb,
                                trackCount = count
                            )
                        )
                    }
                }
            }

            val listItems = mutableListOf<JSONObject>()
            collectObjectsByKey(root, "musicResponsiveListItemRenderer", listItems)
            listItems.forEach { item ->
                val browseId = item.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                    .orEmpty()
                if (browseId.startsWith("VL") && browseId != "VLLM" && browseId != "VLSE") {
                    val playlistId = browseId.removePrefix("VL")
                    val flexColumns = item.optJSONArray("flexColumns")
                    val title = flexColumns?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.let { readRunsText(it) }
                        .orEmpty()
                        .ifBlank { "YouTube Playlist" }
                    val thumb = item.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?.let { pickLargestThumbnailUrl(it) }
                        .orEmpty()
                    if (playlistId.isNotBlank()) {
                        result.putIfAbsent(
                            playlistId,
                            YouTubeUserPlaylist(
                                playlistId = playlistId,
                                title = title,
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            }

            return result.values.toList()
        }

        private fun parseResponsiveListItemTrack(item: JSONObject): Track? {
            val videoId = item.optJSONObject("playlistItemData")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
                ?: item.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                ?: findFirstStringByKey(item, "videoId")
                ?: return null

            val flexColumns = item.optJSONArray("flexColumns")
            val title = flexColumns?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.let { readRunsText(it) }
                .orEmpty()
                .ifBlank { return null }

            val rawArtist = flexColumns?.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.let { readRunsText(it) }
                .orEmpty()

            val artist = rawArtist
                .split("•")
                .firstOrNull { part ->
                    val trimmed = part.trim()
                    trimmed.isNotEmpty() && !DURATION_TEXT_REGEX.matches(trimmed)
                }
                ?.trim()
                .orEmpty()
                .ifBlank { "YouTube Artist" }

            var durationSeconds = 0L
            val fixedColumns = item.optJSONArray("fixedColumns")
            if (fixedColumns != null && fixedColumns.length() > 0) {
                val durText = fixedColumns.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")
                    ?.let { readRunsText(it) }
                    .orEmpty()
                durationSeconds = parseDurationSeconds(durText)
            }
            if (durationSeconds == 0L && rawArtist.contains("•")) {
                val tail = rawArtist.split("•").lastOrNull()?.trim().orEmpty()
                durationSeconds = parseDurationSeconds(tail)
            }

            val thumbnailUrl = item.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { pickLargestThumbnailUrl(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            return Track(
                id = videoId,
                title = title,
                artist = artist,
                durationSeconds = durationSeconds,
                thumbnailUrl = thumbnailUrl
            )
        }

        private fun parseTwoRowTrack(item: JSONObject): Track? {
            val videoId = item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val title = readRunsText(item.optJSONObject("title")).ifBlank { return null }
            val artist = readRunsText(item.optJSONObject("subtitle"))
                .split("•")
                .firstOrNull()
                ?.trim()
                .orEmpty()
                .ifBlank { "YouTube Artist" }
            val thumb = item.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { pickLargestThumbnailUrl(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            return Track(
                id = videoId,
                title = title,
                artist = artist,
                durationSeconds = 0L,
                thumbnailUrl = thumb
            )
        }

        private fun parsePlaylistVideoRenderer(item: JSONObject): Track? {
            val videoId = item.optString("videoId").takeIf { it.isNotBlank() } ?: return null
            val title = readRunsText(item.optJSONObject("title")).ifBlank { return null }
            val artist = readRunsText(item.optJSONObject("shortBylineText"))
                .ifBlank { "YouTube Artist" }
            val durationSeconds = item.optString("lengthSeconds").toLongOrNull()
                ?: parseDurationSeconds(readRunsText(item.optJSONObject("lengthText")))
            val thumb = item.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?.let { pickLargestThumbnailUrl(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            return Track(
                id = videoId,
                title = title,
                artist = artist,
                durationSeconds = durationSeconds,
                thumbnailUrl = thumb
            )
        }

        fun extractNextContinuation(root: JSONObject): String? {
            findFirstObjectByKey(root, "nextContinuationData")
                ?.optString("continuation")
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            val contRenderers = mutableListOf<JSONObject>()
            collectObjectsByKey(root, "continuationItemRenderer", contRenderers)
            for (renderer in contRenderers) {
                val token = renderer.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                if (token != null) return token
            }
            return null
        }

        private fun parseDurationSeconds(raw: String): Long {
            val clean = raw.trim()
            if (!DURATION_TEXT_REGEX.matches(clean)) return 0L
            val parts = clean.split(':').mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> 0L
            }
        }

        private fun readRunsText(obj: JSONObject?): String {
            if (obj == null) return ""
            obj.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
            val runs = obj.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            return sb.toString().trim()
        }

        private fun pickLargestThumbnailUrl(array: JSONArray): String {
            var bestUrl = ""
            var bestArea = -1
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                val area = obj.optInt("width", 0) * obj.optInt("height", 0)
                if (area >= bestArea) {
                    bestArea = area
                    bestUrl = url
                }
            }
            return bestUrl
        }

        private fun findFirstObjectByKey(node: Any?, targetKey: String): JSONObject? {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(targetKey)?.let { return it }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findFirstObjectByKey(node.opt(key), targetKey)?.let { return it }
                    }
                }

                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findFirstObjectByKey(node.opt(i), targetKey)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun findFirstStringByKey(node: Any?, targetKey: String): String? {
            when (node) {
                is JSONObject -> {
                    node.optString(targetKey).takeIf { it.isNotBlank() }?.let { return it }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findFirstStringByKey(node.opt(key), targetKey)?.let { return it }
                    }
                }

                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findFirstStringByKey(node.opt(i), targetKey)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun collectObjectsByKey(
            node: Any?,
            targetKey: String,
            output: MutableList<JSONObject>
        ) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(targetKey)?.let { output.add(it) }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        collectObjectsByKey(node.opt(key), targetKey, output)
                    }
                }

                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectObjectsByKey(node.opt(i), targetKey, output)
                    }
                }
            }
        }

        private val DURATION_TEXT_REGEX = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
    }
}
