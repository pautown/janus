package com.mediadash.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Podcast(
    val id: String,
    val title: String,
    val author: String,
    val description: String = "",
    val artworkUrl: String = "",
    val feedUrl: String,
    val genre: String = "",
    val episodeCount: Int = 0,
    val isSubscribed: Boolean = false
)

enum class DownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

@Serializable
data class PodcastEpisode(
    val id: String,
    val podcastId: String,
    val title: String,
    val description: String = "",
    val audioUrl: String,
    val artworkUrl: String? = null,
    val duration: Long = 0L,
    val pubDate: Long = 0L,
    val pubDateFormatted: String = "",
    val isPlayed: Boolean = false,
    val playPosition: Long = 0L,
    val isDownloaded: Boolean = false,
    val downloadState: DownloadState = DownloadState.NOT_DOWNLOADED,
    val downloadProgress: Int = 0,
    val localFilePath: String? = null,
    val downloadedSize: Long = 0L
)

@Serializable
data class ITunesSearchResponse(
    val resultCount: Int,
    val results: List<ITunesPodcast>
)

@Serializable
data class ITunesPodcast(
    val trackId: Long,
    val collectionId: Long? = null,
    val trackName: String,
    val collectionName: String? = null,
    val artistName: String,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
    val primaryGenreName: String? = null,
    val genres: List<String> = emptyList(),
    val trackCount: Int = 0,
    val releaseDate: String? = null,
    val collectionExplicitness: String? = null
) {
    fun toPodcast(): Podcast? {
        val url = feedUrl ?: return null
        return Podcast(
            id = trackId.toString(),
            title = trackName,
            author = artistName,
            description = "",
            artworkUrl = artworkUrl600 ?: artworkUrl100 ?: "",
            feedUrl = url,
            genre = primaryGenreName ?: genres.firstOrNull() ?: "",
            episodeCount = trackCount
        )
    }
}
