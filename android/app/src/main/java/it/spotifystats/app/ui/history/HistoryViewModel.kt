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

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
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
                        // `distinctBy` non e' ridondante: due richieste di
                        // pagina ravvicinate possono superare il controllo su
                        // `loadingMore` prima che venga scritto, e una chiave
                        // duplicata in LazyColumn fa terminare l'app.
                        items = (data.items + page.items).distinctBy { it.id },
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
