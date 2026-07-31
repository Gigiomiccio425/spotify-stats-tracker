package it.spotifystats.app.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.spotifystats.app.StatsApplication
import it.spotifystats.app.data.ServerConfig
import it.spotifystats.app.data.api.BackendProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServerSetupState(
    val input: String = "",
    val checking: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

class ServerSetupViewModel(private val app: StatsApplication) : ViewModel() {

    private val _state = MutableStateFlow(ServerSetupState())
    val state: StateFlow<ServerSetupState> = _state.asStateFlow()

    init {
        // Precompila con l'indirizzo già in uso, così "cambia server" parte da
        // quello attuale invece che da un campo vuoto.
        _state.value = ServerSetupState(input = app.server.currentUrl.orEmpty())
    }

    fun onInputChange(value: String) {
        _state.value = _state.value.copy(input = value, error = null)
    }

    /**
     * L'indirizzo viene salvato solo se il server risponde davvero.
     * Salvarlo alla cieca porterebbe l'utente in un'app che fallisce ogni
     * schermata senza dirgli che il problema è l'indirizzo appena inserito.
     */
    fun verifyAndSave() {
        val raw = _state.value.input
        if (!ServerConfig.isValid(raw)) {
            _state.value = _state.value.copy(error = "Indirizzo non valido.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(checking = true, error = null)

            BackendProbe.check(raw)
                .onSuccess { normalized ->
                    val previous = app.server.currentUrl
                    app.server.save(normalized)

                    // Cambiare server significa cambiare database: il JWT del
                    // vecchio backend non vale nulla su quello nuovo, e tenerlo
                    // manderebbe l'app in una schermata di errore invece che al
                    // login.
                    if (previous != null && previous != normalized) {
                        app.session.clear()
                    }

                    _state.value = _state.value.copy(checking = false, saved = true)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        checking = false,
                        error = error.message ?: "Verifica non riuscita.",
                    )
                }
        }
    }
}
