package com.mediadash.android.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title ASC")
    fun getAllPodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getPodcastById(id: String): PodcastEntity?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getPodcastByFeedUrl(feedUrl: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPodcast(podcast: PodcastEntity)

    @Update
    suspend fun updatePodcast(podcast: PodcastEntity)

    @Delete
    suspend fun deletePodcast(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcastById(id: String)

    @Query("UPDATE podcasts SET lastFetched = :timestamp WHERE id = :id")
    suspend fun updateLastFetched(id: String, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun getEpisodesForPodcast(podcastId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC LIMIT :limit")
    fun getEpisodesForPodcastLimited(podcastId: String, limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId")
    suspend fun getEpisodeCountForPodcast(podcastId: String): Int

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getEpisodeById(id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes ORDER BY pubDate DESC LIMIT :limit")
    fun getRecentEpisodes(limit: Int = 50): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE isDownloaded = 1 ORDER BY pubDate DESC")
    fun getDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE downloadState = :state ORDER BY pubDate DESC")
    fun getEpisodesByDownloadState(state: String): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: EpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Update
    suspend fun updateEpisode(episode: EpisodeEntity)

    @Query("UPDATE episodes SET isPlayed = :isPlayed WHERE id = :id")
    suspend fun markAsPlayed(id: String, isPlayed: Boolean = true)

    @Query("UPDATE episodes SET playPosition = :position WHERE id = :id")
    suspend fun updatePlayPosition(id: String, position: Long)

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress WHERE id = :id")
    suspend fun updateDownloadProgress(id: String, state: String, progress: Int)

    @Query("UPDATE episodes SET downloadState = :state, isDownloaded = :isDownloaded, localFilePath = :localPath, downloadedSize = :size, downloadProgress = 100 WHERE id = :id")
    suspend fun markAsDownloaded(id: String, state: String, isDownloaded: Boolean, localPath: String?, size: Long)

    @Query("UPDATE episodes SET downloadState = 'NOT_DOWNLOADED', isDownloaded = 0, localFilePath = NULL, downloadedSize = 0, downloadProgress = 0 WHERE id = :id")
    suspend fun clearDownload(id: String)

    @Query("DELETE FROM episodes WHERE podcastId = :podcastId")
    suspend fun deleteEpisodesForPodcast(podcastId: String)

    @Delete
    suspend fun deleteEpisode(episode: EpisodeEntity)
}
