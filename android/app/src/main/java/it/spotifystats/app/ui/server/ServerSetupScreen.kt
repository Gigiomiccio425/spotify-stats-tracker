package it.spotifystats.app.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.spotifystats.app.BuildConfig
import it.spotifystats.app.ui.appViewModel
import it.spotifystats.app.ui.components.VerticalSpacer
import it.spotifystats.app.ui.theme.Accent
import it.spotifystats.app.ui.theme.Background
import it.spotifystats.app.ui.theme.Danger
import it.spotifystats.app.ui.theme.SurfaceElevated
import it.spotifystats.app.ui.theme.TextSecondary
import it.spotifystats.app.ui.theme.TextTertiary

/**
 * Prima schermata al primo avvio, e raggiungibile da Impostazioni per cambiare
 * server.
 *
 * L'APK è lo stesso per tutti: l'indirizzo del backend non può stare in una
 * costante di compilazione, perché chi installa l'app punta al proprio server.
 */
@Composable
fun ServerSetupScreen(
    onConfigured: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val viewModel = appViewModel { ServerSetupViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onConfigured()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1F1F), Background)))
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Il tuo server", style = MaterialTheme.typography.headlineLarge)
        VerticalSpacer(8)
        Text(
            "Questa app non ha un servizio centrale: i tuoi ascolti vengono archiviati da un " +
                "backend che gestisci tu. Inserisci il suo indirizzo.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        VerticalSpacer(24)

        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            label = { Text("Indirizzo del backend") },
            placeholder = { Text("stats.miodominio.it") },
            singleLine = true,
            enabled = !state.checking,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                cursorColor = Accent,
                focusedLabelColor = Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "Senza prefisso si assume https. Per un server in rete locale scrivi l'indirizzo " +
                "per esteso, ad esempio http://192.168.1.50:8787",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = 6.dp),
        )

        state.error?.let { message ->
            VerticalSpacer(16)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
                    .padding(12.dp),
            ) {
                Text(
                    "Verifica non riuscita",
                    style = MaterialTheme.typography.titleMedium,
                    color = Danger,
                )
                Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
        }

        VerticalSpacer(24)

        Button(
            onClick = viewModel::verifyAndSave,
            enabled = !state.checking && state.input.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.checking) {
                CircularProgressIndicator(
                    color = Color.Black,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text("  Verifica in corso…", style = MaterialTheme.typography.labelLarge)
            } else {
                Text(
                    "Verifica e continua",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        onCancel?.let {
            TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Text("Annulla", color = TextSecondary)
            }
        }

        VerticalSpacer(24)
        Text(
            "Non servono Client ID né Client Secret di Spotify: restano sul backend. " +
                "Un secret dentro un'app installata sarebbe leggibile da chiunque.",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
        )

        if (BuildConfig.DEBUG) {
            TextButton(onClick = { viewModel.onInputChange(BuildConfig.DEFAULT_API_BASE_URL) }) {
                Text("Usa l'indirizzo di sviluppo", color = TextTertiary)
            }
        }
    }
}
