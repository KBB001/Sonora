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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN youtubePlaylistId TEXT")
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
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true)
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
}
