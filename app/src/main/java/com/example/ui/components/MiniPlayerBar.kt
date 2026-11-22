package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.domain.model.PlaybackProgress
import com.example.domain.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun MiniPlayerBar(
    playbackState: PlaybackState,
    progressFlow: StateFlow<PlaybackProgress>,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack

    AnimatedVisibility(
        visible = track != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        if (track != null) {
            val context = LocalContext.current
            val imageRequest = remember(track.thumbnailUrl) {
                ImageRequest.Builder(context)
                    .data(track.thumbnailUrl)
                    .size(132)
                    .crossfade(true)
                    .build()
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenNowPlaying)
                    .testTag("mini_player_bar"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = "Обложка ${track.title}",
                            placeholder = painterResource(R.drawable.img_default_cover),
                            error = painterResource(R.drawable.img_default_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (playbackState.isLoadingStream) {
                                    "Подключение потока..."
                                } else {
                                    track.artist
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (playbackState.isLoadingStream) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (playbackState.isLoadingStream || playbackState.isBuffering) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("mini_player_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) {
                                        SonoraIcons.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    contentDescription = if (playbackState.isPlaying) "Пауза" else "Воспроизвести",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        IconButton(
                            onClick = onSkipNext,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_player_next")
                        ) {
                            Icon(
                                imageVector = SonoraIcons.SkipNext,
                                contentDescription = "Следующий трек",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    MiniPlayerProgressIndicator(progressFlow = progressFlow)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerProgressIndicator(progressFlow: StateFlow<PlaybackProgress>) {
    val progressState by progressFlow.collectAsStateWithLifecycle()
    val progress = if (progressState.durationMs > 0L) {
        (progressState.positionMs.toFloat() / progressState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.5.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    )
}
