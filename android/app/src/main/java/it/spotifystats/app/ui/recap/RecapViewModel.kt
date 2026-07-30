package it.spotifystats.app.ui.recap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.Recap
import it.spotifystats.app.data.api.RecapListResponse
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecapListViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<RecapListResponse>>(UiState.Loading)
    val state: StateFlow<UiState<RecapListResponse>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.recaps().fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare i recap") },
            )
        }
    }
}

class RecapDetailViewModel(
    private val repository: StatsRepository,
    private val type: String,
    private val key: String,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<Recap>>(UiState.Loading)
    val state: StateFlow<UiState<Recap>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.recap(type, key).fold(
                onSuccess = { UiState.Ready(it) },
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare il recap") },
            )
        }
    }
}
