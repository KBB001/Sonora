package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.YouTubeMusicAccountApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sonora", appName)
  }

  @Test
  fun `youtubeAccountApi parses profile tracks and playlists`() {
    val profileJson = JSONObject(
      """
      {
        "actions": [{
          "openPopupAction": {
            "popup": {
              "multiPageMenuRenderer": {
                "header": {
                  "activeAccountHeaderRenderer": {
                    "accountName": {"runs": [{"text": "Alex Music"}]},
                    "channelHandle": {"runs": [{"text": "@alexmusic"}]},
                    "email": {"runs": [{"text": "alex@example.com"}]},
                    "accountPhoto": {"thumbnails": [{"url": "https://img.example/a.jpg", "width": 120, "height": 120}]}
                  }
                }
              }
            }
          }
        }]
      }
      """.trimIndent()
    )
    val profile = YouTubeMusicAccountApi.parseAccountProfile(profileJson)
    assertEquals("Alex Music", profile?.name)
    assertEquals("@alexmusic", profile?.handle)
    assertEquals("alex@example.com", profile?.email)
    assertEquals("https://img.example/a.jpg", profile?.avatarUrl)

    val tracksJson = JSONObject(
      """
      {
        "contents": {
          "musicResponsiveListItemRenderer": {
            "playlistItemData": {"videoId": "dQw4w9WgXcQ"},
            "flexColumns": [
              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Test Song"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Test Artist"}, {"text": " • "}, {"text": "3:32"}]}}}
            ],
            "fixedColumns": [
              {"musicResponsiveListItemFixedColumnRenderer": {"text": {"runs": [{"text": "3:32"}]}}}
            ]
          }
        }
      }
      """.trimIndent()
    )
    val tracks = YouTubeMusicAccountApi.extractTracksRecursive(tracksJson)
    assertEquals(1, tracks.size)
    assertEquals("dQw4w9WgXcQ", tracks[0].id)
    assertEquals("Test Song", tracks[0].title)
    assertEquals("Test Artist", tracks[0].artist)
    assertEquals(212L, tracks[0].durationSeconds)

    val playlistsJson = JSONObject(
      """
      {
        "items": [{
          "musicTwoRowItemRenderer": {
            "title": {"runs": [{"text": "Road Trip"}]},
            "subtitle": {"runs": [{"text": "Playlist • 18 songs"}]},
            "navigationEndpoint": {"browseEndpoint": {"browseId": "VLPL1234567890"}}
          }
        }]
      }
      """.trimIndent()
    )
    val playlists = YouTubeMusicAccountApi.parseUserPlaylists(playlistsJson)
    assertEquals(1, playlists.size)
    assertEquals("PL1234567890", playlists[0].playlistId)
    assertEquals("Road Trip", playlists[0].title)
    assertEquals(18, playlists[0].trackCount)
  }
}
