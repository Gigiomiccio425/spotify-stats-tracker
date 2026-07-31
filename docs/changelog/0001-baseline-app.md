# 0001 — Baseline dell'app

Stato completo dell'app Android al 31 luglio 2026. Questo documento è il punto di partenza per il
porting su iOS: descrive cosa fa l'app e con quale contratto parla al backend.

Gli aggiornamenti successivi saranno file separati che descrivono solo il delta.

---

## 1. Concetti da capire prima di scrivere codice

**Spotify non espone lo storico.** `recently-played` restituisce al massimo le ultime 50 tracce.
Tutto l'archivio lo costruisce il backend interrogando Spotify ogni 15 minuti. **L'app non parla mai
con Spotify**: parla solo con il backend.

**L'app non conserva dati.** Nessun database locale, nessuna cache di statistiche. Sul dispositivo
stanno solo due cose: il token di sessione e l'indirizzo del server. Tutto il resto è ricalcolato dal
backend a ogni richiesta, il che è anche il motivo per cui reinstallare l'app o cambiare dispositivo
non fa perdere nulla.

**L'indirizzo del backend non è una costante.** Ogni utente ha il proprio server. Va chiesto al
primo avvio e salvato; senza, l'app non ha nulla da mostrare.

**Client ID e Secret di Spotify non entrano mai nell'app.** Restano sul backend. In un'app installata
sarebbero estraibili da chiunque.

---

## 2. Configurazione del server

Prima schermata al primo avvio, e raggiungibile da Impostazioni.

L'utente digita un indirizzo. Va normalizzato prima di usarlo:

- rimuovere spazi in testa e coda
- se non contiene `://`, anteporre `https://`
- se non termina con `/`, aggiungerlo

Chi incolla un indirizzo scrive `stats.miosito.it` o `https://stats.miosito.it` indifferentemente, e
quasi mai mette lo slash finale.

**Verificare prima di salvare.** `GET {base}health` deve rispondere `{"ok": true, "now": "..."}`.
Salvare alla cieca porta l'utente in un'app che fallisce ogni schermata senza dirgli che il problema
è l'indirizzo appena inserito.

Il controllo va fatto con un client HTTP **separato** da quello dell'app: quello dell'app riscrive
ogni richiesta verso il server già configurato, non verso il candidato in prova.

**Cambiare indirizzo azzera la sessione.** Il token vale solo per il backend che l'ha emesso.

Messaggi d'errore utili: host non risolto, errore TLS (suggerire `http://` esplicito per server in
rete locale), risposta non JSON ("a questo indirizzo c'è qualcosa, ma non è il backend").

---

## 3. Autenticazione

Flusso OAuth **fuori dall'app**, in una scheda del browser di sistema (su iOS:
`ASWebAuthenticationSession`).

1. Aprire `{base}auth/spotify/start`
2. Il backend redirige a Spotify, riceve il callback, e rimanda all'app via deep link
3. Deep link: `spotifystats://auth?session=<jwt>` oppure `spotifystats://auth?error=<codice>`

**Mai una WebView**: Spotify blocca il login nelle WebView, e una WebView potrebbe leggere le
credenziali digitate.

Codici d'errore da tradurre in messaggi comprensibili:

| Codice | Significato |
|---|---|
| `not_allowlisted` | L'email non è fra i 25 account autorizzati nella dashboard Spotify. È il caso più frequente al primo tentativo |
| `access_denied` | L'utente ha rifiutato su Spotify |
| `invalid_state` | Sessione di login scaduta, riprovare |
| `exchange_failed` | Il backend non ha completato lo scambio |

Il JWT va salvato in modo persistente e inviato come `Authorization: Bearer <jwt>` su ogni chiamata
sotto `/api/`.

**Un 401 significa sessione non più valida**: cancellare il token e tornare al login. Va gestito in
un punto solo, non in ogni schermata.

---

## 4. Contratto API

Base: l'indirizzo configurato. Tutte le risposte sono JSON.

### Parametro `range`

Molti endpoint accettano `?range=`. Valori: `week`, `month`, `4weeks`, `6months`, `year`,
`lifetime`. In alternativa `from` e `to` in ISO 8601.

`lifetime` include anche gli ascolti importati dall'archivio Spotify, precedenti al collegamento.
È il valore predefinito dell'app.

