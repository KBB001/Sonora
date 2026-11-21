package com.example.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.MainActivity
import com.example.R
import com.example.playback.SonoraPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object SonoraWidgetKeys {
    val TRACK_TITLE = stringPreferencesKey("widget_track_title")
    val TRACK_ARTIST = stringPreferencesKey("widget_track_artist")
    val IS_PLAYING = booleanPreferencesKey("widget_is_playing")
}

class SonoraWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val title = prefs[SonoraWidgetKeys.TRACK_TITLE] ?: "Sonora"
            val artist = prefs[SonoraWidgetKeys.TRACK_ARTIST] ?: "Нажмите для воспроизведения"
            val isPlaying = prefs[SonoraWidgetKeys.IS_PLAYING] ?: false

            WidgetContent(
                title = title,
                artist = artist,
                isPlaying = isPlaying
            )
        }
    }

    @Composable
    private fun WidgetContent(
        title: String,
        artist: String,
        isPlaying: Boolean
    ) {
        val bgColor = ColorProvider(Color(0xFF12151C))
        val accentColor = ColorProvider(Color(0xFF1ED760))
        val textPrimary = ColorProvider(Color(0xFFF8FAFC))
        val textSecondary = ColorProvider(Color(0xFF94A3B8))

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(20.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.glance.Image(
                provider = ImageProvider(R.mipmap.ic_launcher_round),
                contentDescription = "Sonora",
                modifier = GlanceModifier
                    .size(44.dp)
                    .cornerRadius(10.dp)
            )

            Spacer(modifier = GlanceModifier.width(12.dp))

            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        color = textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = artist,
                    maxLines = 1,
                    style = TextStyle(
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "⏮",
                    style = TextStyle(color = textPrimary, fontSize = 18.sp),
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .clickable(actionRunCallback<WidgetPrevAction>())
                )
                Text(
                    text = if (isPlaying) "⏸" else "▶",
                    style = TextStyle(
                        color = accentColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .clickable(actionRunCallback<WidgetPlayPauseAction>())
                )
                Text(
                    text = "⏭",
                    style = TextStyle(color = textPrimary, fontSize = 18.sp),
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .clickable(actionRunCallback<WidgetNextAction>())
                )
            }
        }
    }

    companion object {
        suspend fun updateAllWidgets(
            context: Context,
            title: String,
            artist: String,
            isPlaying: Boolean
        ) {
            runCatching {
                val manager = GlanceAppWidgetManager(context)
                val glanceIds = manager.getGlanceIds(SonoraWidget::class.java)
                glanceIds.forEach { glanceId ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                        prefs.toMutablePreferences().apply {
                            this[SonoraWidgetKeys.TRACK_TITLE] = title
                            this[SonoraWidgetKeys.TRACK_ARTIST] = artist
                            this[SonoraWidgetKeys.IS_PLAYING] = isPlaying
                        }
                    }
                    SonoraWidget().update(context, glanceId)
                }
            }
        }
    }
}

class SonoraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SonoraWidget()
}

private suspend fun dispatchMediaAction(
    context: Context,
    action: (MediaController) -> Unit
) {
    withContext(Dispatchers.Main) {
        runCatching {
            val token = SessionToken(context, ComponentName(context, SonoraPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val controller = withContext(Dispatchers.IO) {
                future.get(3, TimeUnit.SECONDS)
            }
            try {
                action(controller)
            } finally {
                controller.release()
            }
        }
    }
}

class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        dispatchMediaAction(context) { controller ->
            if (controller.isPlaying) controller.pause() else controller.play()
        }
    }
}

class WidgetNextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        dispatchMediaAction(context) { controller ->
            controller.seekToNextMediaItem()
        }
    }
}

class WidgetPrevAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        dispatchMediaAction(context) { controller ->
            controller.seekToPreviousMediaItem()
        }
    }
}
