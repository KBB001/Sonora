package com.example.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.SonoraDatabase
import com.example.data.local.dao.DownloadedTrackDao
import com.example.data.local.dao.FavoriteDao
import com.example.data.local.dao.HistoryDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.dao.SearchHistoryDao
import com.example.data.local.dao.TrackSignalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloaded_tracks` (" +
                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, " +
                    "`durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT NOT NULL, " +
                    "`localFilePath` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, " +
                    "`downloadedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `search_history` (" +
                    "`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))"
            )
        }
    }

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            var hasYoutubePlaylistId = false
            db.query("PRAGMA table_info(`playlists`)").use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameCol >= 0 && cursor.getString(nameCol) == "youtubePlaylistId") {
                        hasYoutubePlaylistId = true
                        break
                    }
                }
            }
            if (!hasYoutubePlaylistId) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN youtubePlaylistId TEXT")
            }
        }
    }

    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `track_affinities` (" +
                    "`trackId` TEXT NOT NULL, `normalizedArtist` TEXT NOT NULL, " +
                    "`moodName` TEXT NOT NULL, `tempoName` TEXT NOT NULL, " +
                    "`completedCount` INTEGER NOT NULL, `repeatCount` INTEGER NOT NULL, " +
                    "`quickSkipCount` INTEGER NOT NULL, `partialSkipCount` INTEGER NOT NULL, " +
                    "`affinityScore` REAL NOT NULL, `lastInteractedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`trackId`))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_track_affinities_normalizedArtist` " +
                    "ON `track_affinities` (`normalizedArtist`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_track_affinities_moodName` " +
                    "ON `track_affinities` (`moodName`)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonoraDatabase =
        Room.databaseBuilder(
            context,
            SonoraDatabase::class.java,
            "sonora_music.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideFavoriteDao(database: SonoraDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideHistoryDao(database: SonoraDatabase): HistoryDao = database.historyDao()

    @Provides
    fun providePlaylistDao(database: SonoraDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideDownloadedTrackDao(database: SonoraDatabase): DownloadedTrackDao =
        database.downloadedTrackDao()

    @Provides
    fun provideSearchHistoryDao(database: SonoraDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Provides
    fun provideTrackSignalDao(database: SonoraDatabase): TrackSignalDao =
        database.trackSignalDao()
}
