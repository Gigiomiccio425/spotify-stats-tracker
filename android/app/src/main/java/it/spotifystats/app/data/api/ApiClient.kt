package it.spotifystats.app.data.api

import it.spotifystats.app.BuildConfig
import it.spotifystats.app.data.ServerConfig
import it.spotifystats.app.data.SessionStore
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Il backend non è ancora stato configurato in Impostazioni. */
class BackendNotConfiguredException : IOException("Indirizzo del backend non configurato")

object ApiClient {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Retrofit pretende una baseUrl al momento della costruzione, ma il vero
     * indirizzo lo sceglie l'utente e può cambiare mentre l'app gira. Questo
     * segnaposto viene sostituito a ogni richiesta da [BackendUrlInterceptor],
     * e non raggiunge mai la rete.
     */
    private const val PLACEHOLDER_BASE_URL = "http://backend.invalid/"

    fun create(session: SessionStore, server: ServerConfig): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            // L'import dell'archivio manda file da parecchi MB e il server deve
            // interrogare Spotify per i brani mancanti: 30s non bastano.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(BackendUrlInterceptor(server))
            .addInterceptor { chain ->
                val token = session.currentToken
                val request = chain.request().newBuilder().apply {
                    if (token != null) header("Authorization", "Bearer $token")
                }.build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }
}

/**
 * Riscrive ogni richiesta verso l'indirizzo configurato dall'utente.
 *
 * Alternativa scartata: ricostruire Retrofit a ogni cambio di indirizzo.
 * Avrebbe richiesto di ricreare anche il repository e tutti i ViewModel che lo
 * tengono, con il rischio che qualcuno restasse agganciato al client vecchio.
 */
private class BackendUrlInterceptor(private val server: ServerConfig) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val configured = server.currentUrl ?: throw BackendNotConfiguredException()
        val base = configured.toHttpUrlOrNull() ?: throw BackendNotConfiguredException()

        val original = chain.request()

        // `addPathSegments` sul base preserva un eventuale prefisso di percorso
        // (es. https://casa.it/stats/), che una semplice sostituzione di host
        // butterebbe via.
        val rewritten = base.newBuilder()
            .addPathSegments(original.url.encodedPath.trimStart('/'))
            .query(original.url.query)
            .build()

        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
