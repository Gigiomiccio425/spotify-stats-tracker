package it.spotifystats.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.HistoryItem
import it.spotifystats.app.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryData(
    val items: List<HistoryItem> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
)

class HistoryViewModel(private val repository: StatsRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HistoryData>>(UiState.Loading)
    val state: StateFlow<UiState<HistoryData>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _state.value = repository.history(cursor = null).fold(
                onSuccess = { UiState.Ready(HistoryData(it.items, it.nextCursor)) },
                onFailure = { UiState.Error(it.message ?: "Impossibile caricare lo storico") },
            )
        }
    }

    fun loadMore() {
        val current = _state.value as? UiState.Ready ?: return
        val data = current.data
        val cursor = data.nextCursor ?: return
        if (data.loadingMore) return

        viewModelScope.launch {
            _state.value = UiState.Ready(data.copy(loadingMore = true))
            repository.history(cursor).onSuccess { page ->
                _state.value = UiState.Ready(
                    HistoryData(
                        items = data.items + page.items,
                        nextCursor = page.nextCursor,
                        loadingMore = false,
                    ),
                )
            }.onFailure {
                // Si smette di paginare ma si tiene ciò che è già visibile.
                _state.value = UiState.Ready(data.copy(loadingMore = false, nextCursor = null))
            }
        }
    }
}
