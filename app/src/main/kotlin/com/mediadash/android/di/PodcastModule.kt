package com.mediadash.android.di

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.mediadash.android.data.local.EpisodeDao
import com.mediadash.android.data.local.PodcastDao
import com.mediadash.android.data.local.PodcastDatabase
import com.mediadash.android.data.remote.ITunesApiService
import com.mediadash.android.data.remote.RssFeedParser
import com.mediadash.android.data.repository.PodcastRepository
import com.mediadash.android.data.repository.PodcastRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PodcastModule {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)  // Longer timeout for podcast downloads
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(ITunesApiService.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideITunesApiService(retrofit: Retrofit): ITunesApiService {
        return retrofit.create(ITunesApiService::class.java)
    }

    @Provides
    @Singleton
    fun providePodcastDatabase(@ApplicationContext context: Context): PodcastDatabase {
        return Room.databaseBuilder(
            context,
            PodcastDatabase::class.java,
            PodcastDatabase.DATABASE_NAME
        )
            .addMigrations(PodcastDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun providePodcastDao(database: PodcastDatabase): PodcastDao {
        return database.podcastDao()
    }

    @Provides
    @Singleton
    fun provideEpisodeDao(database: PodcastDatabase): EpisodeDao {
        return database.episodeDao()
    }

    @Provides
    @Singleton
    fun provideRssFeedParser(): RssFeedParser {
        return RssFeedParser()
    }

    @Provides
    @Singleton
    fun providePodcastRepository(
        iTunesApiService: ITunesApiService,
        rssFeedParser: RssFeedParser,
        podcastDao: PodcastDao,
        episodeDao: EpisodeDao
    ): PodcastRepository {
        return PodcastRepositoryImpl(
            iTunesApiService = iTunesApiService,
            rssFeedParser = rssFeedParser,
            podcastDao = podcastDao,
            episodeDao = episodeDao
        )
    }
}
