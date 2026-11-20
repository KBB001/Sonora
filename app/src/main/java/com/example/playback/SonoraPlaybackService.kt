package com.example.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.MainActivity
import com.example.domain.model.Track
import com.example.domain.repository.LibraryRepository
import com.example.domain.repository.SettingsRepository
import com.example.widget.SonoraWidget
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SonoraPlaybackService : MediaLibraryService() {

    @Inject
    lateinit var audioCacheProvider: AudioCacheProvider

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var equalizerManager: SonoraEqualizerManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var lastWidgetSnapshot: WidgetSnapshot? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(
            audioCacheProvider.buildCacheDataSourceFactory()
        )

        // Audio-tuned LoadControl: faster playback start (1.5s) and battery-friendly buffering
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                45_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(15_000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        serviceScope.launch {
            settingsRepository.settings
                .map { it.skipSilenceEnabled }
                .distinctUntilChanged()
                .collect { skipSilence ->
                    player.skipSilenceEnabled = skipSilence
                }
        }

        // Enable DSP hardware audio offload when Equalizer is off to allow CPU sleep during playback
        serviceScope.launch {
            equalizerManager.state
                .map { it.isEnabled }
                .distinctUntilChanged()
                .collect { eqEnabled ->
                    val offloadPrefs = AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(
                            if (eqEnabled) {
                                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            } else {
                                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                            }
                        )
                        .build()
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setAudioOffloadPreferences(offloadPrefs)
                        .build()
                }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncWidget(player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                syncWidget(player)
            }
        })

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            player,
            SonoraLibraryCallback()
        )
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    private fun syncWidget(player: Player) {
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Sonora"
        val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Ready to play"
        val isPlaying = player.isPlaying
        val snapshot = WidgetSnapshot(title = title, artist = artist, isPlaying = isPlaying)
        if (snapshot == lastWidgetSnapshot) return
        lastWidgetSnapshot = snapshot

        serviceScope.launch(Dispatchers.IO) {
            SonoraWidget.updateAllWidgets(
                context = this@SonoraPlaybackService,
                title = title,
                artist = artist,
                isPlaying = isPlaying
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class SonoraLibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Sonora")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == ROOT_ID) {
                val rootChildren = listOf(
                    browsableNode(NODE_DOWNLOADS, "Offline Downloads"),
                    browsableNode(NODE_FAVORITES, "Liked Songs"),
                    browsableNode(NODE_HISTORY, "Recently Played")
                )
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(ImmutableList.copyOf(rootChildren), params)
                )
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch(Dispatchers.IO) {
                runCatching {
                    val tracks = when (parentId) {
                        NODE_DOWNLOADS -> libraryRepository.observeDownloadedTracks().first()
                        NODE_FAVORITES -> libraryRepository.observeFavorites().first()
                        NODE_HISTORY -> libraryRepository.observeHistory().first()
                        else -> emptyList()
                    }
                    val items = tracks.map { it.toMediaItem() }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                }.onFailure {
                    future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }
            return future
        }

        private fun browsableNode(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
        }

        private fun Track.toMediaItem(): MediaItem {
            val uri = localFilePath?.let { Uri.fromFile(java.io.File(it)) }
            return MediaItem.Builder()
                .setMediaId(id)
                .apply { if (uri != null) setUri(uri) }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setArtworkUri(Uri.parse(thumbnailUrl))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }

    private data class WidgetSnapshot(
        val title: String,
        val artist: String,
        val isPlaying: Boolean
    )

    private companion object {
        const val ROOT_ID = "sonora_root"
        const val NODE_DOWNLOADS = "sonora_downloads"
        const val NODE_FAVORITES = "sonora_favorites"
        const val NODE_HISTORY = "sonora_history"
    }
}
