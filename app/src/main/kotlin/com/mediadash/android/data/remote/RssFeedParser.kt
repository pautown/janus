package com.mediadash.android.data.remote

import com.mediadash.android.domain.model.Podcast
import com.mediadash.android.domain.model.PodcastEpisode
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.RssParserBuilder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RssFeedParser @Inject constructor() {
    private val rssParser: RssParser = RssParserBuilder().build()

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
    )

    suspend fun parseFeed(feedUrl: String, podcastId: String? = null): ParsedFeed? {
        return try {
            val channel = rssParser.getRssChannel(feedUrl)

            val id = podcastId ?: UUID.randomUUID().toString()

            val podcast = Podcast(
                id = id,
                title = channel.title ?: "Unknown Podcast",
                author = channel.itunesChannelData?.author ?: channel.itunesChannelData?.owner?.name ?: "",
                description = channel.description ?: channel.itunesChannelData?.summary ?: "",
                artworkUrl = channel.itunesChannelData?.image ?: channel.image?.url ?: "",
                feedUrl = feedUrl,
                genre = channel.itunesChannelData?.categories?.firstOrNull() ?: "",
                episodeCount = channel.items.size
            )

            val episodes = channel.items.mapNotNull { item ->
                val audioUrl = item.audio ?: return@mapNotNull null

                PodcastEpisode(
                    id = item.guid ?: item.link ?: UUID.randomUUID().toString(),
                    podcastId = id,
                    title = item.title ?: "Untitled Episode",
                    description = item.description ?: item.itunesItemData?.summary ?: "",
                    audioUrl = audioUrl,
                    artworkUrl = item.itunesItemData?.image,
                    duration = parseDuration(item.itunesItemData?.duration),
                    pubDate = parseDate(item.pubDate),
                    pubDateFormatted = formatDate(item.pubDate)
                )
            }

            ParsedFeed(podcast, episodes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseDuration(duration: String?): Long {
        if (duration.isNullOrBlank()) return 0L

        return try {
            when {
                duration.contains(":") -> {
                    val parts = duration.split(":").map { it.toIntOrNull() ?: 0 }
                    when (parts.size) {
                        2 -> (parts[0] * 60 + parts[1]).toLong()
                        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toLong()
                        else -> 0L
                    }
                }
                else -> duration.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0L

        for (format in dateFormats) {
            try {
                return format.parse(dateString)?.time ?: continue
            } catch (e: Exception) {
                continue
            }
        }
        return 0L
    }

    private fun formatDate(dateString: String?): String {
        if (dateString.isNullOrBlank()) return ""

        val timestamp = parseDate(dateString)
        if (timestamp == 0L) return dateString

        return try {
            val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            outputFormat.format(timestamp)
        } catch (e: Exception) {
            dateString
        }
    }
}

data class ParsedFeed(
    val podcast: Podcast,
    val episodes: List<PodcastEpisode>
)