> Esisteva anche `since_tracking`, tolto dall'interfaccia: da quando il backend allinea la data di
> inizio al più vecchio ascolto archiviato dava gli stessi numeri di `lifetime`, e due voci identiche
> fanno solo dubitare di quale sia quella giusta.

### Account

**`GET /api/account/me`**

```json
{
  "id": "uuid",
  "spotifyUserId": "...",
  "displayName": "...",
  "imageUrl": "https://...",
  "trackingSince": "2026-07-31T10:00:00.000Z",
  "periodMode": "calendar",
  "timezone": "Europe/Rome",
  "dailyRecapHour": 0,
  "spotify": { "linked": true, "invalidatedAt": null, "invalidReason": null },
  "sync": {
    "lastRunAt": "...", "status": "ok", "error": null,
    "itemsInserted": 3, "possibleGaps": 0
  }
}
```

`spotify.linked` è **distinto** dalla validità della sessione: si può restare autenticati mentre il
backend non riesce più a interrogare Spotify. Quando è `false` l'archivio ha smesso di crescere, e
va detto in modo prominente, non nascosto nelle impostazioni.

`sync.possibleGaps` conta le volte in cui il poller ha trovato la finestra da 50 tracce piena: ogni
occorrenza è un possibile buco irrecuperabile nell'archivio. Va mostrato, non taciuto.

**`PATCH /api/account/me`** — corpo con uno o più fra `periodMode` (`calendar` | `anniversary`),
`timezone` (IANA), `dailyRecapHour` (0-23).

**`POST /api/account/sync`** — forza un controllo su Spotify subito. Risposta
`{"skipped": false, "inserted": 4, ...}`; `skipped: true` se l'ultimo controllo è più recente di 20
secondi. Il limite protegge il rate limit di Spotify, che è per applicazione e non per utente.

**`GET /api/account/export`** — JSON completo dell'archivio.

**`DELETE /api/account?confirm=<spotifyUserId>`** — cancella account e dati.

### Statistiche

| Endpoint | Risposta |
|---|---|
| `GET /api/stats/overview?range=` | totali, `streak`, `topTracks`, `topArtists` |
| `GET /api/stats/top/tracks?range=&limit=&offset=` | `{ items: [...] }` |
| `GET /api/stats/top/artists?range=&limit=&offset=` | `{ items: [...] }` |
| `GET /api/stats/top/albums?range=&limit=&offset=` | `{ items: [...] }` |
| `GET /api/stats/top/genres?range=&limit=` | `{ items, artistsTotal, artistsWithGenres }` |
| `GET /api/stats/timeline?range=&bucket=day\|week\|month` | `{ points: [{bucket, playCount, msPlayed}] }` |
| `GET /api/stats/clock?range=` | `{ hours: [{hour, playCount}] }` — sempre 24 elementi |
| `GET /api/stats/weekdays?range=` | `{ days: [{weekday, playCount, msPlayed}] }` — 7 elementi, lunedì = 1 |
| `GET /api/stats/release-years?range=` | vedi sotto |
| `GET /api/stats/track/:id` | dettaglio con primo e ultimo ascolto |
| `GET /api/stats/artist/:id` | dettaglio con i brani più ascoltati di quell'artista |

`release-years` restituisce l'**età musicale**:

```json
{
  "years": [{ "year": 1998, "playCount": 12, "msPlayed": 2400000 }],
  "decades": [{ "decade": 1990, "playCount": 40, "share": 25 }],
  "averageYear": 2011, "medianYear": 2015,
  "oldestYear": 1968, "newestYear": 2026,
  "coveredPlays": 160
}
```

`averageYear` è l'anno di pubblicazione medio pesato sugli ascolti. Media e mediana vanno mostrate
**insieme**: divergono in modo istruttivo, perché un solo disco vecchio riascoltato spesso tira
indietro la media e lascia ferma la mediana.

`coveredPlays` è inferiore al totale: gli album senza data di pubblicazione sono esclusi, perché
contarli come anno zero sposterebbe la media di secoli. Va dichiarato in interfaccia.

### Storico

**`GET /api/history?cursor=&limit=`** → `{ items: [...], nextCursor: "..." }`

