package it.spotifystats.app.data.update

import it.spotifystats.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Una versione pubblicata più recente di quella installata. */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val notes: String?,
)

sealed interface UpdateStatus {
    /** Controllo in corso. */
    data object Checking : UpdateStatus

    /** Nessuna versione più recente. */
    data object UpToDate : UpdateStatus

    data class Available(val update: AvailableUpdate) : UpdateStatus

    /** Il controllo non è riuscito: non dice nulla sull'esistenza di aggiornamenti. */
    data class Failed(val message: String) : UpdateStatus
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Chiede a GitHub qual è l'ultima versione pubblicata.
 *
 * Client OkHttp tutto suo, non quello dell'app: quello riscrive ogni richiesta
 * verso il backend configurato dall'utente, e qui si sta interrogando GitHub.
 *
 * Il numero di build sta in coda al tag della release (`v0.2.0-42`), che è lo
 * stesso `versionCode` con cui Android decide se un APK è più recente. Il nome
 * della versione non basta a confrontare: "0.2.0" e "0.10.0" ordinati come
 * testo darebbero il risultato sbagliato.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos"

    private val TAG_FORMAT = Regex("""^v(.+)-(\d+)$""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$API/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                // 404 quando non è ancora stata pubblicata nessuna release:
                // non è un guasto, semplicemente non c'è niente da scaricare.
                if (response.code == 404) return@withContext UpdateStatus.UpToDate
                if (!response.isSuccessful) error("GitHub ha risposto ${response.code}")

                val release = json.decodeFromString<GithubRelease>(response.body?.string().orEmpty())
                val match = TAG_FORMAT.find(release.tagName)
                    ?: error("Tag della release in un formato inatteso: ${release.tagName}")

                val versionCode = match.groupValues[2].toInt()
                if (versionCode <= BuildConfig.VERSION_CODE) return@withContext UpdateStatus.UpToDate

                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: error("La release ${release.tagName} non contiene un APK")

                UpdateStatus.Available(
                    AvailableUpdate(
                        versionName = match.groupValues[1],
                        versionCode = versionCode,
                        downloadUrl = apk.browserDownloadUrl,
                        notes = release.body?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }.getOrElse { UpdateStatus.Failed(it.message ?: "Controllo non riuscito") }
    }
}
