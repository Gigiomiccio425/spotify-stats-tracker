# 0008 — Conteggi dell'import veritieri

Aggiunge un campo alla risposta. **Modifica minima nel client**: usare `rowsDuplicate`.

## Contesto

Dopo un import l'app scriveva `0 ascolti importati`. Sembrava un fallimento. Non lo era: quel numero
conta le righe **nuove**, e ricaricando un file già importato non c'è niente di nuovo da aggiungere —
l'inserimento scarta i doppioni sulla chiave utente/brano/istante. Zero righe nuove era il risultato
giusto, presentato come se fosse un guasto.

Non è un dettaglio di forma. Chi legge "0 importati" ricarica il file, e ricaricare mentre Spotify sta
limitando l'applicazione è esattamente la cosa da non fare.

C'era anche un secondo conteggio sbagliato: `rowsSkipped` valeva `righe del file − righe nuove`,
quindi comprendeva anche i doppioni e i brani non recuperabili, tutto mescolato.

## Contratto API

`GET /api/import/jobs/{id}` e `GET /api/import/jobs` guadagnano `rowsDuplicate`. I quattro conteggi
ora si sommano in modo leggibile:

| Campo | Significato |
|---|---|
| `rowsTotal` | righe presenti nel file |
| `rowsImported` | righe **nuove** finite in archivio |
| `rowsDuplicate` | righe che c'erano già |
| `rowsSkipped` | righe non archiviabili: podcast, ascolti sotto i 30 secondi, brani non recuperati |

`rowsImported + rowsDuplicate + rowsSkipped == rowsTotal`.

## Comportamento atteso nel client

Il riepilogo va costruito su entrambi i numeri:

- `imported == 0 && duplicate > 0` → «Nessun ascolto nuovo: questi N erano già in archivio.»
- `imported == 0 && duplicate == 0` → «Nessun ascolto importato.»
- `imported > 0 && duplicate > 0` → «N ascolti importati, M erano già in archivio.»
- altrimenti → «N ascolti importati.»

## Anche: la pausa dice quanto manca

Quando il backend si mette in pausa dopo un rifiuto di Spotify (vedi
[0006](0006-rate-limit-spotify.md)), il messaggio conteneva i minuti rimanenti — ma veniva sostituito
da un generico "Spotify ha rifiutato le richieste sul catalogo (403)" prima di arrivare all'utente,
perché la pausa interna era indistinguibile da un rifiuto appena arrivato.

Ora la pausa ha un tipo suo e il suo testo passa intatto: «Spotify ha bloccato l'accesso al catalogo.
Si riprova fra 22 minuti.» Il client non cambia — è sempre il campo `warning` — ma quello che ci legge
dentro adesso è utile.

## Migrazione

`drizzle/0004_pink_makkari.sql` aggiunge `rows_duplicate` a `import_jobs`. Si applica da sola
all'avvio.

## Trappola

| Trappola | Conseguenza |
|---|---|
| Presentare "0 nuovi" come "0 importati" | L'utente ricarica un file già a posto, e insiste proprio quando Spotify sta limitando l'applicazione |
| Sostituire il messaggio di un errore con una propria riformulazione | Si perde l'unica informazione che conteneva — qui, i minuti che mancano |
