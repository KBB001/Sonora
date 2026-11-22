package com.example.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class AudioCacheProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir = File(context.cacheDir, "sonora_audio_cache").apply { mkdirs() }
    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }
    private val activePrefetchIds = ConcurrentHashMap.newKeySet<String>()
    private val prefetchedTrackUrls = ConcurrentHashMap<String, String>()

    val simpleCache: SimpleCache by lazy {
        SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            databaseProvider
        )
    }

    private fun buildHttpUpstreamFactory(): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Android 14; Mobile) Sonora/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        return DefaultDataSource.Factory(context, httpFactory)
    }

    fun buildCacheDataSourceFactory(): DataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(buildHttpUpstreamFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Prefetches the opening audio segment of [streamUrl] into [simpleCache] for instant gapless
     * track transitions without downloading full files over metered connections.
     */
    suspend fun prefetchTrackSegment(
        trackId: String,
        streamUrl: String,
        maxBytes: Long = DEFAULT_PREFETCH_BYTES
    ) = withContext(Dispatchers.IO) {
        if (trackId.isBlank() || streamUrl.isBlank()) return@withContext
        if (prefetchedTrackUrls[trackId] == streamUrl) return@withContext
        if (!activePrefetchIds.add(trackId)) return@withContext

        try {
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(buildHttpUpstreamFactory())
                .createDataSource()

            val dataSpec = DataSpec.Builder()
                .setUri(streamUrl)
                .setPosition(0L)
                .setLength(maxBytes.coerceAtLeast(256L * 1024L))
                .build()

            val writer = CacheWriter(cacheDataSource, dataSpec, null, null)
            writer.cache()
            prefetchedTrackUrls[trackId] = streamUrl
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Best-effort prefetch; playback will stream directly if prefetch fails
        } finally {
            activePrefetchIds.remove(trackId)
        }
    }

    suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        simpleCache.cacheSpace
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        prefetchedTrackUrls.clear()
        simpleCache.keys.toList().forEach { key ->
            runCatching { simpleCache.removeResource(key) }
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
        const val DEFAULT_PREFETCH_BYTES = 1_250_000L
    }
}
