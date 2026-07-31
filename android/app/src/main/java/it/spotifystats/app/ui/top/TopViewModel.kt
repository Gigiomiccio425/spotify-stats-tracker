package it.spotifystats.app.ui.top

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ClockHour
import it.spotifystats.app.data.api.Overview
import it.spotifystats.app.data.api.ReleaseYearStats
import it.spotifystats.app.data.api.TimelinePoint
import it.spotifystats.app.data.api.TopAlbum
import it.spotifystats.app.data.api.TopArtist
import it.spotifystats.app.data.api.TopGenre
import it.spotifystats.app.data.api.TopTrack
import it.spotifystats.app.data.api.WeekdayStat
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TopTab(val label: String) {
    Trends("Tendenze"),
    Tracks("Brani"),
    Artists("Artisti"),
    Albums("Album"),
    Genres("Generi"),
    Years("Anni"),
}

/** Tutto ciò che serve alla scheda delle tendenze in una sola struttura. */
data class TrendsData(
    val overview: Overview,
    val timeline: List<TimelinePoint>,
    val clock: List<ClockHour>,
    val weekdays: List<WeekdayStat>,
    val bucket: String,
)

/** Una sola sezione per volta: quale dipende dalla scheda attiva. */
data class TopData(
    val tab: TopTab,
    val range: String,
    val trends: TrendsData? = null,
    val tracks: List<TopTrack> = emptyList(),
    val artists: List<TopArtist> = emptyList(),
    val albums: List<TopAlbum> = emptyList(),
    val genres: List<TopGenre> = emptyList(),
    /** Artisti ascoltati nel periodo e quanti di questi hanno generi su
     *  Spotify: spiegano una classifica dei generi vuota. */
    val artistsTotal: Int = 0,
    val artistsWithGenres: Int = 0,
    val releaseYears: ReleaseYearStats? = null,
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
)

private const val PAGE_SIZE = 50

class TopViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TopData>>(UiState.Loading)
    val state: StateFlow<UiState<TopData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var tab = TopTab.Trends
    private var range = "lifetime"
    private var bucket: String? = null

    init {
        load()
    }

    fun setTab(value: TopTab) {
        if (value == tab) return
        tab = value
        load()
    }

    fun setRange(value: String) {
        if (value == range) return
        range = value
        // Il raggruppamento scelto a mano vale per l'intervallo su cui è stato
        // scelto: su "Tutto" le barre giornaliere sarebbero migliaia.
        bucket = null
        load()
    }

    fun setBucket(value: String) {
        if (value == bucket) return
        bucket = value
        load(showLoading = false)
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repository.sync()
            load(showLoading = false)
            _refreshing.value = false
        }
    }

    fun load(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _state.value = UiState.Loading
            _state.value = fetch(offset = 0).fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare le statistiche") },
            )
        }
    }

    /** Paginazione: si aggiunge in coda a ciò che è già a schermo. */
    fun loadMore() {
        val current = _state.value as? UiState.Ready ?: return
        val data = current.data
        if (!data.canLoadMore || data.loadingMore) return

        viewModelScope.launch {
            _state.value = UiState.Ready(data.copy(loadingMore = true))
            val offset = when (data.tab) {
                TopTab.Tracks -> data.tracks.size
                TopTab.Artists -> data.artists.size
                TopTab.Albums -> data.albums.size
                else -> 0
            }
            fetch(offset).onSuccess { page ->
                _state.value = UiState.Ready(
                    data.copy(
                        // Due richieste di pagina ravvicinate possono superare
                        // il controllo su `loadingMore` prima che venga scritto,
                        // e una chiave duplicata in LazyColumn fa terminare l'app.
                        tracks = (data.tracks + page.tracks).distinctBy { it.id },
                        artists = (data.artists + page.artists).distinctBy { it.id },
                        albums = (data.albums + page.albums).distinctBy { it.id },
                        canLoadMore = page.canLoadMore,
                        loadingMore = false,
                    ),
                )
            }.onFailure {
                // Un errore in coda non deve buttare via la lista già visibile.
                _state.value = UiState.Ready(data.copy(loadingMore = false, canLoadMore = false))
            }
        }
    }

    /** Su finestre lunghe le barre giornaliere diventano illeggibili. */
    private fun defaultBucketFor(value: String): String = when (value) {
        "week", "month", "4weeks" -> "day"
        "6months", "year" -> "week"
        else -> "month"
    }

    private suspend fun fetch(offset: Int): Result<TopData> = when (tab) {
        TopTab.Trends -> fetchTrends()

        TopTab.Tracks -> repository.topTracks(range, PAGE_SIZE, offset).map {
            TopData(tab, range, tracks = it, canLoadMore = it.size == PAGE_SIZE)
        }
        TopTab.Artists -> repository.topArtists(range, PAGE_SIZE, offset).map {
            TopData(tab, range, artists = it, canLoadMore = it.size == PAGE_SIZE)
        }
        TopTab.Albums -> repository.topAlbums(range, PAGE_SIZE, offset).map {
            TopData(tab, range, albums = it, canLoadMore = it.size == PAGE_SIZE)
        }
        // I generi sono poche decine in tutto: niente paginazione.
        TopTab.Genres -> repository.topGenres(range, 50).map {
            TopData(
                tab,
                range,
                genres = it.items,
                artistsTotal = it.artistsTotal,
                artistsWithGenres = it.artistsWithGenres,
                canLoadMore = false,
            )
        }
        // Gli anni arrivano già aggregati dal server, con media e mediana
        // calcolate su tutti gli ascolti: nessuna pagina da scorrere.
        TopTab.Years -> repository.releaseYears(range).map {
            TopData(tab, range, releaseYears = it, canLoadMore = false)
        }
    }

    private suspend fun fetchTrends(): Result<TopData> = coroutineScope {
        val effectiveBucket = bucket ?: defaultBucketFor(range)

        // Quattro chiamate indipendenti: in parallelo la scheda compare nel
        // tempo della più lenta, non della loro somma.
        val overview = async { repository.overview(range) }
        val timeline = async { repository.timeline(range, effectiveBucket) }
        val clock = async { repository.clock(range) }
        val weekdays = async { repository.weekdays(range) }

        val overviewResult = overview.await()
        val timelineResult = timeline.await()
        val clockResult = clock.await()
        val weekdaysResult = weekdays.await()

        val failure = listOf(overviewResult, timelineResult, clockResult, weekdaysResult)
            .firstOrNull { it.isFailure }

        if (failure != null) {
            Result.failure(failure.exceptionOrNull() ?: Exception("Errore imprevisto"))
        } else {
            Result.success(
                TopData(
                    tab = TopTab.Trends,
                    range = range,
                    trends = TrendsData(
                        overview = overviewResult.getOrThrow(),
                        timeline = timelineResult.getOrThrow().points,
                        clock = clockResult.getOrThrow().hours,
                        weekdays = weekdaysResult.getOrThrow().days,
                        bucket = effectiveBucket,
                    ),
                ),
            )
        }
    }
}
