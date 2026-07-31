package it.spotifystats.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicReference

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_config")

/**
 * Indirizzo del backend, impostato dall'utente dentro l'app.
 *
 * Non è una costante di compilazione perché l'APK viene distribuito già
 * compilato: chi lo installa punta al proprio server, non a quello di chi ha
 * fatto la build.
 *
 * Qui non finiscono Client ID e Client Secret di Spotify: restano sul backend.
 * Un secret dentro un APK è estraibile da chiunque con `unzip` e un decompiler,
 * quindi non sarebbe più un secret.
 */
class ServerConfig(context: Context) {

    private val dataStore = context.applicationContext.serverDataStore
    private val cached = AtomicReference<String?>(null)

    /** Emette null finché l'utente non ha configurato nulla. */
    val url: Flow<String?> = dataStore.data.map { it[KEY_URL] }.onEach { cached.set(it) }

    /** Letto dall'interceptor OkHttp, che gira fuori dalle coroutine. */
    val currentUrl: String? get() = cached.get()

    suspend fun load() {
        cached.set(url.first())
    }

    suspend fun save(raw: String) {
        val normalized = normalize(raw)
        cached.set(normalized)
        dataStore.edit { it[KEY_URL] = normalized }
    }

    suspend fun clear() {
        cached.set(null)
        dataStore.edit { it.remove(KEY_URL) }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("backend_url")

        /**
         * Rende utilizzabile quello che l'utente ha digitato.
         *
         * Chi incolla un indirizzo scrive "stats.miosito.it" o
         * "https://stats.miosito.it" indifferentemente, e quasi mai mette lo
         * slash finale: senza normalizzazione il primo caso non è nemmeno una
         * URL valida e il secondo produce percorsi con doppio slash.
         */
        fun normalize(raw: String): String {
            var value = raw.trim()
            if (!value.contains("://")) value = "https://$value"
            if (!value.endsWith("/")) value = "$value/"
            return value
        }

        /**
         * Controllo di forma, volutamente permissivo: accetta anche hostname
         * senza punti come `zimaos.local`. Che il server risponda davvero lo
         * stabilisce [it.spotifystats.app.data.api.BackendProbe], non una
         * regex.
         */
        fun isValid(raw: String): Boolean {
            if (raw.isBlank()) return false
            val url = normalize(raw).toHttpUrlOrNull() ?: return false
            return url.host.isNotBlank()
        }
    }
}
