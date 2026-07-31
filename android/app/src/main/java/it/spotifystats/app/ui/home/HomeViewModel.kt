package it.spotifystats.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ClockHour
import it.spotifystats.app.data.api.Me
import it.spotifystats.app.data.api.Overview
import it.spotifystats.app.data.api.TimelinePoint
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeData(
    val me: Me,
    val overview: Overview,
    val timeline: List<TimelinePoint>,
    val clock: List<ClockHour>,
    val range: String,
)

class HomeViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state: StateFlow<UiState<HomeData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var range = "since_tracking"

    init {
        load()
    }

    fun setRange(value: String) {
        if (value == range) return
        range = value
        load()
    }

    /**
     * Gesto "tira giù per aggiornare": prima fa interrogare Spotify al server,
     * poi rilegge. Senza la prima parte i dati resterebbero identici fino al
     * giro successivo del poller e il gesto sembrerebbe inutile.
     */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            // Un sync fallito non deve impedire la rilettura: i dati già
            // archiviati vanno mostrati comunque.
            repository.sync()
            load(showLoading = false)
            _refreshing.value = false
        }
    }

    fun load(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _state.value = UiState.Loading

            // Le quattro chiamate sono indipendenti: in parallelo la schermata
            // compare nel tempo della più lenta, non della loro somma.
            val meDeferred = async { repository.me() }
            val overviewDeferred = async { repository.overview(range) }
            val timelineDeferred = async { repository.timeline(range, bucketFor(range)) }
            val clockDeferred = async { repository.clock(range) }

            val me = meDeferred.await()
            val overview = overviewDeferred.await()
            val timeline = timelineDeferred.await()
            val clock = clockDeferred.await()

            val failure = listOf(me, overview, timeline, clock).firstOrNull { it.isFailure }
            if (failure != null) {
                _state.value = UiState.Error(
                    failure.exceptionOrNull()?.message ?: "Impossibile caricare le statistiche",
                )
                return@launch
            }

            _state.value = UiState.Ready(
                HomeData(
                    me = me.getOrThrow(),
                    overview = overview.getOrThrow(),
                    timeline = timeline.getOrThrow().points,
                    clock = clock.getOrThrow().hours,
                    range = range,
                ),
            )
        }
    }

    /** Su finestre lunghe le barre giornaliere diventano illeggibili. */
    private fun bucketFor(range: String): String = when (range) {
        "week", "month", "4weeks" -> "day"
        "6months", "year" -> "week"
        else -> "month"
    }
}
