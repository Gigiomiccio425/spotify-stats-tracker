package it.spotifystats.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.SurfaceElevated

/**
 * Contenitore con il gesto "tira giù per aggiornare".
 *
 * Il refresh non si limita a rileggere il database: chiede prima al server di
 * interrogare Spotify. Senza, l'utente vedrebbe gli stessi dati fino al giro
 * successivo del poller e penserebbe che il gesto non funziona.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = SurfaceElevated,
                color = Accent,
            )
        },
        content = content,
    )
}
