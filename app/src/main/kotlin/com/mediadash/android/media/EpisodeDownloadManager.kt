package com.mediadash.android.media

import android.content.Context
import com.mediadash.android.data.local.EpisodeDao
import com.mediadash.android.domain.model.DownloadState
import com.mediadash.android.domain.model.PodcastEpisode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val episodeId: String,
    val progress: Int,
    val state: DownloadState,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L
)

@Singleton
class EpisodeDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeDao: EpisodeDao,
    private val okHttpClient: OkHttpClient
) {
    private val downloadDir: File by lazy {
        File(context.filesDir, "podcast_downloads").also { it.mkdirs() }
    }

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    private val downloadJobs = ConcurrentHashMap<String, Boolean>()

    suspend fun downloadEpisode(episode: PodcastEpisode): Result<File> {
        if (downloadJobs[episode.id] == true) {
            return Result.failure(Exception("Download already in progress"))
        }

        return withContext(Dispatchers.IO) {
            downloadJobs[episode.id] = true

            try {
                // Update state to downloading
                episodeDao.updateDownloadProgress(episode.id, DownloadState.DOWNLOADING.name, 0)
                updateProgress(episode.id, 0, DownloadState.DOWNLOADING)

                val fileName = "${episode.id}.mp3"
                val outputFile = File(downloadDir, fileName)

                val request = Request.Builder()
                    .url(episode.audioUrl)
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    episodeDao.updateDownloadProgress(episode.id, DownloadState.FAILED.name, 0)
                    updateProgress(episode.id, 0, DownloadState.FAILED)
                    return@withContext Result.failure(Exception("Download failed: ${response.code}"))
                }

                val body = response.body ?: run {
                    episodeDao.updateDownloadProgress(episode.id, DownloadState.FAILED.name, 0)
                    updateProgress(episode.id, 0, DownloadState.FAILED)
                    return@withContext Result.failure(Exception("Empty response body"))
                }

                val totalBytes = body.contentLength()
                var bytesDownloaded = 0L

                FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (downloadJobs[episode.id] != true) {
                                // Download was cancelled
                                outputFile.delete()
                                episodeDao.clearDownload(episode.id)
                                removeProgress(episode.id)
                                return@withContext Result.failure(Exception("Download cancelled"))
                            }

                            fos.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            val progress = if (totalBytes > 0) {
                                ((bytesDownloaded * 100) / totalBytes).toInt()
                            } else {
                                -1
                            }

                            episodeDao.updateDownloadProgress(episode.id, DownloadState.DOWNLOADING.name, progress)
                            updateProgress(episode.id, progress, DownloadState.DOWNLOADING, bytesDownloaded, totalBytes)
                        }
                    }
                }

                // Mark as downloaded
                episodeDao.markAsDownloaded(
                    id = episode.id,
                    state = DownloadState.DOWNLOADED.name,
                    isDownloaded = true,
                    localPath = outputFile.absolutePath,
                    size = bytesDownloaded
                )
                updateProgress(episode.id, 100, DownloadState.DOWNLOADED, bytesDownloaded, totalBytes)

                Result.success(outputFile)
            } catch (e: Exception) {
                episodeDao.updateDownloadProgress(episode.id, DownloadState.FAILED.name, 0)
                updateProgress(episode.id, 0, DownloadState.FAILED)
                Result.failure(e)
            } finally {
                downloadJobs.remove(episode.id)
            }
        }
    }

    suspend fun cancelDownload(episodeId: String) {
        downloadJobs[episodeId] = false
    }

    suspend fun deleteDownload(episode: PodcastEpisode): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Cancel if downloading
                downloadJobs[episode.id] = false

                // Delete file if exists
                episode.localFilePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }

                // Also try to delete by ID-based filename
                val fileName = "${episode.id}.mp3"
                val file = File(downloadDir, fileName)
                if (file.exists()) {
                    file.delete()
                }

                // Update database
                episodeDao.clearDownload(episode.id)
                removeProgress(episode.id)

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun getLocalFilePath(episode: PodcastEpisode): String? {
        if (!episode.isDownloaded || episode.localFilePath == null) return null
        val file = File(episode.localFilePath)
        return if (file.exists()) episode.localFilePath else null
    }

    fun getDownloadedFileSize(episodeId: String): Long {
        val file = File(downloadDir, "${episodeId}.mp3")
        return if (file.exists()) file.length() else 0L
    }

    fun isDownloading(episodeId: String): Boolean {
        return downloadJobs[episodeId] == true
    }

    private fun updateProgress(
        episodeId: String,
        progress: Int,
        state: DownloadState,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L
    ) {
        _activeDownloads.value = _activeDownloads.value + (episodeId to DownloadProgress(
            episodeId = episodeId,
            progress = progress,
            state = state,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes
        ))
    }

    private fun removeProgress(episodeId: String) {
        _activeDownloads.value = _activeDownloads.value - episodeId
    }

    suspend fun cleanupOrphanedDownloads() {
        withContext(Dispatchers.IO) {
            downloadDir.listFiles()?.forEach { file ->
                val episodeId = file.nameWithoutExtension
                val episode = episodeDao.getEpisodeById(episodeId)
                if (episode == null || !episode.isDownloaded) {
                    file.delete()
                }
            }
        }
    }

    fun getTotalDownloadedSize(): Long {
        return downloadDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
