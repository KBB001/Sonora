package com.example.data.remote

import com.example.core.common.NetworkMonitor
import com.example.data.repository.YouTubeAccountStore
import com.example.domain.model.AudioQuality
import com.example.domain.model.Track
import com.example.domain.model.YouTubeArtistSummary
import com.example.domain.model.YouTubePlaylistSummary
import com.example.domain.repository.YouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeRepositoryImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val networkMonitor: NetworkMonitor? = null,
    private val accountStore: YouTubeAccountStore? = null,
    private val youTubeMusicAccountApi: YouTubeMusicAccountApi? = null
) : YouTubeRepository {

    constructor(okHttpClient: OkHttpClient) : this(okHttpClient, null, null, null)

    private val youtubeService = ServiceList.YouTube
    private val fallbackHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val searchTracksCache = TtlLruCache<String, List<Track>>(maxEntries = 30, ttlMs = SEARCH_CACHE_TTL_MS)
    private val searchPlaylistsCache = TtlLruCache<String, List<YouTubePlaylistSummary>>(maxEntries = 20, ttlMs = SEARCH_CACHE_TTL_MS)
    private val searchArtistsCache = TtlLruCache<String, List<YouTubeArtistSummary>>(maxEntries = 20, ttlMs = SEARCH_CACHE_TTL_MS)
    private val suggestionsCache = TtlLruCache<String, List<String>>(maxEntries = 60, ttlMs = SUGGESTIONS_CACHE_TTL_MS)
    private val playlistCache = TtlLruCache<String, Pair<String, List<Track>>>(maxEntries = 20, ttlMs = SEARCH_CACHE_TTL_MS)
    private val artistTracksCache = TtlLruCache<String, List<Track>>(maxEntries = 20, ttlMs = SEARCH_CACHE_TTL_MS)
    private val relatedTracksCache = TtlLruCache<String, List<Track>>(maxEntries = 32, ttlMs = RELATED_CACHE_TTL_MS)
    private val streamUrlCache = TtlLruCache<String, String>(maxEntries = 64, ttlMs = STREAM_URL_TTL_MS)
    private val inFlightStreams = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()

    private fun isOffline(): Boolean = networkMonitor?.isOnline?.value == false

    override suspend fun searchTracks(query: String): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext Result.success(emptyList())
            }
            val cacheKey = trimmed.lowercase()
            searchTracksCache.get(cacheKey)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val queryHandler = youtubeService.searchQHFactory.fromQuery(trimmed)
                val searchInfo = SearchInfo.getInfo(youtubeService, queryHandler)
                val tracks = searchInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<StreamInfoItem>()
                    .filter {
                        it.streamType == StreamType.VIDEO_STREAM ||
                            it.streamType == StreamType.AUDIO_STREAM
                    }
                    .mapNotNull { it.toTrackOrNull() }
                    .distinctBy { it.id }
                searchTracksCache.put(cacheKey, tracks)
                Result.success(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun searchPlaylists(query: String): Result<List<YouTubePlaylistSummary>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext Result.success(emptyList())
            val cacheKey = trimmed.lowercase()
            searchPlaylistsCache.get(cacheKey)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val queryHandler = youtubeService.searchQHFactory.fromQuery(
                    trimmed,
                    listOf("playlists"),
                    ""
                )
                val searchInfo = SearchInfo.getInfo(youtubeService, queryHandler)
                val playlists = searchInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<PlaylistInfoItem>()
                    .map { item ->
                        YouTubePlaylistSummary(
                            url = item.url.orEmpty(),
                            name = item.name.orEmpty().ifBlank { "Плейлист YouTube" },
                            uploaderName = item.uploaderName.orEmpty(),
                            trackCount = item.streamCount.coerceAtLeast(0L),
                            thumbnailUrl = normalizeThumbnailUrl(
                                item.thumbnails.orEmpty().maxByOrNull {
                                    it.width.coerceAtLeast(0) * it.height.coerceAtLeast(0)
                                }?.url.orEmpty()
                            )
                        )
                    }
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
                searchPlaylistsCache.put(cacheKey, playlists)
                Result.success(playlists)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun searchArtists(query: String): Result<List<YouTubeArtistSummary>> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext Result.success(emptyList())
            val cacheKey = trimmed.lowercase()
            searchArtistsCache.get(cacheKey)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val queryHandler = youtubeService.searchQHFactory.fromQuery(
                    trimmed,
                    listOf("channels"),
                    ""
                )
                val searchInfo = SearchInfo.getInfo(youtubeService, queryHandler)
                val artists = searchInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<ChannelInfoItem>()
                    .map { item ->
                        YouTubeArtistSummary(
                            url = item.url.orEmpty(),
                            name = item.name.orEmpty().ifBlank { "Исполнитель" },
                            subscriberCount = item.subscriberCount.coerceAtLeast(0L),
                            thumbnailUrl = normalizeThumbnailUrl(
                                item.thumbnails.orEmpty().maxByOrNull {
                                    it.width.coerceAtLeast(0) * it.height.coerceAtLeast(0)
                                }?.url.orEmpty()
                            )
                        )
                    }
                    .filter { it.url.isNotBlank() }
                    .distinctBy { it.url }
                searchArtistsCache.put(cacheKey, artists)
                Result.success(artists)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getArtistTracks(channelUrl: String): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val trimmed = channelUrl.trim()
            if (trimmed.isEmpty()) return@withContext Result.success(emptyList())
            artistTracksCache.get(trimmed)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val channelInfo = ChannelInfo.getInfo(youtubeService, trimmed)
                val videoTab = channelInfo.tabs.orEmpty().firstOrNull()
                    ?: return@withContext Result.success(emptyList())
                val tabInfo = ChannelTabInfo.getInfo(youtubeService, videoTab)
                val tracks = tabInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toTrackOrNull() }
                    .distinctBy { it.id }
                artistTracksCache.put(trimmed, tracks)
                Result.success(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getSearchSuggestions(query: String): List<String> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.length < 2) return@withContext emptyList()
            val cacheKey = trimmed.lowercase()
            suggestionsCache.get(cacheKey)?.let { return@withContext it }
            if (isOffline()) return@withContext emptyList()
            runCatching {
                youtubeService.suggestionExtractor
                    .suggestionList(trimmed)
                    .orEmpty()
                    .distinct()
                    .take(8)
                    .also { list ->
                        if (list.isNotEmpty()) suggestionsCache.put(cacheKey, list)
                    }
            }.getOrDefault(emptyList())
        }

    override suspend fun getAudioStreamUrl(
        videoId: String,
        audioQuality: AudioQuality,
        forceRefresh: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId)
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Некорректный ID трека"))
        }

        val cacheKey = "$cleanId:${audioQuality.name}"
        if (forceRefresh) {
            streamUrlCache.remove(cacheKey)
        } else {
            streamUrlCache.get(cacheKey)?.let { cachedUrl ->
                return@withContext Result.success(cachedUrl)
            }
        }

        if (isOffline()) {
            return@withContext Result.failure(
                IOException("Нет подключения к интернету. Скачайте трек для прослушивания офлайн.")
            )
        }

        // Deduplicate concurrent stream resolutions for the same videoId + quality
        val newDeferred = CompletableDeferred<Result<String>>()
        val existingDeferred = inFlightStreams.putIfAbsent(cacheKey, newDeferred)
        if (existingDeferred != null) {
            return@withContext existingDeferred.await()
        }

        try {
            val resolved = resolveAudioStreamInternal(cleanId, audioQuality)
            resolved.onSuccess { url -> streamUrlCache.put(cacheKey, url) }
            newDeferred.complete(resolved)
            resolved
        } catch (e: CancellationException) {
            newDeferred.cancel(e)
            throw e
        } catch (e: Exception) {
            val failure = Result.failure<String>(e)
            newDeferred.complete(failure)
            failure
        } finally {
            inFlightStreams.remove(cacheKey, newDeferred)
        }
    }

    private suspend fun resolveAudioStreamInternal(
        cleanId: String,
        audioQuality: AudioQuality
    ): Result<String> {
        var primaryError: Exception? = null

        // 1. Primary: NewPipeExtractor direct extraction (also opportunistically caches related tracks)
        try {
            val watchUrl = "https://www.youtube.com/watch?v=$cleanId"
            val streamInfo = StreamInfo.getInfo(youtubeService, watchUrl)
            val related = streamInfo.relatedItems
                .orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter {
                    it.streamType == StreamType.VIDEO_STREAM ||
                        it.streamType == StreamType.AUDIO_STREAM
                }
                .mapNotNull { it.toTrackOrNull() }
                .distinctBy { it.id }
            if (related.isNotEmpty()) {
                relatedTracksCache.put(cleanId, related)
            }
            val bestAudioStream = selectBestAudioStream(streamInfo.audioStreams.orEmpty(), audioQuality)
            if (bestAudioStream != null) {
                return Result.success(bestAudioStream.content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            primaryError = e
        }

        // 2. Fallback: Authenticated user session (if signed in to YouTube)
        val activeAccount = accountStore?.getActiveAccountSnapshot()
        if (activeAccount != null && youTubeMusicAccountApi != null) {
            youTubeMusicAccountApi
                .resolveAuthenticatedStreamUrl(activeAccount, cleanId, audioQuality)
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                ?.let { url ->
                    return Result.success(url)
                }
        }

        // 3. Fallback: Direct InnerTube alternative client profiles
        fetchViaInnerTubeClients(cleanId, audioQuality)?.let { url ->
            return Result.success(url)
        }

        // 4. Fallback: Public Piped / Invidious audio endpoints
        fetchViaPipedInstances(cleanId, audioQuality)?.let { url ->
            return Result.success(url)
        }
        fetchViaInvidiousInstances(cleanId, audioQuality)?.let { url ->
            return Result.success(url)
        }

        val rawMessage = primaryError?.message.orEmpty()
        val friendlyError = if (
            rawMessage.contains("LOGIN_REQUIRED", ignoreCase = true) ||
            rawMessage.contains("not a bot", ignoreCase = true)
        ) {
            IllegalStateException(
                "YouTube ограничил анонимное воспроизведение для этого IP. Попробуйте сменить сеть (Wi-Fi / мобильные данные), войти в аккаунт или включить скачанный трек."
            )
        } else {
            primaryError ?: IllegalStateException("Не удалось найти доступный аудиопоток")
        }
        return Result.failure(friendlyError)
    }

    override suspend fun getRelatedTracks(videoId: String): Result<List<Track>> =
        withContext(Dispatchers.IO) {
            val cleanId = extractVideoId(videoId)
            if (cleanId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Некорректный ID видео"))
            }
            relatedTracksCache.get(cleanId)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val watchUrl = "https://www.youtube.com/watch?v=$cleanId"
                val streamInfo = StreamInfo.getInfo(youtubeService, watchUrl)
                val related = streamInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<StreamInfoItem>()
                    .filter {
                        it.streamType == StreamType.VIDEO_STREAM ||
                            it.streamType == StreamType.AUDIO_STREAM
                    }
                    .mapNotNull { it.toTrackOrNull() }
                    .distinctBy { it.id }
                relatedTracksCache.put(cleanId, related)
                Result.success(related)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getPlaylistTracks(playlistUrl: String): Result<List<Track>> =
        getPlaylistWithTitle(playlistUrl).map { it.second }

    override suspend fun getPlaylistWithTitle(playlistUrl: String): Result<Pair<String, List<Track>>> =
        withContext(Dispatchers.IO) {
            val trimmedUrl = playlistUrl.trim()
            if (trimmedUrl.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Ссылка на плейлист не может быть пустой"))
            }
            playlistCache.get(trimmedUrl)?.let { cached ->
                return@withContext Result.success(cached)
            }
            if (isOffline()) {
                return@withContext Result.failure(IOException("Нет подключения к интернету"))
            }
            try {
                val playlistInfo = PlaylistInfo.getInfo(youtubeService, trimmedUrl)
                val collected = mutableListOf<Track>()

                playlistInfo.relatedItems
                    .orEmpty()
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNullTo(collected) { it.toTrackOrNull() }

                var currentPage = playlistInfo.nextPage
                var pageCount = 0
                while (currentPage != null && pageCount < MAX_PLAYLIST_PAGES) {
                    val moreItems = PlaylistInfo.getMoreItems(youtubeService, trimmedUrl, currentPage)
                    moreItems.items
                        .orEmpty()
                        .filterIsInstance<StreamInfoItem>()
                        .mapNotNullTo(collected) { it.toTrackOrNull() }
                    currentPage = if (moreItems.hasNextPage()) moreItems.nextPage else null
                    pageCount++
                }

                val playlistTitle = playlistInfo.name.orEmpty().takeIf { it.isNotBlank() } ?: "Плейлист YouTube"
                val resultPair = playlistTitle to collected.distinctBy { it.id }
                playlistCache.put(trimmedUrl, resultPair)
                Result.success(resultPair)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun fetchViaInnerTubeClients(videoId: String, audioQuality: AudioQuality): String? {
        val clients = listOf(
            InnerTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L"
            ),
            InnerTubeClient(
                clientName = "IOS",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                osName = "iOS",
                osVersion = "18.3.2"
            )
        )

        for (client in clients) {
            val resolved = runCatching {
                val bodyJson = JSONObject().apply {
                    put("videoId", videoId)
                    put(
                        "context",
                        JSONObject().put(
                            "client",
                            JSONObject().apply {
                                put("clientName", client.clientName)
                                put("clientVersion", client.clientVersion)
                                put("osName", client.osName)
                                put("osVersion", client.osVersion)
                                put("hl", "en")
                                put("gl", "US")
                            }
                        )
                    )
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }.toString()

                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", client.userAgent)
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                fallbackHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) return@use null
                    val json = JSONObject(raw)
                    val playability = json.optJSONObject("playabilityStatus")?.optString("status")
                    if (playability != "OK") return@use null
                    val adaptive = json.optJSONObject("streamingData")
                        ?.optJSONArray("adaptiveFormats") ?: return@use null
                    pickBestJsonAudioUrl(adaptive, "mimeType", audioQuality)
                }
            }.getOrNull()

            if (!resolved.isNullOrBlank()) return resolved
        }
        return null
    }

    private fun fetchViaPipedInstances(videoId: String, audioQuality: AudioQuality): String? {
        for (baseUrl in PIPED_INSTANCES) {
            val url = runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/streams/$videoId")
                    .header("Accept", "application/json")
                    .build()

                fallbackHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) return@use null
                    val json = JSONObject(raw)
                    val audioStreams = json.optJSONArray("audioStreams") ?: return@use null
                    pickBestJsonAudioUrl(audioStreams, "mimeType", audioQuality, requireAudioPrefix = false)
                }
            }.getOrNull()

            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun fetchViaInvidiousInstances(videoId: String, audioQuality: AudioQuality): String? {
        for (baseUrl in INVIDIOUS_INSTANCES) {
            val url = runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/videos/$videoId")
                    .header("Accept", "application/json")
                    .build()

                fallbackHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) return@use null
                    val json = JSONObject(raw)
                    val adaptive = json.optJSONArray("adaptiveFormats") ?: return@use null
                    pickBestJsonAudioUrl(adaptive, "type", audioQuality)
                }
            }.getOrNull()

            if (!url.isNullOrBlank()) return url
        }
        return null
    }

    private fun pickBestJsonAudioUrl(
        array: JSONArray,
        mimeKey: String,
        audioQuality: AudioQuality,
        requireAudioPrefix: Boolean = true
    ): String? {
        val candidates = mutableListOf<Triple<String, String, Long>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val mime = item.optString(mimeKey)
            val url = item.optString("url").trim()
            if (!url.startsWith("https://", ignoreCase = true)) continue
            if (requireAudioPrefix && !mime.startsWith("audio/")) continue
            val bitrate = item.optLong("bitrate", item.optString("bitrate").toLongOrNull() ?: 0L)
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

    private fun selectBestAudioStream(
        streams: List<AudioStream>,
        audioQuality: AudioQuality
    ): AudioStream? {
        val validStreams = streams.filter { stream ->
            stream.isUrl &&
                stream.content.orEmpty().startsWith("https://", ignoreCase = true) &&
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val candidates = validStreams.ifEmpty {
            streams.filter { it.isUrl && it.content.orEmpty().startsWith("https://", ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null

        val maxBitrateKbps = audioQuality.maxBitrateKbps
        val qualityFiltered = candidates.filter {
            val effectiveKbps = it.averageBitrate.takeIf { b -> b > 0 } ?: (it.bitrate / 1000)
            effectiveKbps <= maxBitrateKbps
        }.ifEmpty { candidates }

        return qualityFiltered.maxWithOrNull(
            compareBy<AudioStream>(
                { formatPriority(it.format) },
                { it.averageBitrate },
                { it.bitrate }
            )
        )
    }

    private fun formatPriority(format: MediaFormat?): Int = when (format) {
        MediaFormat.M4A -> 3
        MediaFormat.WEBMA_OPUS -> 2
        MediaFormat.WEBMA -> 1
        MediaFormat.MP3 -> 1
        else -> 0
    }

    private fun StreamInfoItem.toTrackOrNull(): Track? {
        val videoId = extractVideoId(url.orEmpty())
        if (videoId.isBlank()) return null
        val bestThumbnail = selectBestThumbnail(thumbnails.orEmpty(), videoId)
        return Track(
            id = videoId,
            title = name.orEmpty().ifBlank { "Без названия" },
            artist = uploaderName.orEmpty().ifBlank { "Неизвестный исполнитель" },
            durationSeconds = duration.coerceAtLeast(0L),
            thumbnailUrl = bestThumbnail
        )
    }

    private fun selectBestThumbnail(thumbnails: List<Image>, videoId: String): String {
        val bestFromExtractor = thumbnails
            .mapNotNull { image ->
                val normalized = normalizeThumbnailUrl(image.url.orEmpty())
                normalized.takeIf { it.isNotBlank() }?.let { url -> image to url }
            }
            .maxByOrNull { (image, _) ->
                val width = image.width.takeIf { it > 0 } ?: 0
                val height = image.height.takeIf { it > 0 } ?: 0
                width * height
            }
            ?.second
        return bestFromExtractor ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun normalizeThumbnailUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        return if (normalized.startsWith("https://", ignoreCase = true)) normalized else ""
    }

    private fun extractVideoId(rawUrlOrId: String): String {
        val trimmed = rawUrlOrId.trim()
        if (trimmed.matches(VIDEO_ID_REGEX)) {
            return trimmed
        }
        return runCatching {
            youtubeService.streamLHFactory.getId(trimmed)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: VIDEO_URL_REGEX
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
    }

    private class TtlLruCache<K : Any, V : Any>(
        private val maxEntries: Int,
        private val ttlMs: Long
    ) {
        private data class Entry<V>(val value: V, val expiresAtMs: Long)

        private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        fun get(key: K): V? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() > entry.expiresAtMs) {
                map.remove(key)
                return null
            }
            return entry.value
        }

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
        }

        @Synchronized
        fun remove(key: K) {
            map.remove(key)
        }
    }

    private data class InnerTubeClient(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String
    )

    private companion object {
        const val MAX_PLAYLIST_PAGES = 4
        const val SEARCH_CACHE_TTL_MS = 5L * 60L * 1000L
        const val SUGGESTIONS_CACHE_TTL_MS = 10L * 60L * 1000L
        const val RELATED_CACHE_TTL_MS = 15L * 60L * 1000L
        const val STREAM_URL_TTL_MS = 2L * 60L * 60L * 1000L
        val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        val VIDEO_URL_REGEX = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})")
        val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
        )
        val INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://invidious.nerdvpn.de"
        )
    }
}
