package it.spotifystats.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import it.spotifystats.app.auth.AuthOutcome
import it.spotifystats.app.ui.components.LoadingState
import it.spotifystats.app.ui.login.LoginScreen
import it.spotifystats.app.ui.navigation.AppNavigation
import it.spotifystats.app.ui.server.ServerSetupScreen
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.SpotifyStatsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var authError by mutableStateOf<String?>(null)
    private var storesLoaded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as StatsApplication

        // Indirizzo del server e sessione vanno letti prima di decidere quale
        // schermata mostrare, altrimenti a ogni avvio l'app lampeggia sulla
        // configurazione iniziale già fatta.
        lifecycleScope.launch {
            app.server.load()
            app.session.load()
            storesLoaded = true
        }

        handleDeepLink(intent)

        setContent {
            SpotifyStatsTheme {
                val serverUrl by app.server.url.collectAsState(initial = null)
                val token by app.session.token.collectAsState(initial = null)

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Background),
                ) {
                    when {
                        !storesLoaded -> LoadingState()

                        // Senza indirizzo del backend non c'è nulla da mostrare
                        // e nemmeno dove eseguire il login.
                        serverUrl == null -> ServerSetupScreen(onConfigured = { authError = null })

                        token == null -> LoginScreen(
                            onConnect = {
                                authError = null
                                if (!app.auth.startLogin(this@MainActivity)) {
                                    authError = "Indirizzo del backend non configurato."
                                }
                            },
                            errorMessage = authError,
                        )

                        else -> AppNavigation(
                            onLoggedOut = {
                                // `session.token` emette null e la UI torna da
                                // sola al login: qui basta ripulire l'errore.
                                authError = null
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Il ritorno dall'OAuth arriva qui e non in una nuova Activity grazie a
     * `launchMode="singleTask"` nel manifest.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val app = application as StatsApplication

        lifecycleScope.launch {
            when (val outcome = app.auth.handleRedirect(data)) {
                is AuthOutcome.Success -> {
                    authError = null
                    if (outcome.isNewAccount) {
                        Toast.makeText(
                            this@MainActivity,
                            "Account collegato. L'archiviazione parte da adesso.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                is AuthOutcome.Failed -> authError = outcome.message
                null -> Unit
            }
        }
    }
}
