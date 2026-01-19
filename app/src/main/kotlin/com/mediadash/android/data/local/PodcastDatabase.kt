package com.mediadash.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PodcastDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao

    companion object {
        const val DATABASE_NAME = "podcast_database"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new download-related columns to episodes table
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadState TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadProgress INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN localFilePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadedSize INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
