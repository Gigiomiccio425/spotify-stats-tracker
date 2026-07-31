package it.spotifystats.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.spotifystats.app.StatsApplication
import it.spotifystats.app.data.StatsRepository

/**
 * Costruisce un ViewModel passandogli il repository, senza tirare dentro un
 * framework di dependency injection per tre oggetti condivisi.
 */
@Composable
inline fun <reified VM : ViewModel> repositoryViewModel(
    key: String? = null,
    crossinline create: (StatsRepository) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as StatsApplication
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(app.repository) }
        },
    )
}

/**
 * Variante per i ViewModel che toccano più di un pezzo del container: la
 * configurazione del server, per esempio, va scritta insieme alla sessione.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (StatsApplication) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as StatsApplication
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(app) }
        },
    )
}
