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

    // Must ignore reloadContinuationData and only extract real pagination tokens
    val reloadOnlyJson = JSONObject(
      """
      {
        "reloadContinuationData": {"continuation": "BAD_RELOAD_TOKEN"}
      }
      """.trimIndent()
    )
    org.junit.Assert.assertNull(YouTubeMusicAccountApi.extractNextContinuation(reloadOnlyJson))

    val commandContJson = JSONObject(
      """
      {
        "continuationItemRenderer": {
          "continuationEndpoint": {
            "continuationCommand": {"token": "NEXT_PAGE_TOKEN_42"}
          }
        }
      }
      """.trimIndent()
    )
    assertEquals("NEXT_PAGE_TOKEN_42", YouTubeMusicAccountApi.extractNextContinuation(commandContJson))

    val webPlaylistJson = JSONObject(
      """
      {
        "items": [{
          "playlistVideoRenderer": {
            "videoId": "abc123XYZ00",
            "title": {"runs": [{"text": "Web Liked Song"}]},
            "shortBylineText": {"runs": [{"text": "Web Artist"}]},
            "lengthSeconds": "195"
          }
        }]
      }
      """.trimIndent()
    )
    val webTracks = YouTubeMusicAccountApi.extractTracksRecursive(webPlaylistJson)
    assertEquals(1, webTracks.size)
    assertEquals("abc123XYZ00", webTracks[0].id)
    assertEquals("Web Liked Song", webTracks[0].title)
    assertEquals("Web Artist", webTracks[0].artist)
    assertEquals(195L, webTracks[0].durationSeconds)
  }

  @Test
  fun `room migration 2 to 3 preserves playlists and validates schema`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_test_${System.nanoTime()}.db"
    val helper = object : android.database.sqlite.SQLiteOpenHelper(context, dbName, null, 2) {
      override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `history_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUrl` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `downloaded_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, `downloadedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")
        db.execSQL("INSERT INTO `playlists` (`id`, `name`, `description`, `coverUrl`, `createdAt`) VALUES (1, 'Saved Local', 'Desc', NULL, 1000)")
      }

      override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    helper.writableDatabase.close()
    helper.close()

    val migratedDb = androidx.room.Room.databaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java,
      dbName
    )
      .addMigrations(com.example.di.DatabaseModule.MIGRATION_2_3)
      .allowMainThreadQueries()
      .build()

    try {
      val playlist = migratedDb.playlistDao().findByNameIgnoreCase("Saved Local")
      assertEquals(1L, playlist?.id)
      org.junit.Assert.assertNull(playlist?.youtubePlaylistId)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `secureAccountCipher encrypts credentials at rest and store roundTrips safely`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cipher = com.example.data.repository.SecureAccountCipher(context)
    val secretCookie = "SID=secret_sid_value; SAPISID=secret_sapisid_value"

    val encrypted = cipher.encrypt(secretCookie)
    org.junit.Assert.assertTrue(cipher.isEncryptedPayload(encrypted))
    org.junit.Assert.assertFalse(encrypted.contains("secret_sid_value"))
    assertEquals(secretCookie, cipher.decrypt(encrypted))
    org.junit.Assert.assertNull(cipher.decrypt("enc:v1:invalid:tampered"))

    val store = com.example.data.repository.YouTubeAccountStore(context, cipher)
    val account = com.example.domain.model.YouTubeAccount(
      id = "yt_sec_test",
      name = "Security Test",
      cookieHeader = secretCookie
    )
    store.upsertAndActivateAccount(account)
    val stored = store.getAllAccountsSnapshot()
    assertEquals(1, stored.size)
    assertEquals(secretCookie, stored.first().cookieHeader)
    store.clearAllAccounts()
    assertEquals(0, store.getAllAccountsSnapshot().size)
  }

  @Test
  fun `authWebViewUrl filter restricts navigation to HTTPS Google and YouTube hosts`() {
    org.junit.Assert.assertTrue(
      com.example.ui.account.isAllowedAuthWebViewUrl("https://accounts.google.com/ServiceLogin")
    )
    org.junit.Assert.assertTrue(
      com.example.ui.account.isAllowedAuthWebViewUrl("https://music.youtube.com/")
    )
    org.junit.Assert.assertFalse(
      com.example.ui.account.isAllowedAuthWebViewUrl("http://music.youtube.com/")
    )
    org.junit.Assert.assertFalse(
      com.example.ui.account.isAllowedAuthWebViewUrl("file:///etc/passwd")
    )
    org.junit.Assert.assertFalse(
      com.example.ui.account.isAllowedAuthWebViewUrl("javascript:alert(1)")
    )
    org.junit.Assert.assertFalse(
      com.example.ui.account.isAllowedAuthWebViewUrl("https://evil.example.com/phish")
    )
  }
}
