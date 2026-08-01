# 0005 — Import parziale invece di perdere tutto

Aggiunge un campo alla risposta. **Modifica minima nel client**: mostrare `warning`.

## Contesto

```
[import] job 0b132d2a… fallito SpotifyError: 403 su /tracks?ids=5OaBhC8Njd…
```

L'import si fermava alla prima richiesta di catalogo rifiutata e il job finiva `error`: **zero
ascolti importati**, file da ricaricare da capo.

Sbagliato due volte. Primo, i brani già presenti in catalogo — dagli import precedenti o dal poller —
non hanno bisogno di Spotify: i loro ascolti si potevano archiviare comunque. Secondo, ricaricare lo
stesso file non duplica nulla (`ON CONFLICT DO NOTHING` sulla coppia utente/brano/istante), quindi un
import parziale non è un vicolo cieco: è lavoro fatto che al prossimo tentativo non va rifatto.

## Correzione

Se Spotify rifiuta una richiesta di catalogo, l'import **non si ferma**: smette di chiedere brani
nuovi — insistere è inutile, se rifiuta una richiesta rifiuta anche le prossime — e prosegue
archiviando tutti gli ascolti dei brani che conosce già.

Il job termina `done`, non `error`, con un campo nuovo che spiega cosa manca.

## Contratto API

`GET /api/import/jobs/{id}` e `GET /api/import/jobs` guadagnano `warning`:

```json
{
  "status": "done",
  "rowsImported": 18432,
  "warning": "Spotify ha rifiutato le richieste sul catalogo (403). 21480 brani non recuperati: i loro ascolti non sono stati importati. Ricarica lo stesso file più tardi, gli ascolti già archiviati non vengono duplicati."
}
```

`null` quando l'import è completo. È testo già pronto da mostrare, come `phase`.

`warning` e `error` non vanno confusi:

| Campo | `status` | Significato |
|---|---|---|
| `error` | `error` | Non è stato archiviato nulla. Il file va ricaricato. |
| `warning` | `done` | Archiviato quello che si poteva. Ricaricare lo stesso file più tardi completa il resto. |

## Comportamento atteso nel client

A fine import, se `warning` non è nullo, va mostrato insieme al conteggio — non al posto suo, e non
nascosto. Un archivio importato a metà che si presenta come completo è peggio di un errore: l'utente
guarda statistiche sbagliate senza sapere perché.

Con più file, gli avvisi identici vanno mostrati una volta sola: il rifiuto è lo stesso per tutti.

## Trappole

| Trappola | Conseguenza |
|---|---|
| Trattare `warning` come `error` | L'utente ricarica tutto convinto che non sia passato niente |
| Non mostrarlo affatto | Statistiche incomplete e nessuna spiegazione |
| Fermarsi al primo rifiuto senza salvare il parziale | Si perde anche il lavoro che non dipendeva da Spotify |

## Migrazione

`drizzle/0003_illegal_grey_gargoyle.sql` aggiunge la colonna `warning` a `import_jobs`. Si applica
da sola all'avvio.

## Nota sul 403

Questo cambiamento rende l'import tollerante al rifiuto, **non lo risolve**. Vedi
[0004](0004-403-catalogo-token-applicativo.md) per il ripiego sul token utente. Se il 403 arriva
anche con il token di un utente collegato, allora non è il tipo di token: è l'applicazione a essere
bloccata da Spotify, e va guardata la dashboard.
