# Setup completo

## Strumenti necessari

| Cosa | Costo | A cosa serve |
|---|---|---|
| Account Spotify Developer | gratis | Client ID/Secret, lista dei 25 utenti |
| Node.js 20+ | gratis | Backend |
| Postgres | gratis | Neon free tier, oppure Docker in locale |
| Host per il backend | gratis | Una VPS sempre accesa è la scelta migliore — vedi [DEPLOY_ZIMAOS.md](DEPLOY_ZIMAOS.md). In alternativa Render / Fly.io / Koyeb, **verificando i free tier al momento del deploy** |
| Cron esterno | gratis | Serve **solo** sugli host che sospendono il servizio. Su una VPS il poller gira dentro il processo |
| Android Studio + JDK 17 | gratis | App |
| Google Play Console | 25 $ una tantum | **Solo** se pubblichi. Per uso personale basta l'APK |

Sulla macchina attuale è installato **Java 8**: Android Studio richiede **JDK 17**. Android Studio
ne include uno proprio (JBR), quindi installandolo il problema si risolve da sé.

## Backend in locale

```bash
cd backend
npm install
npm run keys          # genera JWT_SECRET, TOKEN_ENC_KEY, CRON_SECRET
cp .env.example .env  # incolla le chiavi e i dati Spotify
```

Database: con Docker `docker compose up -d`, altrimenti crea un progetto su
[neon.tech](https://neon.tech) e incolla la connection string in `DATABASE_URL`.

```bash
npm run dev           # le tabelle si creano da sole alla prima esecuzione
```

Le migrazioni SQL stanno in `backend/drizzle/` e vengono applicate all'avvio, prima che il server
apra la porta. Dopo aver modificato lo schema in `src/db/schema.ts` serve rigenerarle:

```bash
npm run db:generate
```

Verifica: `curl http://127.0.0.1:8787/health`

Poi vedi [SPOTIFY_SETUP.md](SPOTIFY_SETUP.md) per creare l'app Spotify e collegare il primo account.

## Produzione: VPS

Se hai una macchina sempre accesa (VPS, ZimaOS, Raspberry), è la strada giusta: niente sospensioni,
niente cron esterno, e l'archivio non rischia buchi. Procedura completa in
[DEPLOY_ZIMAOS.md](DEPLOY_ZIMAOS.md) — in sostanza `docker compose up -d --build` sullo stack in
[deploy/](../deploy), che tira su Postgres, il backend e Caddy con HTTPS automatico.

## Cron

Serve **solo** se il backend gira su un host che lo sospende quando è inattivo. Su un server sempre
acceso basta `ENABLE_INTERNAL_CRON=true` e il polling avviene dentro il processo.

Negli altri casi qualcuno deve chiamare `POST /cron/poll` ogni 15 minuti.

**cron-job.org** (consigliato, rispetta gli orari):
- URL: `https://<tuo-backend>/cron/poll`
- Metodo: POST
- Header: `Authorization: Bearer <CRON_SECRET>`
- Intervallo: 15 minuti

**GitHub Actions**: il workflow è già pronto in [.github/workflows/poll.yml](../.github/workflows/poll.yml).
Vanno impostati i secret `BACKEND_URL` e `CRON_SECRET` nel repository. Attenzione: il cron di GitHub
non è puntuale e nelle ore di punta ritarda parecchio.

Per provare subito, senza aspettare:

```bash
cd backend && npm run poll
```

## App Android

L'indirizzo del backend **non** si compila dentro l'APK: lo si inserisce nell'app al primo avvio.
Lo stesso APK funziona quindi per chiunque, ognuno puntato al proprio server.

Al primo avvio compare la schermata **Il tuo server**. Inserisci l'indirizzo:

- emulatore: `http://10.0.2.2:8787` (`10.0.2.2` è il localhost del PC visto dall'emulatore;
  nelle build di debug c'è un pulsante che lo precompila)
- device fisico sulla stessa rete: `http://192.168.x.x:8787`
- produzione: `stats.tuodominio.it` — senza prefisso si assume `https`

L'app contatta `/health` e salva solo se il server risponde davvero. Si cambia in qualsiasi momento
da **Profilo → Server**; cambiando indirizzo la sessione viene azzerata, perché il JWT vale solo per
il backend che l'ha emesso.

**Non servono Client ID e Secret di Spotify nell'app**: restano sul backend. Un secret dentro un APK
è estraibile con `unzip` e un decompiler, quindi non sarebbe più un secret.

Per compilare in locale:

1. Apri la cartella `android/` in Android Studio (usa "Open", non "Import").
2. Alla prima apertura Android Studio scarica il Gradle wrapper e l'SDK. Da riga di comando serve
   prima `gradle wrapper`.

Oppure scarica l'APK già compilato dagli artifact della run
[Android](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml).

Il deep link di ritorno dall'OAuth è fisso nel manifest: `spotifystats://auth`. Deve combaciare con
`APP_DEEP_LINK` del backend, che ha lo stesso valore di default.

`usesCleartextTraffic="true"` nel manifest serve a raggiungere backend in HTTP su rete locale. Se
usi solo HTTPS puoi toglierlo.

## Test end-to-end

1. Avvia il backend, apri l'app, tocca **Collega Spotify**, autorizza.
2. Riproduci 3 brani su Spotify lasciandoli andare **oltre i 30 secondi** — sotto quella soglia
   Spotify non li registra affatto.
3. Forza un giro di polling:
   ```bash
   curl -X POST -H "Authorization: Bearer <CRON_SECRET>" http://127.0.0.1:8787/cron/poll
   ```
   La risposta riporta `inserted`.
4. Rilancia lo stesso comando: `inserted` deve essere **0**. Se non lo è, la deduplicazione è rotta.
5. Nell'app: i brani compaiono in **Storico**, i contatori si aggiornano in **Home**.
6. In **Recap**, apri un periodo concluso e tocca **Condividi**: deve uscire un PNG 1080×1920.

Test automatici del backend:

```bash
cd backend
npm test          # 21 test sul calcolo dei periodi
npm run typecheck
```

## Cosa succede se il backend si ferma

Gli ascolti fatti mentre è giù sono persi in modo definitivo se l'interruzione supera le ~2 ore e
mezza. Non c'è modo di recuperarli: Spotify non li espone. L'unica rete di sicurezza è l'archivio
Extended Streaming History, che però arriva con 30 giorni di ritardo.

Se il free tier dell'host si rivela inaffidabile, l'alternativa è un Raspberry Pi o un PC sempre
acceso in casa: al poller basta una connessione e Node.
