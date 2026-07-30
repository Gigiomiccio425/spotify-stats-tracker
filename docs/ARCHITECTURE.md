# Architettura

## Il vincolo da cui discende tutto

`GET /v1/me/player/recently-played` restituisce **al massimo le ultime 50 tracce**. Non esiste alcun
endpoint per chiedere a Spotify cosa hai ascoltato la settimana scorsa.

50 tracce sono circa **2 ore e mezza** di musica. Se nessuno interroga l'API entro quella finestra,
quegli ascolti **non sono più recuperabili in nessun modo**.

Questo esclude l'idea più naturale — far fare tutto all'app sul telefono. `WorkManager` garantisce
solo che il lavoro *prima o poi* verrà eseguito: Doze, il risparmio energetico dei produttori e il
telefono spento producono ritardi di ore. Il risultato sarebbe un archivio con buchi permanenti.

Quindi il polling gira **sul server**, con i refresh token degli utenti, ogni 15 minuti. L'app
Android è un client di sola lettura.

```
                              [backend Node]
   scheduler interno ogni 15 min ──┤
   (server sempre acceso)          │  refresh token
                                   │  recently-played (max 50)
   oppure                          ▼
   [cron esterno] ──POST /cron/poll──> [Spotify Web API]
   (host che sospendono)               │
                                       ▼
                                 [Postgres: plays]
                                       ▲
                                       │ REST + JWT
                                [app Android]
```

Le due modalità sono alternative, scelte da `ENABLE_INTERNAL_CRON`. Il timer interno è preferibile
quando possibile: non dipende da un servizio di terze parti e rispetta l'intervallo. Su un host che
sospende il processo va invece disattivato, perché il timer morirebbe con lui: lì è la chiamata HTTP
del cron esterno a risvegliare il servizio, e quindi solo quella funziona.

## Componenti

| Cosa | Dove | Perché |
|---|---|---|
| Poller | [backend/src/jobs/poll.ts](../backend/src/jobs/poll.ts) | L'unico pezzo che deve girare senza interruzioni |
| Scheduler | [backend/src/jobs/scheduler.ts](../backend/src/jobs/scheduler.ts) | Timer interno per i server sempre accesi, con guardia contro i giri accavallati |
| Archivio | tabella `plays`, append-only | Una riga per ascolto, per sempre |
| Catalogo | `tracks`, `artists`, `albums` | Condiviso fra utenti: stesso brano, una riga sola |
| Periodi | [backend/src/lib/periods.ts](../backend/src/lib/periods.ts) | Fusi orari e confini di settimana/mese/anno |
| Client | [android/](../android) | Legge il backend, non parla mai con Spotify |

## Idempotenza

L'inserimento degli ascolti si appoggia a un vincolo del database, non a un controllo applicativo:

```sql
UNIQUE (user_id, track_id, played_at)
```

con `ON CONFLICT DO NOTHING`. Rieseguire il poller sulla stessa finestra non produce duplicati,
qualunque cosa succeda in mezzo: un timeout, un riavvio, due cron che partono insieme.

`played_at` è il timestamp di **fine** traccia riportato da Spotify. È stabile e identico a ogni
chiamata, il che è esattamente ciò che serve a una chiave di deduplicazione.

## Rilevamento dei buchi

Quando la risposta contiene esattamente 50 elementi, la finestra era piena: fra un controllo e il
successivo è passato troppo tempo e qualcosa si è quasi certamente perso. Il poller lo registra in
`poll_runs.hit_page_limit` e l'app lo mostra in chiaro nella Home e in Impostazioni.

Dichiarare un dato incompleto è più utile che presentare numeri che l'utente crederebbe esatti.

## Cosa si può recuperare del passato

Tre livelli, in ordine di completezza:

1. **`recently-played` al primo collegamento** — gli ultimi 50 ascolti. Reali, entrano in `plays`.
2. **`/me/top/*`** — una fotografia dei gusti su 4 settimane / 6 mesi / anni. Non sono ascolti
   datati: finiscono in `top_snapshots`, marcati *pre-tracking*, e **non** entrano nelle statistiche.
   Servono solo a non mostrare un'app vuota il primo giorno.
3. **Extended Streaming History** — l'archivio completo che Spotify consegna su richiesta in circa
   30 giorni. Si carica dall'app e finisce in `plays` con `source = 'import'` e i millisecondi
   realmente ascoltati. È l'unico modo per avere anni di storico.

## Stima del tempo di ascolto

`recently-played` non dice quanto è stato ascoltato davvero. Il poller usa la durata della traccia
come stima e marca la riga con `ms_estimated = true`. Gli ascolti importati dall'archivio hanno il
dato reale e sono marcati `false`.

La differenza conta: chi salta metà dei brani ha minuti stimati sistematicamente più alti del vero.
Il campionatore `now-playing` (non ancora implementato) è il modo per correggere questo, al prezzo di
una chiamata al minuto per ogni utente attivo.

## Endpoint

```
POST /cron/poll                  # protetto da CRON_SECRET
POST /cron/poll/:userId          # per il test manuale

GET  /auth/spotify/start
GET  /auth/spotify/callback      # Redirect URI registrato su Spotify

GET  /api/account/me
PATCH /api/account/me            # periodMode, timezone
GET  /api/account/export         # JSON completo
DELETE /api/account?confirm=<spotifyUserId>

GET  /api/stats/overview?range=
GET  /api/stats/top/{tracks|artists|albums|genres}
GET  /api/stats/timeline?bucket=day|week|month
GET  /api/stats/clock
GET  /api/stats/track/:id
GET  /api/stats/artist/:id
GET  /api/history?cursor=
GET  /api/recaps
GET  /api/recaps/{week|month|year}/:key
POST /api/import/streaming-history?filename=
GET  /api/import/jobs
```

## Sicurezza

- I token Spotify sono cifrati a riposo con AES-256-GCM ([auth/crypto.ts](../backend/src/auth/crypto.ts))
  e **non lasciano mai il server**. L'app riceve un JWT applicativo.
- OAuth con PKCE: anche intercettando il `code` durante il redirect non se ne fa nulla senza il
  `code_verifier`, che resta sul server.
- Il login usa una Chrome Custom Tab, non una WebView: Spotify blocca le WebView, e una WebView
  potrebbe leggere le credenziali digitate.
- Se un utente revoca l'accesso da Spotify, il refresh fallisce con `invalid_grant`: la riga viene
  marcata `invalidated_at` e il poller smette di riprovare invece di sbattere contro l'API a ogni giro.

## Scelte che potrebbero sorprendere

**Nessuna tabella di rollup.** Con 25 utenti e qualche decina di migliaia di ascolti a testa, gli
indici su `plays` bastano e la fonte di verità resta una sola. Il posto giusto per una cache
aggregata, se servisse, è accanto a `plays` in [schema.ts](../backend/src/db/schema.ts).

**Un ascolto conta per ogni artista accreditato.** Un brano con un featuring conta 1 per l'artista
principale e 1 per l'ospite. È quello che gli utenti si aspettano — l'ospite deve comparire nella
propria classifica — ma significa che la somma degli ascolti per artista supera il totale.

**Niente libreria di grafici sull'app.** Servono due istogrammi; sono disegnati su `Canvas` in
[Charts.kt](../android/app/src/main/java/it/spotifystats/app/ui/components/Charts.kt). Una
dipendenza esterna porterebbe vincoli di versione su Compose senza dare nulla in cambio.

**Niente Audio Features.** Non è una scelta: Spotify le ha rimosse per le app create dopo novembre
2024, insieme a Recommendations, Related Artists e le anteprime da 30 secondi.
