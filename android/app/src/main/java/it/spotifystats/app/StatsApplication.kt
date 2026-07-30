package it.spotifystats.app

import android.app.Application
import it.spotifystats.app.auth.AuthManager
import it.spotifystats.app.data.SessionStore
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Container delle dipendenze. Niente framework di dependency injection: con
 * tre oggetti condivisi, Hilt aggiungerebbe solo build più lente.
 */
class StatsApplication : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var repository: StatsRepository
        private set
    lateinit var auth: AuthManager
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        repository = StatsRepository(ApiClient.create(session), session)
        auth = AuthManager(session)

        // Porta in memoria il token salvato: l'interceptor OkHttp lo legge in
        // modo sincrono e non può attendere DataStore.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { session.load() }
    }
}
