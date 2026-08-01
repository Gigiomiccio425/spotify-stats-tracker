# 0003 — Import dello storico precedente in sottofondo

Il caricamento dei file *Extended Streaming History* falliva. Cambia il contratto API e cambia il
modo in cui il client invia il file: **le app vanno modificate**.

## Contesto

Il vecchio flusso faceva tutto dentro una sola richiesta HTTP:

1. l'app leggeva il file scelto dall'utente e ne costruiva l'albero JSON in memoria;
2. lo riserializzava nel corpo della richiesta;
3. il server importava tutto e rispondeva a lavoro concluso.

Tre punti di rottura, tutti sul percorso normale, non su casi limite.

**Il tempo.** Un archivio di anni contiene decine di migliaia di brani distinti. Il server deve
chiederli a Spotify cinquanta per chiamata, rispettando il rate limit: sono minuti, a volte molti.
Nessuna richiesta HTTP resta aperta così a lungo. Il client va in timeout, e un proxy davanti al
server chiude prima ancora — Cloudflare taglia a 100 secondi con un 524. L'import intanto proseguiva
sul server, ma l'app aveva già dichiarato il fallimento: l'utente ricaricava lo stesso file, che
ripartiva da capo.

**La memoria sul telefono.** Un file da 40 MB diventa un albero di oggetti da parecchie centinaia di
MB. Molti telefoni non ce la fanno, e l'`OutOfMemoryError` veniva inghiottito da un `runCatching`.

**Il messaggio.** Qualunque fosse la causa, l'app scriveva `N file non riusciti`. Nessun motivo,
nessun modo di distinguere un formato sbagliato da un timeout da una sessione scaduta.

## Contratto API

### `POST /api/import/streaming-history?filename=<nome>`

Il corpo è il contenuto del file `Streaming_History_Audio_*.json`, inviato **tale e quale**. Va bene
sia l'array in radice sia `{ "entries": [...] }`.

Risposta **202** — il file è stato accodato, non importato:

```json
{ "jobId": "0f3c…", "rowsTotal": 84213, "queuePosition": 1 }
```

Errori possibili:

| Codice | Quando | Cosa fare |
|---|---|---|
| 400 | corpo non JSON, oppure manca il campo `ts` | è il file sbagliato: serve *Extended Streaming History*, non *Account Data* |
| 409 | l'utente ha già un import aperto | attendere che finisca, oppure riagganciarsi a quello in corso |
| 413 | il file supera il limite del proxy (Cloudflare: 100 MB) | caricare i file singolarmente |

Il 409 è voluto: **un solo import aperto per utente**. Le righe restano in memoria nel processo
finché il job non viene lavorato, e dieci file accodati insieme sono centinaia di MB.

### `GET /api/import/jobs/{id}`

```json
{
  "id": "0f3c…",
  "filename": "Streaming_History_Audio_2019-2020_0.json",
  "status": "running",
  "phase": "Brani da Spotify: 3000 di 21480",
  "rowsTotal": 84213,
  "rowsImported": 12000,
  "rowsSkipped": 0,
  "error": null,
  "createdAt": "2026-08-01T…",
  "startedAt": "2026-08-01T…",
  "finishedAt": null,
  "enrichment": { "running": false, "done": 0 }
}
```

`status` ∈ `pending` | `running` | `done` | `error`. 404 se l'id non esiste o non è dell'utente.

`phase` è **testo già pronto da mostrare**, scritto dal server in italiano. Il client non deve
tradurre né interpretare: aggiungendo un passaggio lato server, le app lo mostrano senza essere
ricompilate. Può essere `null`.

### `GET /api/import/jobs`

```json
{ "jobs": [ … ], "enrichment": { "running": true, "done": 4500 } }
```

I 50 più recenti, dal più nuovo. Serve a riagganciare un import lasciato a metà: il primo elemento
con `status` diverso da `done`/`error` è quello ancora aperto.

`enrichment` è il recupero di foto, generi e popolarità degli artisti. Parte quando la coda si
svuota e **prosegue dopo che il job è `done`**: non appartiene a un singolo file, perché un archivio
caricato in dieci pezzi contiene sempre gli stessi artisti.

## Comportamento atteso nel client

