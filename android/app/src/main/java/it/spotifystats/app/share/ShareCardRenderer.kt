package it.spotifystats.app.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Trasforma la card in un PNG e apre il pannello di condivisione.
 *
 * L'immagine passa da un FileProvider: da Android 7 condividere un `file://`
 * fa crashare l'app ricevente con FileUriExposedException.
 */
object ShareCardRenderer {

    /** Formato verticale delle storie. Instagram, WhatsApp e TikTok lo usano tutti. */
    const val STORY_WIDTH = 1080
    const val STORY_HEIGHT = 1920

    suspend fun writePng(
        context: Context,
        bitmap: Bitmap,
        filename: String,
        targetWidth: Int = STORY_WIDTH,
        targetHeight: Int = STORY_HEIGHT,
    ): File = withContext(Dispatchers.IO) {
        // La cattura avviene alla risoluzione dello schermo: qui si porta al
        // formato standard delle storie, così la card non arriva sgranata o
        // con proporzioni diverse a seconda del telefono.
        val scaled = if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        val directory = File(context.cacheDir, "cards").apply { mkdirs() }
        val file = File(directory, filename)

        FileOutputStream(file).use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        if (scaled !== bitmap) scaled.recycle()
        file
    }

    fun share(context: Context, file: File, text: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Condividi il recap"))
    }
}
