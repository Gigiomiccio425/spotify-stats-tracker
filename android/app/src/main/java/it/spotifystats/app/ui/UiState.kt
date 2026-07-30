package it.spotifystats.app.ui

/** Stato di un caricamento: evita che ogni schermata inventi i propri flag. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Ready<T>(val data: T) : UiState<T>
}

inline fun <T> Result<T>.toUiState(): UiState<T> = fold(
    onSuccess = { UiState.Ready(it) },
    onFailure = { UiState.Error(it.message ?: "Errore imprevisto") },
)
