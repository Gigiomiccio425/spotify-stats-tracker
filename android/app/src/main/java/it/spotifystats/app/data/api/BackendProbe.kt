package it.spotifystats.app.data.api

import it.spotifystats.app.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@Serializable
private data class Health(val ok: Boolean = false, val now: String? = null)

/**
 * Verifica un indirizzo prima di salvarlo.
 *
 * Usa un client OkHttp tutto suo, non quello dell'app: quello riscrive ogni
 * richiesta verso il server *già configurato*, e qui invece si sta collaudando
 * un candidato che ancora non lo è.
 */
object BackendProbe {

    private val client = OkHttpClient.Builder()
        // Timeout corti: è un controllo interattivo, l'utente sta aspettando
        // davanti allo schermo.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun check(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val base = ServerConfig.normalize(rawUrl)

        runCatching {
            val request = Request.Builder().url("${base}health").get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Il server ha risposto ${response.code}. Controlla che l'indirizzo punti al backend e non ad altro.")
                }

                val body = response.body?.string().orEmpty()
                val health = runCatching { ApiClient.json.decodeFromString<Health>(body) }.getOrNull()
                    ?: error("Risposta inattesa: a questo indirizzo c'è qualcosa, ma non è il backend.")

                if (!health.ok) error("Il backend ha risposto ma si segnala non funzionante.")
                base
            }
        }.recoverCatching { error ->
            throw Exception(describe(error))
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is UnknownHostException ->
            "Nome host non risolto. Controlla l'indirizzo e la connessione."
        is SSLException ->
            "Errore TLS. Se il server è in HTTP e non HTTPS, scrivi l'indirizzo per esteso: http://…"
        else -> error.message ?: "Server irraggiungibile."
    }
}
