package it.spotifystats.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ArtistDetail
import it.spotifystats.app.data.api.TrackDetail
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrackDetailViewModel(
    private val repository: StatsRepository,
    private val trackId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<TrackDetail>>(UiState.Loading)
    val state: StateFlow<UiState<TrackDetail>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.trackDetail(trackId).fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Brano non trovato") },
            )
        }
    }
}

class ArtistDetailViewModel(
    private val repository: StatsRepository,
    private val artistId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ArtistDetail>>(UiState.Loading)
    val state: StateFlow<UiState<ArtistDetail>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.artistDetail(artistId).fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Artista non trovato") },
            )
        }
    }
}
