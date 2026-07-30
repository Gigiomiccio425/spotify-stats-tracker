package it.spotifystats.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.Danger
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

@Composable
fun LoginScreen(
    onConnect: () -> Unit,
    errorMessage: String?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1F1F), Background)))
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Le tue statistiche,\nper sempre",
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center,
        )
        VerticalSpacer(16)
        Text(
            "Spotify ricorda solo gli ultimi 50 brani che hai ascoltato. " +
                "Da qui in poi li archiviamo noi, senza limiti di tempo.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )

        VerticalSpacer(40)

        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Collega Spotify",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        errorMessage?.let { message ->
            VerticalSpacer(24)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
                    .padding(16.dp),
            ) {
                Text("Collegamento non riuscito", color = Danger, style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }

        VerticalSpacer(32)
        Text(
            "L'archiviazione parte dal momento del collegamento. " +
                "Lo storico precedente si può aggiungere dopo, importando l'archivio " +
                "che Spotify fornisce su richiesta.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
