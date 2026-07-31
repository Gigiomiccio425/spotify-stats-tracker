package it.spotifystats.app

import android.app.Application
import it.spotifystats.app.auth.AuthManager
import it.spotifystats.app.data.ServerConfig
import it.spotifystats.app.data.SessionStore
import it.spotifystats.app.data.StatsRepository
import it.spotifystats.app.data.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Container delle dipendenze. Niente framework di dependency injection: con
 * quattro oggetti condivisi, Hilt aggiungerebbe solo build più lente.
 */
class StatsApplication : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var server: ServerConfig
        private set
    lateinit var repository: StatsRepository
        private set
    lateinit var auth: AuthManager
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        server = ServerConfig(this)
        repository = StatsRepository(ApiClient.create(session, server), session)
        auth = AuthManager(session, server)

        // Porta in memoria i valori salvati: l'interceptor OkHttp li legge in
        // modo sincrono e non può attendere DataStore.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            server.load()
            session.load()
        }
    }
}
