package it.spotifystats.app.data.api

import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/account/me")
    suspend fun me(): Me

    @PATCH("api/account/me")
    suspend fun updateSettings(@Body patch: SettingsPatch): SettingsPatch

    @DELETE("api/account")
    suspend fun deleteAccount(@Query("confirm") confirm: String): DeleteResult

    /** Interroga Spotify subito invece di aspettare il giro dei 15 minuti. */
    @POST("api/account/sync")
    suspend fun sync(): SyncResult

    /** `range` accetta i preset: week, month, 4weeks, 6months, year,
     *  since_tracking, lifetime. In alternativa si passano from/to ISO. */
    @GET("api/stats/overview")
    suspend fun overview(@Query("range") range: String): Overview

    @GET("api/stats/top/tracks")
    suspend fun topTracks(
        @Query("range") range: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): TopTracksResponse

    @GET("api/stats/top/artists")
    suspend fun topArtists(
        @Query("range") range: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): TopArtistsResponse

    @GET("api/stats/top/albums")
    suspend fun topAlbums(
        @Query("range") range: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): TopAlbumsResponse

    @GET("api/stats/top/genres")
    suspend fun topGenres(
        @Query("range") range: String,
        @Query("limit") limit: Int = 30,
    ): TopGenresResponse

    @GET("api/stats/timeline")
    suspend fun timeline(
        @Query("range") range: String,
        @Query("bucket") bucket: String,
    ): TimelineResponse

    @GET("api/stats/clock")
    suspend fun clock(@Query("range") range: String): ClockResponse

    @GET("api/stats/release-years")
    suspend fun releaseYears(@Query("range") range: String): ReleaseYearStats

    @GET("api/stats/track/{id}")
    suspend fun trackDetail(@Path("id") id: String): TrackDetail

    @GET("api/stats/artist/{id}")
    suspend fun artistDetail(
        @Path("id") id: String,
        @Query("range") range: String = "lifetime",
    ): ArtistDetail

    @GET("api/history")
    suspend fun history(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50,
    ): HistoryResponse

    @GET("api/recaps")
    suspend fun recaps(@Query("type") type: String? = null): RecapListResponse

    @GET("api/recaps/{type}/{key}")
    suspend fun recap(@Path("type") type: String, @Path("key") key: String): Recap

    /** Il corpo è la lista grezza letta dal file Streaming_History_Audio_*.json. */
    @POST("api/import/streaming-history")
    suspend fun importStreamingHistory(
        @Query("filename") filename: String,
        @Body entries: JsonElement,
    ): ImportResult
}