Paginazione a cursore. Il cursore arriva già pronto dal server: non va costruito dal client.
`nextCursor` è `null` quando non c'è altro.

Ogni elemento ha `id` (numerico, univoco), `playedAt` (ISO 8601), `trackId`, `trackName`,
`artistNames`, `albumName`, `imageUrl`, `msPlayed`.

**Deduplicare per `id` quando si accodano le pagine.** Due richieste ravvicinate possono superare il
controllo "sto già caricando" prima che venga scritto, e una chiave duplicata in una lista pigra fa
terminare l'app.

### Recap

**`GET /api/recaps?type=&limit=`**

```json
{
  "mode": "calendar",
  "trackingSince": "...",
  "archive": { "firstPlayAt": "...", "lastPlayAt": "...", "totalPlays": 1200, "importedPlays": 0 },
  "groups": [
    {
      "type": "day",
      "current": { "key": "2026-07-31", "label": "31 luglio 2026", "partial": false, "inProgress": true },
      "periods": [{ "key": "2026-07-30", "label": "30 luglio 2026", "partial": false, "start": "...", "end": "..." }]
    }
  ]
}
```

Quattro gruppi: `day`, `week`, `month`, `year`.

- `current` è il periodo **in corso**, quindi non concluso: va distinto o omesso.
- `periods` contiene solo periodi **conclusi e con almeno un ascolto**. Un archivio importato copre
  spesso anni con buchi di mesi, e un elenco di settimane vuote sarebbe solo rumore.
- `partial: true` significa che l'archivio è iniziato a periodo già cominciato: i numeri non sono
  confrontabili con gli altri periodi, e va dichiarato.
- `archive.importedPlays > 0` indica che l'utente ha caricato l'archivio Spotify e che i recap
  storici sono disponibili.

**`GET /api/recaps/:type/:key`** → il recap completo.

Formato delle chiavi, in modalità `calendar`:

| Tipo | Chiave |
|---|---|
| `day` | `2026-07-30` |
| `week` | `2026-W31` (settimana ISO) |
| `month` | `2026-07` |
| `year` | `2026` |

In modalità `anniversary` **tutte** le chiavi sono `YYYY-MM-DD`, la data di inizio del periodo.

Il recap contiene `period`, `totals`, `topTracks`, `topArtists`, `topAlbums`, `topGenres`,
`busiestDay`, `minutesChangePct` e `releaseYears`.

**`releaseYears` è presente solo per `month` e `year`.** Su un giorno o una settimana sarebbe
calcolato su troppi pochi ascolti: tre riascolti di un album vecchio sposterebbero la media di
vent'anni.

`minutesChangePct` è `null` quando non esiste un periodo precedente con dati: partire da zero darebbe
sempre "+infinito%".

### Import

**`POST /api/import/streaming-history?filename=`** — corpo: la lista grezza letta da un file
`Streaming_History_Audio_*.json` dell'archivio Spotify.

I file vanno inviati **uno alla volta**: l'archivio ne contiene diversi, e così una richiesta resta
di dimensioni gestibili e un file corrotto non fa fallire tutto.

Risposta: `{ rowsTotal, rowsImported, rowsSkipped }`. Gli scarti sono normali: podcast e ascolti
sotto i 30 secondi non vengono contati.

Timeout generosi, almeno 120 secondi: il server deve interrogare Spotify per i brani che non ha
ancora in catalogo.

---

## 5. Schermate

Navigazione a cinque voci: **Home**, **Statistiche**, **Storico**, **Recap**, **Profilo**.

### Home

Saluto, giorni di archiviazione, selettore di intervallo, quattro riquadri (minuti, ascolti, artisti,
giorni di fila), andamento, orologio degli ascolti, top artisti, top brani.

In cima, in quest'ordine di priorità:

1. **Collegamento a Spotify interrotto**, se `spotify.linked` è `false`
2. **Possibili buchi**, se `sync.possibleGaps > 0`

Con zero ascolti: messaggio che spiega che l'archiviazione parte dal collegamento e che il primo
ascolto comparirà entro un quarto d'ora. Non un grafico vuoto.

### Statistiche

Sei schede: **Tendenze**, Brani, Artisti, Album, Generi, Anni.

**Tendenze** è la prima e la predefinita: totali, andamento con raggruppamento scegliibile fra
giorno, settimana e mese, distribuzione per giorno della settimana, orologio con l'ora di punta
scritta a parole.

