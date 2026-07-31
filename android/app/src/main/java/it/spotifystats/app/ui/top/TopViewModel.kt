package it.spotifystats.app.ui.top

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.TopAlbum
import it.spotifystats.app.data.api.TopArtist
import it.spotifystats.app.data.api.ReleaseYearStats
import it.spotifystats.app.data.api.TopGenre
import it.spotifystats.app.data.api.TopTrack
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TopTab(val label: String) {
    Tracks("Brani"),
    Artists("Artisti"),
    Albums("Album"),
    Genres("Generi"),
    Years("Anni"),
}

/** Una sola lista per volta: quale dipende dalla scheda attiva. */
data class TopData(
    val tab: TopTab,
    val range: String,
    val tracks: List<TopTrack> = emptyList(),
    val artists: List<TopArtist> = emptyList(),
    val albums: List<TopAlbum> = emptyList(),
    val genres: List<TopGenre> = emptyList(),
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

    private var tab = TopTab.Tracks
    private var range = "lifetime"

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
        load()
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
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare la classifica") },
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
                TopTab.Genres -> data.genres.size
                TopTab.Years -> 0
            }
            fetch(offset).onSuccess { page ->
                _state.value = UiState.Ready(
                    data.copy(
                        // Stessa ragione dello storico: due richieste di pagina
                        // ravvicinate darebbero chiavi duplicate, e una chiave
                        // duplicata in LazyColumn fa terminare l'app.
                        tracks = (data.tracks + page.tracks).distinctBy { it.id },
                        artists = (data.artists + page.artists).distinctBy { it.id },
                        albums = (data.albums + page.albums).distinctBy { it.id },
                        genres = (data.genres + page.genres).distinctBy { it.genre },
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

    private suspend fun fetch(offset: Int): Result<TopData> = when (tab) {
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
            TopData(tab, range, genres = it, canLoadMore = false)
        }
        // Gli anni arrivano gia' aggregati dal server, con media e mediana
        // calcolate su tutti gli ascolti del periodo: nessuna pagina da scorrere.
        TopTab.Years -> repository.releaseYears(range).map {
            TopData(tab, range, releaseYears = it, canLoadMore = false)
        }
    }
}
