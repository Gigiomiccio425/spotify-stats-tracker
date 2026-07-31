# 0002 — 404 sui recap giornalieri

Correzione lato backend. Nessuna modifica al contratto API: le app non vanno cambiate, ma il
comportamento che vedono cambia.

## Contesto

Aprire un recap giornaliero rispondeva `404 {"error":"Periodo non trovato"}` pur essendo presente
nell'elenco.

Il backend ricostruisce il periodo dalla chiave (`2026-07-30`) prendendo un istante "sicuro" dentro
quella giornata e chiedendosi quale periodo lo contenga. L'istante scelto era **mezzogiorno locale**,
fisso.

Funziona finché la giornata comincia a mezzanotte. Ma l'ora di inizio giornata è configurabile
(`dailyRecapHour`, 0-23): impostandola dopo le 12 — per esempio alle 20 — mezzogiorno del 30 luglio
appartiene ancora alla giornata *del 29*, che inizia alle 20:00 del 29 e finisce alle 20:00 del 30.
Il periodo ricostruito aveva quindi chiave `2026-07-29`, non combaciava con quella richiesta, e la
richiesta veniva rifiutata.

## Correzione

Per i periodi giornalieri l'istante di riferimento non è più mezzogiorno, ma **l'inizio effettivo
della giornata più sei ore**. Sei ore stanno dentro qualsiasi giornata, comprese quelle da 23 ore del
cambio di ora legale.

## Verifica

Due test nuovi in `periods.test.ts`:

- la chiave di un giorno si risolve con ora di inizio 0, 4, 12, 13, 20 e 23
- si risolve anche il 29 marzo 2026, giornata da 23 ore per il cambio di ora legale

## Per il porting

Niente da cambiare nel client. Vale però la pena ricordare la regola generale, che vale per ogni
piattaforma:

> Quando si ricostruisce un periodo da una data, non usare mai un'ora fissa scelta "perché sembra
> lontana dai bordi". I bordi si spostano: fusi orari, ora legale, e qui anche una preferenza
> dell'utente. Va calcolato l'inizio reale del periodo e aggiunto un margine.

Il client deve comunque gestire il 404 su un recap: può capitare se l'utente cambia l'ora di inizio
giornata mentre ha una lista già caricata, e le chiavi di quella lista non corrispondono più a
periodi reali. In quel caso la risposta giusta è ricaricare l'elenco, non mostrare un errore secco.
