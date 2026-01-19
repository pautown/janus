package com.mediadash.android.di

import com.mediadash.android.data.media.AlbumArtCache
import com.mediadash.android.data.media.AlbumArtFetcher
import com.mediadash.android.data.repository.MediaRepository
import com.mediadash.android.data.repository.MediaRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository
}

@Module
@InstallIn(SingletonComponent::class)
object MediaProviderModule {

    @Provides
    @Singleton
    fun provideAlbumArtCache(): AlbumArtCache {
        return AlbumArtCache(maxSize = 10)
    }
}
