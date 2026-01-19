package com.mediadash.android.data.repository

import com.mediadash.android.data.local.EpisodeDao
import com.mediadash.android.data.local.EpisodeEntity
import com.mediadash.android.data.local.PodcastDao
import com.mediadash.android.data.local.PodcastEntity
import com.mediadash.android.data.remote.ITunesApiService
import com.mediadash.android.data.remote.RssFeedParser
import com.mediadash.android.domain.model.Podcast
import com.mediadash.android.domain.model.PodcastEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface PodcastRepository {
    suspend fun searchPodcasts(query: String): Result<List<Podcast>>
    fun getSubscribedPodcasts(): Flow<List<Podcast>>
    suspend fun subscribeToPodcast(podcast: Podcast): Result<Unit>
    suspend fun subscribeToFeed(feedUrl: String): Result<Podcast>
    suspend fun unsubscribe(podcastId: String)
    fun getEpisodesForPodcast(podcastId: String): Flow<List<PodcastEpisode>>
    fun getEpisodesForPodcastLimited(podcastId: String, limit: Int): Flow<List<PodcastEpisode>>
    suspend fun getEpisodeCountForPodcast(podcastId: String): Int
    suspend fun refreshPodcast(podcastId: String): Result<Unit>
    suspend fun markEpisodeAsPlayed(episodeId: String, isPlayed: Boolean)
    suspend fun updatePlayPosition(episodeId: String, position: Long)
    fun getDownloadedEpisodes(): Flow<List<PodcastEpisode>>
    suspend fun getEpisodeById(episodeId: String): PodcastEpisode?
    suspend fun isFeedSubscribed(feedUrl: String): Boolean
}

@Singleton
class PodcastRepositoryImpl @Inject constructor(
    private val iTunesApiService: ITunesApiService,
    private val rssFeedParser: RssFeedParser,
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao
) : PodcastRepository {

    override suspend fun searchPodcasts(query: String): Result<List<Podcast>> {
        return withContext(Dispatchers.IO) {
            try {
                val response = iTunesApiService.searchPodcasts(query)
                val podcasts = response.results.mapNotNull { it.toPodcast() }
                Result.success(podcasts)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun getSubscribedPodcasts(): Flow<List<Podcast>> {
        return podcastDao.getAllPodcasts().map { entities ->
            entities.map { it.toPodcast(isSubscribed = true) }
        }
    }

    override suspend fun subscribeToPodcast(podcast: Podcast): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val parsedFeed = rssFeedParser.parseFeed(podcast.feedUrl, podcast.id)
                    ?: return@withContext Result.failure(Exception("Failed to parse feed"))

                val updatedPodcast = podcast.copy(
                    description = parsedFeed.podcast.description,
                    artworkUrl = parsedFeed.podcast.artworkUrl.ifBlank { podcast.artworkUrl },
                    episodeCount = parsedFeed.episodes.size
                )

                podcastDao.insertPodcast(PodcastEntity.fromPodcast(updatedPodcast))
                episodeDao.insertEpisodes(parsedFeed.episodes.map { EpisodeEntity.fromEpisode(it) })

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun subscribeToFeed(feedUrl: String): Result<Podcast> {
        return withContext(Dispatchers.IO) {
            try {
                val existingPodcast = podcastDao.getPodcastByFeedUrl(feedUrl)
                if (existingPodcast != null) {
                    return@withContext Result.failure(Exception("Already subscribed to this podcast"))
                }

                val parsedFeed = rssFeedParser.parseFeed(feedUrl)
                    ?: return@withContext Result.failure(Exception("Failed to parse RSS feed. Please check the URL."))

                podcastDao.insertPodcast(PodcastEntity.fromPodcast(parsedFeed.podcast))
                episodeDao.insertEpisodes(parsedFeed.episodes.map { EpisodeEntity.fromEpisode(it) })

                Result.success(parsedFeed.podcast.copy(isSubscribed = true))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun unsubscribe(podcastId: String) {
        withContext(Dispatchers.IO) {
            episodeDao.deleteEpisodesForPodcast(podcastId)
            podcastDao.deletePodcastById(podcastId)
        }
    }

    override fun getEpisodesForPodcast(podcastId: String): Flow<List<PodcastEpisode>> {
        return episodeDao.getEpisodesForPodcast(podcastId).map { entities ->
            entities.map { it.toEpisode() }
        }
    }

    override fun getEpisodesForPodcastLimited(podcastId: String, limit: Int): Flow<List<PodcastEpisode>> {
        return episodeDao.getEpisodesForPodcastLimited(podcastId, limit).map { entities ->
            entities.map { it.toEpisode() }
        }
    }

    override suspend fun getEpisodeCountForPodcast(podcastId: String): Int {
        return withContext(Dispatchers.IO) {
            episodeDao.getEpisodeCountForPodcast(podcastId)
        }
    }

    override suspend fun isFeedSubscribed(feedUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            podcastDao.getPodcastByFeedUrl(feedUrl) != null
        }
    }

    override suspend fun refreshPodcast(podcastId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val podcast = podcastDao.getPodcastById(podcastId)
                    ?: return@withContext Result.failure(Exception("Podcast not found"))

                val parsedFeed = rssFeedParser.parseFeed(podcast.feedUrl, podcastId)
                    ?: return@withContext Result.failure(Exception("Failed to refresh feed"))

                val updatedEntity = podcast.copy(
                    title = parsedFeed.podcast.title,
                    author = parsedFeed.podcast.author,
                    description = parsedFeed.podcast.description,
                    artworkUrl = parsedFeed.podcast.artworkUrl.ifBlank { podcast.artworkUrl },
                    episodeCount = parsedFeed.episodes.size,
                    lastFetched = System.currentTimeMillis()
                )

                podcastDao.updatePodcast(updatedEntity)
                episodeDao.insertEpisodes(parsedFeed.episodes.map { EpisodeEntity.fromEpisode(it) })

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun markEpisodeAsPlayed(episodeId: String, isPlayed: Boolean) {
        withContext(Dispatchers.IO) {
            episodeDao.markAsPlayed(episodeId, isPlayed)
        }
    }

    override suspend fun updatePlayPosition(episodeId: String, position: Long) {
        withContext(Dispatchers.IO) {
            episodeDao.updatePlayPosition(episodeId, position)
        }
    }

    override fun getDownloadedEpisodes(): Flow<List<PodcastEpisode>> {
        return episodeDao.getDownloadedEpisodes().map { entities ->
            entities.map { it.toEpisode() }
        }
    }

    override suspend fun getEpisodeById(episodeId: String): PodcastEpisode? {
        return withContext(Dispatchers.IO) {
            episodeDao.getEpisodeById(episodeId)?.toEpisode()
        }
    }
}
