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
import androidx.media3.common.util.UnstableApi
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class SonoraPlaybackService : MediaLibraryService() {

    @Inject
    lateinit var audioCacheProvider: AudioCacheProvider

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var libraryRepository: LibraryRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaLibrarySession: MediaLibrarySession? = null

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

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        serviceScope.launch {
            settingsRepository.settings.collect { appSettings ->
                player.skipSilenceEnabled = appSettings.skipSilenceEnabled
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
        serviceScope.launch {
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
            val items = when (parentId) {
                ROOT_ID -> listOf(
                    browsableNode(NODE_DOWNLOADS, "Offline Downloads"),
                    browsableNode(NODE_FAVORITES, "Liked Songs"),
                    browsableNode(NODE_HISTORY, "Recently Played")
                )

                NODE_DOWNLOADS -> runBlocking {
                    libraryRepository.observeDownloadedTracks().first().map { it.toMediaItem() }
                }

                NODE_FAVORITES -> runBlocking {
                    libraryRepository.observeFavorites().first().map { it.toMediaItem() }
                }

                NODE_HISTORY -> runBlocking {
                    libraryRepository.observeHistory().first().map { it.toMediaItem() }
                }

                else -> emptyList()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )
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

    private companion object {
        const val ROOT_ID = "sonora_root"
        const val NODE_DOWNLOADS = "sonora_downloads"
        const val NODE_FAVORITES = "sonora_favorites"
        const val NODE_HISTORY = "sonora_history"
    }
}