**Invio.** Il file va copiato dal disco al socket senza costruirne una rappresentazione in memoria.
Su Android è una `RequestBody` che apre il flusso dentro `writeTo`; su iOS l'equivalente è
`URLSession.uploadTask(with:fromFile:)`. Dichiarare `Content-Type: application/json` e, se
disponibile, `Content-Length`.

Il flusso va **riaperto a ogni scrittura**, non tenuto aperto: la libreria HTTP può dover rimandare
il corpo (un redirect, una connessione caduta e ripresa), e un flusso già consumato manderebbe un
file vuoto senza alcun errore.

**Attesa.** Ricevuto il 202, si interroga `GET /api/import/jobs/{id}` ogni 2 secondi mostrando
`phase`. Si smette quando `status` è `done` o `error`.

Un errore di rete durante l'attesa **non** significa che l'import sia fallito: gira sul server e
prosegue anche a telefono scollegato. Va ritentato (l'app Android si arrende dopo 15 tentativi
consecutivi falliti). Un 401 invece va propagato subito: la sessione è scaduta.

**Più file.** Si inviano in sequenza, aspettando la fine di ciascuno prima del successivo — è
imposto dal 409, non una scelta di comodo. Un file fallito non deve fermare i successivi: si
raccoglie il motivo e si prosegue.

**Ripresa.** All'apertura della schermata si chiama `GET /api/import/jobs`: se c'è un job aperto si
torna a seguirlo. Senza questo, chi chiude l'app durante un import trova al ritorno un pulsante che
risponde soltanto "un import è già in corso".

**Messaggi.** Il backend spiega sempre il motivo in `{"error": "..."}`. Va letto e mostrato: il solo
codice HTTP costringe a guardare i log del server per distinguere un file sbagliato da un import già
in corso. Il 413 fa eccezione — arriva dal proxy, il corpo è HTML, e va tradotto in un messaggio
scritto dal client.

**A fine import** conviene ricaricare il profilo: `tracking_since` è cambiato (vedi sotto), quindi
gli intervalli "dall'inizio" e l'elenco dei recap non sono più quelli di prima.

## Altre due correzioni comprese qui

**`tracking_since` arretra dopo l'import.** Le righe importate precedono il collegamento
dell'account. Senza arretrare la data di inizio archivio, i periodi più vecchi restavano fuori da
statistiche e recap pur avendo i dati in tabella. Stessa correzione già applicata al poller.

**Artisti spariti dal catalogo.** `enrichPendingArtists` interrogava Spotify e, se non tornava
nulla, usciva senza toccare niente. Quegli artisti restavano in cima alla coda per sempre e ogni
tornata successiva ripescava gli stessi, bloccando tutti gli altri. Ora vengono marcati come
tentati. La funzione restituisce quanti artisti sono **usciti dalla coda**, non quanti sono stati
arricchiti: serve a chi cicla per sapere quando fermarsi.

## Trappole

| Trappola | Conseguenza |
|---|---|
| Aspettare l'esito dentro la richiesta di upload | Timeout garantito su archivi veri, con l'import che intanto riesce |
| Leggere il file in memoria per serializzarlo | `OutOfMemory` su file da decine di MB |
| Inviare più file in parallelo | 409, e comunque due import si contenderebbero il rate limit di Spotify |
| Trattare un errore di rete durante l'attesa come import fallito | L'utente ricarica un file che era già stato importato |
| Tenere aperto un solo `InputStream` per il corpo | Al primo rinvio della richiesta parte un file vuoto |
| Mostrare solo il codice HTTP | Formato sbagliato, import in corso e sessione scaduta diventano indistinguibili |
| Considerare l'import finito quando `status` è `done` | Le foto degli artisti arrivano dopo: va detto all'utente, non nascosto |

## Migrazione

`drizzle/0002_luxuriant_devos.sql` aggiunge a `import_jobs` le colonne `phase` e `started_at`. Si
applica da sola all'avvio del backend.

La coda vive in memoria: un riavvio la perde. All'avvio i job rimasti `pending`/`running` vengono
chiusi con stato `error` e il messaggio "Interrotto dal riavvio del server. Ricarica il file." —
senza questa pulizia resterebbero aperti per sempre e, dato che se ne ammette uno solo per utente,
bloccherebbero ogni caricamento successivo.
