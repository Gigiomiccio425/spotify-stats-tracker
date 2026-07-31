package it.spotifystats.app.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import it.spotifystats.app.data.ServerConfig
import it.spotifystats.app.data.SessionStore

/**
 * Gestisce il collegamento dell'account Spotify.
 *
 * Il flusso OAuth non avviene dentro l'app: apriamo una Chrome Custom Tab sul
 * backend, che redirige a Spotify e alla fine ci rimanda qui via deep link
 * `spotifystats://auth?session=...`.
 *
 * Perché non una WebView: Spotify blocca il login nelle WebView, e una WebView
 * potrebbe leggere le credenziali digitate. La Custom Tab usa il browser di
 * sistema, con le sue password salvate e la sua sessione.
 */
class AuthManager(
    private val session: SessionStore,
    private val server: ServerConfig,
) {

    /** false se manca l'indirizzo del backend: senza, non c'è dove andare. */
    fun startLogin(context: Context): Boolean {
        val base = server.currentUrl ?: return false

        CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .build()
            .launchUrl(context, "${base}auth/spotify/start".toUri())

        return true
    }

    /**
     * Interpreta il deep link di ritorno.
     * Restituisce null se l'URI non è nostro; un [AuthOutcome.Failed] se
     * Spotify o il backend hanno segnalato un errore.
     */
    suspend fun handleRedirect(uri: Uri?): AuthOutcome? {
        if (uri == null || uri.scheme != "spotifystats") return null

        uri.getQueryParameter("error")?.let { code ->
            return AuthOutcome.Failed(code, describe(code))
        }

        val token = uri.getQueryParameter("session") ?: return AuthOutcome.Failed(
            "missing_session",
            "Il backend non ha restituito la sessione.",
        )

        session.save(token)
        return AuthOutcome.Success(isNewAccount = uri.getQueryParameter("new") == "1")
    }

    private fun describe(code: String): String = when (code) {
        // Il caso più frequente in Development Mode: l'email non è fra i 25
        // account autorizzati nella dashboard Spotify.
        "not_allowlisted" ->
            "Questo account non è fra quelli autorizzati. Va aggiunto in User Management " +
                "nella dashboard Spotify dell'app."
        "access_denied" -> "Autorizzazione negata su Spotify."
        "invalid_state" -> "Sessione di login scaduta. Riprova."
        "exchange_failed" -> "Il backend non è riuscito a completare il login con Spotify."
        else -> "Login non riuscito ($code)."
    }
}

sealed interface AuthOutcome {
    data class Success(val isNewAccount: Boolean) : AuthOutcome
    data class Failed(val code: String, val message: String) : AuthOutcome
}