**Generi**: se la lista è vuota vanno distinti due casi opposti, altrimenti l'utente pensa che l'app
sia rotta.

- `artistsTotal == 0` → non c'è ancora nulla in archivio
- `artistsTotal > 0` ma lista vuota → Spotify non attribuisce generi a quegli artisti

Quando `artistsWithGenres < artistsTotal`, dirlo sotto la classifica.

**Anni**: l'età musicale, la mediana, gli estremi e la distribuzione per decennio.

Brani, Artisti e Album sono paginati a 50 per volta.

### Storico

Feed cronologico raggruppato per giorno, con intestazioni "Oggi", "Ieri", o la data estesa.
Scorrimento infinito a cursore.

### Recap

Filtro per tipo in cima: Tutti, Giornalieri, Settimanali, Mensili, Annuali.

Aprendo un recap: anteprima della card, selettore di formato e stile, pulsante di condivisione, e
sotto i dati in forma leggibile.

### Profilo

Server, collegamento a Spotify con pulsante per ricollegare, stato dell'archiviazione, modalità dei
periodi, ora di inizio giornata, import dell'archivio, export, elimina account.

---

## 6. Card condivisibile

L'anteprima e l'immagine esportata devono essere **la stessa definizione**, catturata: due
implementazioni parallele divergono al primo cambiamento.

**Formati**: Storia 1080×1920, Post 1080×1080. Nel quadrato le classifiche si accorciano da 5 a 3
voci invece di traboccare.

**Sei stili**: Notte, Neon, Tramonto, Carta, Oceano, Inchiostro. Ognuno definisce gradiente di
sfondo, accento, tre livelli di testo e uno sfondo per le copertine. Nel selettore ogni stile va
rappresentato dal **proprio gradiente**, non dal nome: un elenco di parole non dice come verrà
l'immagine.

**Vincoli di brand Spotify**, non negoziabili:

- le copertine non vanno ritagliate, deformate né coperte da testo
- non cambiano colore al variare del tema
- deve comparire l'attribuzione a Spotify

Contenuto: intestazione con il tipo di periodo, etichetta del periodo, marcatura "parziale" se lo è,
tre totali, età musicale e decennio preferito (solo mese e anno, solo formato storia), top brani, top
artisti, giorno più intenso, attribuzione.

---

## 7. Comportamenti trasversali

**Tira giù per aggiornare** su Home, Statistiche e Storico. Non deve limitarsi a rileggere: prima
chiama `POST /api/account/sync`, altrimenti l'utente vede gli stessi dati fino al giro successivo del
poller e pensa che il gesto non funzioni. Un errore del sync non deve impedire la rilettura.

**Tema solo scuro**, per scelta. Sfondo `#121212`, superfici `#181818` e `#282828`, accento
`#1DB954`, testi `#FFFFFF` / `#B3B3B3` / `#727272`.

**Date**: il server manda sempre ISO 8601 in UTC, la visualizzazione è nel fuso del dispositivo. Una
data che non si riesce a interpretare **non deve far terminare la schermata**: meglio un trattino.

**Minuti stimati**: il poller non sa quanto hai ascoltato davvero, usa la durata del brano. Gli
ascolti importati dall'archivio hanno invece il dato reale. Dichiararlo dove si mostrano i minuti.

---

## 8. Trappole

| Trappola | Conseguenza |
|---|---|
| Chiavi duplicate in una lista pigra | L'app termina. Deduplicare per id quando si accodano pagine |
| Costruire il cursore dello storico a mano | Paginazione rotta. Usare `nextCursor` così com'è |
| Trattare il 401 in ogni schermata | Cicli di login. Gestirlo in un punto solo |
| Mostrare `current` fra i periodi conclusi | Recap di un periodo non finito, numeri che cambiano sotto gli occhi |
| Verificare il server col client dell'app | Il controllo interroga il server vecchio, non quello in prova |
| Ignorare `partial` | Confronti fra periodi non confrontabili |
| Mostrare `releaseYears` su giorni e settimane | Il campo è `null`: gestirlo, non assumerlo presente |
| Nascondere `possibleGaps` | L'utente crede che l'archivio sia completo quando non lo è |
