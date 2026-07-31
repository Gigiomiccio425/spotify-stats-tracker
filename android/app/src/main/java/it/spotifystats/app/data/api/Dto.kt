package it.spotifystats.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rispecchiano uno a uno le risposte del backend.
 * Tutti i campi opzionali hanno un default: un campo aggiunto lato server non
 * deve far crashare una versione dell'app già installata.
 */

@Serializable
data class SyncStatus(
    val lastRunAt: String? = null,
    val status: String? = null,
    val error: String? = null,
    val itemsInserted: Int = 0,
    /** Quante volte il poller ha trovato la finestra da 50 tracce piena.
     *  Ognuna è un possibile buco nell'archivio. */
    val possibleGaps: Int = 0,
)

@Serializable
data class Me(
    val id: String,
    val spotifyUserId: String,
    val displayName: String? = null,
    val imageUrl: String? = null,
    val country: String? = null,
    val trackingSince: String,
    val periodMode: String = "calendar",
    val timezone: String = "Europe/Rome",
    /** Ora a cui comincia la giornata nei recap giornalieri, 0-23. */
    val dailyRecapHour: Int = 0,
    val sync: SyncStatus = SyncStatus(),
)

@Serializable
data class SettingsPatch(
    val periodMode: String? = null,
    val timezone: String? = null,
    val dailyRecapHour: Int? = null,
)

@Serializable
data class TopTrack(
    val id: String,
    val name: String,
    val artistNames: String? = null,
    val albumName: String? = null,
    val imageUrl: String? = null,
    val durationMs: Int = 0,
    val playCount: Int,
    val msPlayed: Long,
)

@Serializable
data class TopArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val playCount: Int,
    val msPlayed: Long,
)

@Serializable
data class TopAlbum(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val artistNames: String? = null,
    val playCount: Int,
    val msPlayed: Long,
)

@Serializable
data class TopGenre(
    val genre: String,
    val playCount: Int,
    val msPlayed: Long,
)

@Serializable data class TopTracksResponse(val items: List<TopTrack> = emptyList())
@Serializable data class TopArtistsResponse(val items: List<TopArtist> = emptyList())
@Serializable data class TopAlbumsResponse(val items: List<TopAlbum> = emptyList())
@Serializable
data class TopGenresResponse(
    val items: List<TopGenre> = emptyList(),
    /** Quanti artisti ascoltati nel periodo, e quanti di questi hanno almeno un
     *  genere su Spotify. Servono a spiegare una classifica vuota. */
    val artistsTotal: Int = 0,
    val artistsWithGenres: Int = 0,
)

@Serializable
data class Overview(
    val trackingSince: String,
    val playCount: Int = 0,
    val msPlayed: Long = 0,
    val minutesPlayed: Int = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val distinctAlbums: Int = 0,
    val listeningDays: Int = 0,
    val streak: Int = 0,
    val firstPlayAt: String? = null,
    val lastPlayAt: String? = null,
    val topTracks: List<TopTrack> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
)

@Serializable
data class TimelinePoint(val bucket: String, val playCount: Int, val msPlayed: Long)

@Serializable
data class TimelineResponse(val points: List<TimelinePoint> = emptyList())

@Serializable
data class ReleaseYear(val year: Int, val playCount: Int, val msPlayed: Long = 0)

@Serializable
data class Decade(val decade: Int, val playCount: Int, val share: Int = 0)

/** Distribuzione degli ascolti per anno di pubblicazione: l'"età musicale". */
@Serializable
data class ReleaseYearStats(
    val years: List<ReleaseYear> = emptyList(),
    val decades: List<Decade> = emptyList(),
    val averageYear: Int? = null,
    val medianYear: Int? = null,
    val oldestYear: Int? = null,
    val newestYear: Int? = null,
    /** Ascolti su cui il calcolo si basa: alcuni album non hanno una data. */
    val coveredPlays: Int = 0,
)

@Serializable
data class WeekdayStat(val weekday: Int, val playCount: Int, val msPlayed: Long = 0)

@Serializable
data class WeekdaysResponse(val days: List<WeekdayStat> = emptyList())

@Serializable
data class ClockHour(val hour: Int, val playCount: Int)

@Serializable
data class ClockResponse(val hours: List<ClockHour> = emptyList())

@Serializable
data class HistoryItem(
    val id: Long,
    val playedAt: String,
    val trackId: String,
    val trackName: String,
    val artistNames: String? = null,
    val albumName: String? = null,
    val imageUrl: String? = null,
    val msPlayed: Long = 0,
)

@Serializable
data class HistoryResponse(
    val items: List<HistoryItem> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class PeriodRef(
    val key: String,
    val label: String,
    val partial: Boolean = false,
    val start: String? = null,
    val end: String? = null,
    val inProgress: Boolean = false,
)

@Serializable
data class RecapGroup(
    val type: String,
    val current: PeriodRef,
    val periods: List<PeriodRef> = emptyList(),
)

@Serializable
data class RecapListResponse(
    val mode: String = "calendar",
    val trackingSince: String,
    val groups: List<RecapGroup> = emptyList(),
)

@Serializable
data class RecapPeriod(
    val type: String,
    val key: String,
    val label: String,
    val start: String,
    val end: String,
    val partial: Boolean = false,
)

@Serializable
data class RecapTotals(
    val playCount: Int = 0,
    val minutesPlayed: Int = 0,
    val distinctTracks: Int = 0,
    val distinctArtists: Int = 0,
    val listeningDays: Int = 0,
)

@Serializable
data class BusiestDay(val day: String, val playCount: Int, val minutesPlayed: Int)

@Serializable
data class Recap(
    val period: RecapPeriod,
    val totals: RecapTotals,
    val topTracks: List<TopTrack> = emptyList(),
    val topArtists: List<TopArtist> = emptyList(),
    val topAlbums: List<TopAlbum> = emptyList(),
    val topGenres: List<TopGenre> = emptyList(),
    val busiestDay: BusiestDay? = null,
    /** Solo nei recap mensili e annuali: su finestre brevi l'anno medio
     *  sarebbe calcolato su troppi pochi ascolti per significare qualcosa. */
    val releaseYears: ReleaseYearStats? = null,
    val minutesChangePct: Int? = null,
)

@Serializable
data class TrackDetail(
    val id: String,
    val name: String,
    val artistNames: String? = null,
    val albumName: String? = null,
    val imageUrl: String? = null,
    val durationMs: Int = 0,
    val playCount: Int = 0,
    val msPlayed: Long = 0,
    val firstPlayedAt: String? = null,
    val lastPlayedAt: String? = null,
)

@Serializable
data class ArtistDetail(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val playCount: Int = 0,
    val msPlayed: Long = 0,
    val distinctTracks: Int = 0,
    val firstPlayedAt: String? = null,
    val lastPlayedAt: String? = null,
    val topTracks: List<TopTrack> = emptyList(),
)

@Serializable
data class ImportResult(
    @SerialName("jobId") val jobId: String,
    val rowsTotal: Int = 0,
    val rowsImported: Int = 0,
    val rowsSkipped: Int = 0,
)

@Serializable
data class DeleteResult(val deleted: Boolean = false, val playsDeleted: Int = 0)

@Serializable
data class SyncResult(
    /** true quando il server ha rifiutato perché l'ultimo controllo è troppo
     *  recente: il rate limit di Spotify è per applicazione, non per utente. */
    val skipped: Boolean = false,
    val inserted: Int = 0,
    val fetched: Int = 0,
    val status: String? = null,
)
