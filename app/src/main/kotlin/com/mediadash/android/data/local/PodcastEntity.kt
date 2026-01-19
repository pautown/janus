package com.mediadash.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mediadash.android.domain.model.DownloadState
import com.mediadash.android.domain.model.Podcast
import com.mediadash.android.domain.model.PodcastEpisode

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val feedUrl: String,
    val genre: String,
    val episodeCount: Int,
    val lastFetched: Long = System.currentTimeMillis()
) {
    fun toPodcast(isSubscribed: Boolean = true): Podcast {
        return Podcast(
            id = id,
            title = title,
            author = author,
            description = description,
            artworkUrl = artworkUrl,
            feedUrl = feedUrl,
            genre = genre,
            episodeCount = episodeCount,
            isSubscribed = isSubscribed
        )
    }

    companion object {
        fun fromPodcast(podcast: Podcast): PodcastEntity {
            return PodcastEntity(
                id = podcast.id,
                title = podcast.title,
                author = podcast.author,
                description = podcast.description,
                artworkUrl = podcast.artworkUrl,
                feedUrl = podcast.feedUrl,
                genre = podcast.genre,
                episodeCount = podcast.episodeCount
            )
        }
    }
}

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val duration: Long,
    val pubDate: Long,
    val pubDateFormatted: String,
    val isPlayed: Boolean = false,
    val playPosition: Long = 0L,
    val isDownloaded: Boolean = false,
    val downloadState: String = DownloadState.NOT_DOWNLOADED.name,
    val downloadProgress: Int = 0,
    val localFilePath: String? = null,
    val downloadedSize: Long = 0L
) {
    fun toEpisode(): PodcastEpisode {
        return PodcastEpisode(
            id = id,
            podcastId = podcastId,
            title = title,
            description = description,
            audioUrl = audioUrl,
            artworkUrl = artworkUrl,
            duration = duration,
            pubDate = pubDate,
            pubDateFormatted = pubDateFormatted,
            isPlayed = isPlayed,
            playPosition = playPosition,
            isDownloaded = isDownloaded,
            downloadState = try { DownloadState.valueOf(downloadState) } catch (e: Exception) { DownloadState.NOT_DOWNLOADED },
            downloadProgress = downloadProgress,
            localFilePath = localFilePath,
            downloadedSize = downloadedSize
        )
    }

    companion object {
        fun fromEpisode(episode: PodcastEpisode): EpisodeEntity {
            return EpisodeEntity(
                id = episode.id,
                podcastId = episode.podcastId,
                title = episode.title,
                description = episode.description,
                audioUrl = episode.audioUrl,
                artworkUrl = episode.artworkUrl,
                duration = episode.duration,
                pubDate = episode.pubDate,
                pubDateFormatted = episode.pubDateFormatted,
                isPlayed = episode.isPlayed,
                playPosition = episode.playPosition,
                isDownloaded = episode.isDownloaded,
                downloadState = episode.downloadState.name,
                downloadProgress = episode.downloadProgress,
                localFilePath = episode.localFilePath,
                downloadedSize = episode.downloadedSize
            )
        }
    }
}
