package it.spotifystats.app.data

import it.spotifystats.app.data.api.ApiService
import it.spotifystats.app.data.api.ArtistDetail
import it.spotifystats.app.data.api.BackendNotConfiguredException
import it.spotifystats.app.data.api.ClockResponse
import it.spotifystats.app.data.api.HistoryResponse
import it.spotifystats.app.data.api.ImportResult
import it.spotifystats.app.data.api.Me
import it.spotifystats.app.data.api.Overview
import it.spotifystats.app.data.api.Recap
import it.spotifystats.app.data.api.RecapListResponse
import it.spotifystats.app.data.api.ReleaseYearStats
import it.spotifystats.app.data.api.SettingsPatch
import it.spotifystats.app.data.api.SyncResult
import it.spotifystats.app.data.api.TimelineResponse
import it.spotifystats.app.data.api.TopAlbum
import it.spotifystats.app.data.api.TopArtist
import it.spotifystats.app.data.api.TopGenre
import it.spotifystats.app.data.api.TopTrack
import it.spotifystats.app.data.api.TrackDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException
import java.io.IOException

/** Sessione scaduta o revocata: l'app deve tornare alla schermata di login. */
class SessionExpiredException : Exception("Sessione scaduta")

class StatsRepository(
    private val api: ApiService,
    private val session: SessionStore,
) {

    /**
     * Ogni chiamata passa di qui: converte le eccezioni di rete in messaggi
     * leggibili e intercetta il 401 per far ripartire il login una volta sola,
     * invece di lasciare che ogni schermata lo gestisca a modo suo.
     */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching { block() }.recoverCatching { error ->
            when {
                // Va riconosciuto prima di IOException, da cui deriva:
                // altrimenti riceverebbe il messaggio generico di rete e
                // l'utente cercherebbe un guasto che non c'è.
                error is BackendNotConfiguredException ->
                    throw Exception("Indirizzo del backend non configurato. Impostalo in Profilo.")
                error is HttpException && error.code() == 401 -> {
                    session.clear()
                    throw SessionExpiredException()
                }
                error is HttpException ->
                    throw Exception("Il server ha risposto ${error.code()}")
                error is IOException ->
                    throw Exception("Server irraggiungibile. Controlla la connessione e l'indirizzo del backend.")
                else -> throw error
            }
        }
    }

    suspend fun me(): Result<Me> = call { api.me() }

    suspend fun updateSettings(patch: SettingsPatch): Result<SettingsPatch> =
        call { api.updateSettings(patch) }

    suspend fun deleteAccount(spotifyUserId: String): Result<Unit> = call {
        api.deleteAccount(spotifyUserId)
        session.clear()
    }

    /**
     * Forza un controllo su Spotify. Un fallimento qui non deve impedire di
     * ricaricare le schermate: se il server rifiuta perché troppo ravvicinato,
     * i dati esistenti sono comunque da rileggere.
     */
    suspend fun sync(): Result<SyncResult> = call { api.sync() }

    suspend fun overview(range: String): Result<Overview> = call { api.overview(range) }

    suspend fun topTracks(range: String, limit: Int = 50, offset: Int = 0): Result<List<TopTrack>> =
        call { api.topTracks(range, limit, offset).items }

    suspend fun topArtists(range: String, limit: Int = 50, offset: Int = 0): Result<List<TopArtist>> =
        call { api.topArtists(range, limit, offset).items }

    suspend fun topAlbums(range: String, limit: Int = 50, offset: Int = 0): Result<List<TopAlbum>> =
        call { api.topAlbums(range, limit, offset).items }

    suspend fun topGenres(range: String, limit: Int = 30): Result<List<TopGenre>> =
        call { api.topGenres(range, limit).items }

    suspend fun timeline(range: String, bucket: String): Result<TimelineResponse> =
        call { api.timeline(range, bucket) }

    suspend fun clock(range: String): Result<ClockResponse> = call { api.clock(range) }

    suspend fun releaseYears(range: String): Result<ReleaseYearStats> =
        call { api.releaseYears(range) }

    suspend fun history(cursor: String?, limit: Int = 50): Result<HistoryResponse> =
        call { api.history(cursor, limit) }

    suspend fun recaps(type: String? = null): Result<RecapListResponse> = call { api.recaps(type) }

    suspend fun recap(type: String, key: String): Result<Recap> = call { api.recap(type, key) }

    suspend fun trackDetail(id: String): Result<TrackDetail> = call { api.trackDetail(id) }

    suspend fun artistDetail(id: String): Result<ArtistDetail> = call { api.artistDetail(id) }

    suspend fun importStreamingHistory(filename: String, entries: JsonElement): Result<ImportResult> =
        call { api.importStreamingHistory(filename, entries) }

    suspend fun logout() = session.clear()
}
