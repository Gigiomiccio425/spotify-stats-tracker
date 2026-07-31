# Spotify Stats

[![Backend](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/backend.yml/badge.svg)](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/backend.yml)
[![Android](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml/badge.svg)](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml)
[![Immagine backend](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/docker.yml/badge.svg)](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/docker.yml)

Statistiche di ascolto Spotify con archivio storico illimitato e recap condivisibili sui social.
App Android + backend self-hosted.

## Il problema

Spotify non espone lo storico di ascolto. `recently-played` restituisce **al massimo le ultime 50
tracce**, circa 2 ore e mezza di musica. Se nessuno interroga l'API entro quella finestra, quegli
ascolti sono **persi per sempre**.

Per questo il polling gira su un server 24/7 e non sul telefono: `WorkManager` viene rinviato da
Doze e dal risparmio energetico, e produrrebbe buchi permanenti nell'archivio.

## Struttura

```
backend/     Node + TypeScript + Hono + Drizzle + Postgres
  src/jobs/poll.ts        il poller: legge Spotify e archivia in `plays`
  src/lib/periods.ts      confini di settimana/mese/anno (21 test)
  src/lib/stats.ts        query aggregate
  src/auth/               OAuth PKCE, JWT di sessione, cifratura dei token

android/     Kotlin + Jetpack Compose, tema scuro
  ui/home ui/top ui/history ui/recap ui/detail ui/settings
  ui/recap/ShareCard.kt   la card condivisibile
  share/                  cattura in PNG 1080×1920 e condivisione

deploy/      stack Docker per una VPS: Postgres + backend + Caddy (HTTPS automatico)

docs/        changelog/ · SETUP.md · SPOTIFY_SETUP.md · DEPLOY_ZIMAOS.md · ARCHITECTURE.md
```

## Funzioni

- Indirizzo del backend configurabile **dentro l'app**: lo stesso APK vale per chiunque, ognuno
  puntato al proprio server
- Collegamento dell'account Spotify via OAuth (PKCE, Chrome Custom Tabs)
- Archiviazione di ogni ascolto dal momento del collegamento, **senza limiti di retention**
- Classifiche brani / artisti / album / generi su intervalli arbitrari
- Storico cronologico completo, raggruppato per giorno
- Recap **settimanali, mensili e annuali** con card condivisibile in formato storia
- Periodi calendario oppure ancorati al giorno di registrazione (opzione in Impostazioni)
- Import dell'archivio *Extended Streaming History* di Spotify, per anni di storico pregresso
- Export completo in JSON e cancellazione totale dei dati
- Segnalazione esplicita dei possibili buchi nell'archivio

## Build automatiche

GitHub Actions compila tutto a ogni push su `main`:

- **Backend** — typecheck, 21 test sul calcolo dei periodi, build
- **Android** — APK di debug scaricabile dagli artifact della run
- **Immagine backend** — pubblicata su `ghcr.io/gigiomiccio425/spotify-stats-tracker/backend`

L'APK non serve compilarlo in locale: apri l'ultima run
[Android](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml)
e scarica l'artifact `app-debug-apk`.

## Avvio

Vedi [docs/SETUP.md](docs/SETUP.md) per la procedura completa. In breve:

```bash
cd backend
npm install
npm run keys            # genera i segreti
cp .env.example .env    # compila con i dati Spotify e il DATABASE_URL
npm run dev             # le migrazioni si applicano da sole all'avvio
```

Poi [docs/SPOTIFY_SETUP.md](docs/SPOTIFY_SETUP.md) per creare l'app sulla dashboard Spotify, e
Android Studio sulla cartella `android/`.

**In produzione su una VPS** (consigliato) bastano due comandi:

```bash
curl -fsSL -O https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy/install.sh
bash install.sh
```

Lo script chiede dominio, Client ID e Client Secret, genera i segreti, scrive il `.env` e avvia
Postgres, backend e Caddy con HTTPS automatico. Le tabelle si creano da sole all'avvio e il poller
gira dentro il processo: nessun cron esterno. Dettagli in
[docs/DEPLOY_ZIMAOS.md](docs/DEPLOY_ZIMAOS.md).

Sugli host che sospendono il servizio quando è inattivo il timer interno non basta: lì serve un cron
esterno che chiami `POST /cron/poll` ogni 15 minuti — il workflow GitHub Actions è già pronto in
[.github/workflows/poll.yml](.github/workflows/poll.yml).

## Limiti da conoscere

- **Massimo 25 utenti.** Un'app Spotify in Development Mode accetta solo account dichiarati a mano
  nella dashboard. Oltre serve l'Extended Quota Mode, con approvazione manuale di Spotify.
- **Niente Audio Features.** Spotify ha rimosso danceability, energy, tempo, Recommendations,
  Related Artists e le anteprime da 30 secondi per le app create dopo novembre 2024.
- **I minuti sono stimati.** `recently-played` non dice quanto è stato ascoltato davvero: si usa la
  durata della traccia. Gli ascolti importati dall'archivio hanno invece il dato reale.
- **Le tracce sotto i 30 secondi non esistono** per Spotify e non compaiono da nessuna parte.
