package com.mediadash.android.data.remote

import com.mediadash.android.domain.model.ITunesSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ITunesApiService {
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 50,
        @Query("country") country: String = "us"
    ): ITunesSearchResponse

    companion object {
        const val BASE_URL = "https://itunes.apple.com/"
    }
}
