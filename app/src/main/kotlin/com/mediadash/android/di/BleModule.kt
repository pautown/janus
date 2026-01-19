package com.mediadash.android.di

import com.mediadash.android.ble.AlbumArtTransmitter
import com.mediadash.android.ble.GattServerManager
import com.mediadash.android.ble.NotificationThrottler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideNotificationThrottler(): NotificationThrottler {
        return NotificationThrottler()
    }

    @Provides
    @Singleton
    fun provideAlbumArtTransmitter(
        notificationThrottler: NotificationThrottler
    ): AlbumArtTransmitter {
        return AlbumArtTransmitter(notificationThrottler)
    }
}
