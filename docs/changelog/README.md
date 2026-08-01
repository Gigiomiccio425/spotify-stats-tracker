# Changelog per il porting

Un file per ogni aggiornamento dell'app, numerato progressivamente.

Servono a portare le stesse modifiche su altre piattaforme senza dover leggere il codice Kotlin:
descrivono **cosa** fa l'app e **quale contratto** ha con il backend, non come è scritta.

## Convenzione

```
docs/changelog/NNNN-titolo-breve.md
```

Ogni file contiene:

- **Contesto** — perché la modifica esiste, quale problema risolve
- **Contratto API** — endpoint toccati, forma esatta di richiesta e risposta
- **Comportamento atteso** — cosa deve fare l'interfaccia, inclusi i casi limite
- **Trappole** — ciò che è facile sbagliare riscrivendolo da zero

Le decisioni non ovvie vanno motivate: senza la ragione, chi porta il codice le "semplifica" e
reintroduce il bug che avevano risolto.

## Indice

| File | Contenuto |
|---|---|
| [0001-baseline-app.md](0001-baseline-app.md) | Stato completo dell'app: schermate, API, comportamenti |
| [0002-fix-404-recap-giornalieri.md](0002-fix-404-recap-giornalieri.md) | 404 sui recap giornalieri con ora di inizio pomeridiana |
| [0003-import-storico-in-sottofondo.md](0003-import-storico-in-sottofondo.md) | Import dell'archivio in coda, con avanzamento: prima falliva sempre |
| [0004-403-catalogo-token-applicativo.md](0004-403-catalogo-token-applicativo.md) | 403 sul catalogo Spotify: ripiego sul token di un utente |
