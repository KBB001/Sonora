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
      .addMigrations(
        com.example.di.DatabaseModule.MIGRATION_2_3,
        com.example.di.DatabaseModule.MIGRATION_3_4
      )
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

  @Test
  fun `playlistDao updatePlaylistDetails updates name and description and home mood query maps`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val id = db.playlistDao().insertPlaylist(
        com.example.data.local.entity.PlaylistEntity(
          name = "Old Title",
          description = "Old Desc"
        )
      )
      db.playlistDao().updatePlaylistDetails(id, "New Mix", "Updated Description")
      val updated = db.playlistDao().findByNameIgnoreCase("New Mix")
      assertEquals("New Mix", updated?.name)
      assertEquals("Updated Description", updated?.description)
      assertEquals("Lo-Fi Beats chillhop music", com.example.ui.home.HomeUiState.toSearchQuery("Lo-Fi биты"))
    } finally {
      db.close()
    }
  }

  @Test
  fun `room migration 1 to 3 preserves all existing tables without destructive wipe`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v1_v3_${System.nanoTime()}.db"
    val helper = object : android.database.sqlite.SQLiteOpenHelper(context, dbName, null, 1) {
      override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `history_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUrl` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
        db.execSQL("INSERT INTO `favorite_tracks` VALUES ('fav_1', 'Fav Song', 'Artist A', 200, 'https://img.example/1.jpg', 1000)")
        db.execSQL("INSERT INTO `playlists` (`id`, `name`, `description`, `coverUrl`, `createdAt`) VALUES (7, 'Legacy Mix', 'V1 Desc', NULL, 2000)")
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
      .addMigrations(
        com.example.di.DatabaseModule.MIGRATION_1_2,
        com.example.di.DatabaseModule.MIGRATION_2_3,
        com.example.di.DatabaseModule.MIGRATION_3_4
      )
      .allowMainThreadQueries()
      .build()

    try {
      org.junit.Assert.assertTrue(migratedDb.favoriteDao().isFavorite("fav_1"))
      val playlist = migratedDb.playlistDao().findByNameIgnoreCase("Legacy Mix")
      assertEquals(7L, playlist?.id)
      migratedDb.searchHistoryDao().upsertQuery(
        com.example.data.local.entity.SearchHistoryEntity(query = "Ambient")
      )
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `libraryBackup v2 export and idempotent import preserves playlists history search and supports v1`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val repo = com.example.data.repository.LibraryRepositoryImpl(
        favoriteDao = db.favoriteDao(),
        historyDao = db.historyDao(),
        playlistDao = db.playlistDao(),
        downloadedTrackDao = db.downloadedTrackDao(),
        searchHistoryDao = db.searchHistoryDao(),
        context = context
      )

      val t1 = com.example.domain.model.Track(
        id = "trk_1",
        title = "Night Drive",
        artist = "Synth",
        durationSeconds = 210,
        thumbnailUrl = "https://img.example/t1.jpg",
        streamUrl = "https://ephemeral.stream/audio"
      )
      repo.toggleFavorite(t1)
      repo.recordPlayback(t1)
      repo.recordSearchQuery("Synthwave")
      repo.createPlaylist("Roadtrip", "Late night", listOf(t1), youtubePlaylistId = "PL_REMOTE_1")

      val json = repo.exportBackupJson()
      org.junit.Assert.assertFalse(json.contains("ephemeral.stream"))
      org.junit.Assert.assertTrue(json.contains("Synthwave"))
      org.junit.Assert.assertTrue(json.contains("PL_REMOTE_1"))

      // Importing same backup twice must remain idempotent with zero duplicate playlists
      val firstImport = repo.importBackupJson(json).getOrThrow()
      val secondImport = repo.importBackupJson(json).getOrThrow()
      assertEquals(0, firstImport)
      assertEquals(0, secondImport)
      assertEquals(1, repo.getAllPlaylistsWithTracks().size)

      // Backwards compatibility: v1 JSON import restores favorites, playlists, and strips http thumbnails
      val v1Json = """
        {
          "version": 1,
          "favorites": [
            {"id": "v1_fav", "title": "Old Song", "artist": "Old Artist", "durationSeconds": 180, "thumbnailUrl": "http://insecure.example/x.jpg"}
          ],
          "playlists": [
            {"name": "V1 Playlist", "description": "Restored", "tracks": [{"id": "v1_fav", "title": "Old Song", "artist": "Old Artist", "durationSeconds": 180, "thumbnailUrl": "https://safe.example/x.jpg"}]}
          ]
        }
      """.trimIndent()
      val v1Result = repo.importBackupJson(v1Json)
      org.junit.Assert.assertTrue(v1Result.isSuccess)
      assertEquals(2, repo.getAllPlaylistsWithTracks().size)
      val playlists = repo.getAllPlaylistsWithTracks().map { it.first.name }.toSet()
      org.junit.Assert.assertTrue("V1 Playlist" in playlists)
    } finally {
      db.close()
    }
  }

  @Test
  fun `playbackPersistenceStore roundTrips queue position speed and equalizer config safely`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = com.example.data.repository.PlaybackPersistenceStore(context)
    val track = com.example.domain.model.Track(
      id = "q_1",
      title = "Focus Track",
      artist = "Artist",
      durationSeconds = 240,
      thumbnailUrl = "https://img.example/q1.jpg",
      streamUrl = "https://expires.example/stream"
    )
    store.savePlaybackState(
      com.example.domain.model.PersistedPlaybackState(
        queue = listOf(track),
        currentIndex = 0,
        positionMs = 42_000L,
        shuffleEnabled = true,
        repeatMode = com.example.domain.model.RepeatMode.ALL,
        playbackSpeed = 1.25f
      )
    )
    val restoredPlayback = store.readPlaybackState()!!
    assertEquals(1, restoredPlayback.queue.size)
    org.junit.Assert.assertNull(restoredPlayback.queue.first().streamUrl)
    assertEquals(42_000L, restoredPlayback.positionMs)
    assertEquals(true, restoredPlayback.shuffleEnabled)
    assertEquals(com.example.domain.model.RepeatMode.ALL, restoredPlayback.repeatMode)
    assertEquals(1.25f, restoredPlayback.playbackSpeed)

    store.saveEqualizerState(
      com.example.domain.model.PersistedEqualizerState(
        isEnabled = true,
        selectedPresetIndex = 2,
        bandLevels = listOf(300, 100, 0, 200, 400),
        customBandsApplied = true
      )
    )
    val restoredEq = store.readEqualizerState()!!
    assertEquals(true, restoredEq.isEnabled)
    assertEquals(2, restoredEq.selectedPresetIndex)
    assertEquals(listOf<Short>(300, 100, 0, 200, 400), restoredEq.bandLevels)
    assertEquals(true, restoredEq.customBandsApplied)
  }

  @Test
  fun `downloaded offline file lookup recovers relocated file inside offline_tracks`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()
    val offlineDir = java.io.File(context.filesDir, "offline_tracks").apply { mkdirs() }
    val actualFile = java.io.File(offlineDir, "trk_offline_1.m4a")
    actualFile.writeBytes(byteArrayOf(1, 2, 3, 4))

    try {
      val repo = com.example.data.repository.LibraryRepositoryImpl(
        favoriteDao = db.favoriteDao(),
        historyDao = db.historyDao(),
        playlistDao = db.playlistDao(),
        downloadedTrackDao = db.downloadedTrackDao(),
        searchHistoryDao = db.searchHistoryDao(),
        context = context
      )
      val track = com.example.domain.model.Track(
        id = "trk_offline_1",
        title = "Offline Song",
        artist = "Offline Artist",
        durationSeconds = 180,
        thumbnailUrl = "https://img.example/off.jpg"
      )
      // Simulate old sandbox prefix path stored before OS profile/update migration
      repo.saveDownloadedTrack(
        track = track,
        filePath = "/data/user/999/com.example/files/offline_tracks/trk_offline_1.m4a",
        fileSizeBytes = 4L
      )
      val resolvedPath = repo.getDownloadedFilePath("trk_offline_1")
      assertEquals(actualFile.absolutePath, resolvedPath)
    } finally {
      actualFile.delete()
      db.close()
    }
  }

  @Test
  fun `apkInstaller rejects missing zero-byte and corrupted apk archives`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val updatesDir = java.io.File(context.cacheDir, "test_apk_${System.nanoTime()}").apply { mkdirs() }
    val missingFile = java.io.File(updatesDir, "missing.apk")
    val emptyFile = java.io.File(updatesDir, "empty.apk").apply { createNewFile() }
    val corruptFile = java.io.File(updatesDir, "corrupt.apk").apply { writeText("not a valid apk zip") }

    try {
      assertEquals(
        com.example.core.common.ApkInstallCompatibility.InvalidArchive,
        com.example.core.common.ApkInstaller.checkInstallCompatibility(context, missingFile)
      )
      assertEquals(
        com.example.core.common.ApkInstallCompatibility.InvalidArchive,
        com.example.core.common.ApkInstaller.checkInstallCompatibility(context, emptyFile)
      )
      assertEquals(
        com.example.core.common.ApkInstallCompatibility.InvalidArchive,
        com.example.core.common.ApkInstaller.checkInstallCompatibility(context, corruptFile)
      )
    } finally {
      updatesDir.deleteRecursively()
    }
  }

  @Test
  fun `youtubeAccountApi normalizes thumbnails rejects http and ignores JSON null literals`() {
    val thumbs = org.json.JSONArray(
      """
      [
        {"url": "http://insecure.example/large.jpg", "width": 800, "height": 800},
        {"url": "//lh3.googleusercontent.com/small.jpg", "width": 120, "height": 120},
        {"url": "//lh3.googleusercontent.com/medium.jpg", "width": 320, "height": 320}
      ]
      """.trimIndent()
    )
    assertEquals(
      "https://lh3.googleusercontent.com/medium.jpg",
      YouTubeMusicAccountApi.pickLargestThumbnailUrl(thumbs)
    )

    val profileWithNulls = JSONObject(
      """
      {
        "activeAccountHeaderRenderer": {
          "accountName": {"runs": [{"text": "Null Safe User"}]},
          "channelHandle": {"simpleText": null},
          "email": {"simpleText": "null"},
          "accountPhoto": {"thumbnails": [{"url": "//yt3.ggpht.com/avatar.jpg", "width": 64, "height": 64}]}
        }
      }
      """.trimIndent()
    )
    val parsed = YouTubeMusicAccountApi.parseAccountProfile(profileWithNulls)
    assertEquals("Null Safe User", parsed?.name)
    assertEquals("", parsed?.handle)
    assertEquals("", parsed?.email)
    assertEquals("https://yt3.ggpht.com/avatar.jpg", parsed?.avatarUrl)
  }

  @Test
  fun `mergePlaylist matches Cyrillic playlist names case-insensitively without duplicates`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val repo = com.example.data.repository.LibraryRepositoryImpl(
        favoriteDao = db.favoriteDao(),
        historyDao = db.historyDao(),
        playlistDao = db.playlistDao(),
        downloadedTrackDao = db.downloadedTrackDao(),
        searchHistoryDao = db.searchHistoryDao(),
        context = context
      )
      val t1 = com.example.domain.model.Track("cy_1", "Песня 1", "Группа А", 180, "")
      val t2 = com.example.domain.model.Track("cy_2", "Песня 2", "Группа Б", 200, "")

      val id1 = repo.createPlaylist("Рок-н-ролл", "Первый", listOf(t1))
      val (mergedPlaylistId, addedCount) = repo.mergePlaylist(
        name = "рок-н-ролл",
        description = "Дубликат",
        tracks = listOf(t2)
      )

      assertEquals(id1, mergedPlaylistId)
      assertEquals(1, addedCount)
      val all = repo.getAllPlaylistsWithTracks()
      assertEquals(1, all.size)
      assertEquals(2, all.first().second.size)
    } finally {
      db.close()
    }
  }

  @Test
  fun `playbackPersistenceStore sanitizes blank track fields to Russian defaults`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = com.example.data.repository.PlaybackPersistenceStore(context)
    val blankTrack = com.example.domain.model.Track(
      id = "blank_1",
      title = "   ",
      artist = "",
      durationSeconds = -10,
      thumbnailUrl = "  "
    )
    store.savePlaybackState(
      com.example.domain.model.PersistedPlaybackState(
        queue = listOf(blankTrack),
        currentIndex = 5,
        positionMs = -500L,
        playbackSpeed = 5.0f
      )
    )
    val restored = store.readPlaybackState()!!
    assertEquals(1, restored.queue.size)
    assertEquals("Без названия", restored.queue.first().title)
    assertEquals("Неизвестный исполнитель", restored.queue.first().artist)
    assertEquals(0L, restored.queue.first().durationSeconds)
    assertEquals(0, restored.currentIndex)
    assertEquals(0L, restored.positionMs)
    assertEquals(2.0f, restored.playbackSpeed)
  }

  @Test
  fun `equalizerManager release resets availability flag`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = com.example.data.repository.PlaybackPersistenceStore(context)
    val manager = com.example.playback.SonoraEqualizerManager(store)
    manager.release()
    org.junit.Assert.assertFalse(manager.state.value.isAvailable)
  }

  @Test
  fun `appUpdateRepository clearCachedUpdates removes staged apk files`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val updatesDir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
    val staleApk = java.io.File(updatesDir, "sonora-v1.0.10.apk").apply { writeText("temp") }
    org.junit.Assert.assertTrue(staleApk.exists())

    val repo = com.example.data.remote.AppUpdateRepository(
      context = context,
      okHttpClient = okhttp3.OkHttpClient(),
      networkMonitor = com.example.core.common.NetworkMonitor(context)
    )
    repo.clearCachedUpdates()

    org.junit.Assert.assertFalse(staleApk.exists())
    val mismatch = com.example.core.common.ApkInstallCompatibility.SignatureMismatch(
      installedCertFingerprint = "AA:BB:CC:DD:EE:FF",
      archiveCertFingerprint = "CF:32:4B:E4:39:93"
    )
    assertEquals("AA:BB:CC:DD:EE:FF", mismatch.installedCertFingerprint)
    assertEquals("CF:32:4B:E4:39:93", mismatch.archiveCertFingerprint)
  }

  @Test
  fun `room migration 3 to 4 preserves favorites playlists and adds track_affinities table`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v3_v4_${System.nanoTime()}.db"
    val helper = object : android.database.sqlite.SQLiteOpenHelper(context, dbName, null, 3) {
      override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `favorite_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `history_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `playedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `coverUrl` TEXT, `createdAt` INTEGER NOT NULL, `youtubePlaylistId` TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_tracks` (`playlistId` INTEGER NOT NULL, `trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`playlistId`, `trackId`), FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `downloaded_tracks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, `localFilePath` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, `downloadedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")
        db.execSQL("INSERT INTO `favorite_tracks` VALUES ('fav_v3', 'Keep Me', 'Artist V3', 215, 'https://img.example/v3.jpg', 5000)")
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
      .addMigrations(com.example.di.DatabaseModule.MIGRATION_3_4)
      .allowMainThreadQueries()
      .build()

    try {
      org.junit.Assert.assertTrue(migratedDb.favoriteDao().isFavorite("fav_v3"))
      migratedDb.trackSignalDao().upsertAffinity(
        com.example.data.local.entity.TrackAffinityEntity(
          trackId = "fav_v3",
          normalizedArtist = "artist v3",
          moodName = com.example.domain.model.MoodCategory.GENERAL.name,
          tempoName = com.example.domain.model.TempoTier.MEDIUM.name,
          completedCount = 2,
          affinityScore = 3.0f
        )
      )
      val loaded = migratedDb.trackSignalDao().getAffinity("fav_v3")
      assertEquals(2, loaded?.completedCount)
      assertEquals(3.0f, loaded?.affinityScore ?: 0f, 0.01f)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `tasteSignalTracker adapts preferences from repeats completions and quick skips`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val profiler = com.example.domain.recommendation.TrackMoodProfiler()
      val tracker = com.example.domain.recommendation.TasteSignalTracker(db.trackSignalDao(), profiler)

      val loved = com.example.domain.model.Track(
        id = "love_1",
        title = "Ambient Piano Focus",
        artist = "CalmMaker",
        durationSeconds = 220,
        thumbnailUrl = ""
      )
      val skipped = com.example.domain.model.Track(
        id = "skip_1",
        title = "Hardstyle Phonk Bass",
        artist = "LoudMaker",
        durationSeconds = 150,
        thumbnailUrl = ""
      )

      tracker.recordSignal(loved, com.example.domain.model.ImplicitSignalType.COMPLETE_PLAY)
      tracker.recordSignal(loved, com.example.domain.model.ImplicitSignalType.REPEAT_PLAY)
      tracker.recordSignal(skipped, com.example.domain.model.ImplicitSignalType.QUICK_SKIP)
      tracker.recordSignal(skipped, com.example.domain.model.ImplicitSignalType.QUICK_SKIP)

      val lovedBonus = tracker.getPersonalAffinityBonus(loved)
      val skippedBonus = tracker.getPersonalAffinityBonus(skipped)
      org.junit.Assert.assertTrue("Loved bonus should be positive: $lovedBonus", lovedBonus > 0f)
      org.junit.Assert.assertTrue("Skipped bonus should be negative: $skippedBonus", skippedBonus < 0f)

      val persisted = db.trackSignalDao().getAffinity("love_1")
      assertEquals(1, persisted?.completedCount)
      assertEquals(1, persisted?.repeatCount)
    } finally {
      db.close()
    }
  }

  @Test
  fun `smartRecommendationEngine deduplicates per session allows same artist and softly shifts skipped moods`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.SonoraDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val profiler = com.example.domain.recommendation.TrackMoodProfiler()
      val tracker = com.example.domain.recommendation.TasteSignalTracker(db.trackSignalDao(), profiler)
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
      val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
      val shadowCaps = org.robolectric.Shadows.shadowOf(caps)
      shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

      val networkMonitor = com.example.core.common.NetworkMonitor(context)
      val seed = com.example.domain.model.Track("seed", "Night Lofi Study", "Studio A", 200, "")
      val phonk1 = com.example.domain.model.Track("ph1", "Drift Phonk Gym", "Artist P", 140, "")
      val phonk2 = com.example.domain.model.Track("ph2", "Hardstyle Bass Workout", "Artist P", 145, "")
      val acoustic1 = com.example.domain.model.Track("ac1", "Warm Acoustic Guitar", "Studio A", 210, "")
      val chill1 = com.example.domain.model.Track("ch1", "Soft Chillhop Beats", "Studio A", 205, "")

      val fakeRepo = object : com.example.domain.repository.YouTubeRepository {
        override suspend fun searchTracks(query: String): Result<List<com.example.domain.model.Track>> =
          Result.success(listOf(chill1, acoustic1))

        override suspend fun searchPlaylists(query: String): Result<List<com.example.domain.model.YouTubePlaylistSummary>> =
          Result.success(emptyList())

        override suspend fun searchArtists(query: String): Result<List<com.example.domain.model.YouTubeArtistSummary>> =
          Result.success(emptyList())

        override suspend fun getArtistTracks(channelUrl: String): Result<List<com.example.domain.model.Track>> =
          Result.success(emptyList())

        override suspend fun getSearchSuggestions(query: String): List<String> = emptyList()

        override suspend fun getAudioStreamUrl(videoId: String, audioQuality: com.example.domain.model.AudioQuality, forceRefresh: Boolean) =
          Result.success("https://example.com/$videoId.m4a")

        override suspend fun getRelatedTracks(videoId: String): Result<List<com.example.domain.model.Track>> =
          Result.success(listOf(phonk1, phonk2, chill1, acoustic1))

        override suspend fun getPlaylistTracks(playlistUrl: String): Result<List<com.example.domain.model.Track>> =
          Result.success(emptyList())

        override suspend fun getPlaylistWithTitle(playlistUrl: String): Result<Pair<String, List<com.example.domain.model.Track>>> =
          Result.success("" to emptyList())
      }

      val engine = com.example.domain.recommendation.SmartRecommendationEngine(
        moodProfiler = profiler,
        tasteSignalTracker = tracker,
        youTubeRepository = fakeRepo,
        networkMonitor = networkMonitor
      )

      // Session dedup excludes already-heard track without capping same artist
      engine.markHeardInSession(phonk1.id)
      val generated = engine.generateNextQueueTracks(seedTrack = seed, targetCount = 10)
      org.junit.Assert.assertFalse(generated.any { it.id == phonk1.id })
      // Both Studio A tracks (chill1, acoustic1) can appear together with no hard artist streak block
      assertEquals(2, generated.count { it.artist == "Studio A" })

      // Soft real-time queue re-ranking keeps ALL existing upcoming tracks (no wipe) while shifting skipped mood down
      val existingUpcoming = listOf(phonk1, phonk2, acoustic1, chill1)
      val reranked = engine.softRerankUpcoming(
        upcomingTracks = existingUpcoming,
        seedTrack = seed,
        recentSkippedTracks = listOf(phonk1, phonk2)
      )
      assertEquals(existingUpcoming.size, reranked.size)
      org.junit.Assert.assertTrue(reranked.first().id in setOf(acoustic1.id, chill1.id))
    } finally {
      db.close()
    }
  }
}
