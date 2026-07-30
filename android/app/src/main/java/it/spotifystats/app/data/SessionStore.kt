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
import java.util.concurrent.atomic.AtomicReference

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * Conserva il JWT di sessione dell'app. Non contiene token Spotify: quelli
 * restano sul server e non arrivano mai sul telefono.
 *
 * Il valore è tenuto anche in memoria perché l'interceptor OkHttp gira su un
 * thread senza coroutine, dove leggere DataStore bloccherebbe.
 */
class SessionStore(context: Context) {

    private val dataStore = context.applicationContext.dataStore
    private val cached = AtomicReference<String?>(null)

    // `onEach` tiene allineata la copia in memoria: la UI colleziona questo
    // flow all'avvio, quindi la cache è pronta prima della prima richiesta.
    val token: Flow<String?> = dataStore.data.map { it[KEY_TOKEN] }.onEach { cached.set(it) }

    val currentToken: String? get() = cached.get()

    /** Da chiamare all'avvio, prima della prima richiesta di rete. */
    suspend fun load() {
        cached.set(token.first())
    }

    suspend fun save(value: String) {
        cached.set(value)
        dataStore.edit { it[KEY_TOKEN] = value }
    }

    suspend fun clear() {
        cached.set(null)
        dataStore.edit { it.remove(KEY_TOKEN) }
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("session_token")
    }
}
