# 0006 — Il 403 era il rate limit: un tetto alle chiamate a Spotify

Correzione lato backend. Nessuna modifica al contratto API: **le app non vanno cambiate**.

## Come ci si è arrivati

Tre osservazioni, in ordine.

**Il ripiego di [0004](0004-403-catalogo-token-applicativo.md) è scattato e non è servito.** Il log
mostra `token applicativo rifiutato con 403 sul catalogo: passo al token di un utente collegato`, e
subito dopo la stessa chiamata fallisce di nuovo con 403. Se il rifiuto arriva sia con le client
credentials sia con un token utente, non è il tipo di token.

**Il poller ha continuato a funzionare per tutto il tempo.** `recently-played` risponde, dallo stesso
IP, con un token utente. Quindi né l'IP né le credenziali né la connessione. E spiega perché le
statistiche si popolavano lo stesso: quella risposta contiene già brano, album e artisti completi, e
non ha bisogno del catalogo.

**Il catalogo prima funzionava.** Il primo import ha superato la fase "brani da Spotify" e si è
fermato dopo, sugli artisti. Al secondo tentativo il rifiuto è arrivato subito, sui brani. Una
capacità che l'applicazione non ha mai avuto non funziona il lunedì e smette il martedì: quello è il
profilo di un blocco che scatta con l'uso.

Conclusione: la protezione automatica di Spotify ha chiuso l'accesso all'applicazione dopo troppe
richieste ravvicinate. Non risponde 429 con un `Retry-After` da rispettare — risponde **403
Forbidden secco**, su tutti i token dell'applicazione.

## Cosa lo ha provocato

L'import di un archivio di anni chiede a Spotify migliaia di brani e artisti, cinquanta per
chiamata. Sono centinaia di chiamate, e partivano **una attaccata all'altra, il più veloce
possibile**: nessuna pausa dentro `batched()`, nessuna pausa fra una tornata di artisti e la
successiva.

Il rate limit di Spotify non guarda il totale giornaliero ma una finestra scorrevole di pochi
secondi, e non è documentato quanto valga. Una raffica del genere è il modo migliore per farlo
scattare.

## Correzione

**Un tetto globale a cinque chiamate al secondo.** In `spotify/client.ts`, ogni richiesta prenota
uno slot prima di partire: 200 ms di distanza minima fra due chiamate, valida per tutto il processo,
poller compreso. Lo slot si prenota *prima* di aspettare, così due chiamate concorrenti prendono due
slot diversi invece di svegliarsi insieme.

Un import grosso ci mette qualche minuto in più. Restare bloccati per ore costa molto di più.

**Pausa dopo un rifiuto.** Se il catalogo risponde 403 anche con il token utente, ci si sta lontani
per mezz'ora invece di ribussare: insistere allunga il blocco. Il tempo residuo finisce nel
messaggio d'errore, così l'import lo riporta all'utente invece di lasciar passare un 403 nudo.

**Le bocciature scadono.** Il "token applicativo non va" di 0004 era definitivo per tutta la vita
del processo. Ma quel token non era difettoso: era l'applicazione a essere bloccata. Ora la
bocciatura dura un'ora, poi si riprova.

## Se ricapita

Il blocco lo toglie Spotify da solo, non c'è niente da fare sul server. Per sapere se è ancora
attivo, dalla cartella del `.env`:

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST https://accounts.spotify.com/api/token \
  -d grant_type=client_credentials \
  -u "$SPOTIFY_CLIENT_ID:$SPOTIFY_CLIENT_SECRET" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  'https://api.spotify.com/v1/artists?ids=0TnOYISbd1XYRBk9myaseg'
```

`200` = si può riprendere. `403` = ancora bloccata, va aspettato.

Nel frattempo gli ascolti già archiviati restano, il poller continua, e ricaricare i file
dell'archivio più tardi completa il lavoro senza duplicare nulla (vedi
[0005](0005-import-parziale-invece-di-perdere-tutto.md)).

## Per il porting

Niente da cambiare nel client. La regola però vale ovunque si parli con l'API di Spotify, anche da
un'app:

> Un `403 Forbidden` non significa sempre "non hai il permesso". Su Spotify è anche la risposta della
> protezione automatica quando un'applicazione ha chiesto troppo in poco tempo — senza `Retry-After`,
> senza spiegazioni, e su tutti i token. Prima di concludere che manca un permesso o che un endpoint
> è deprecato, va guardato se la stessa chiamata funzionava poco prima.

E la regola che l'ha causato:

> Il rate limit di Spotify guarda una finestra di pochi secondi. Un ciclo che chiama l'API il più
> velocemente possibile va limitato **a monte**, con una distanza minima fra le chiamate, non a valle
> ritentando dopo l'errore: quando l'errore arriva, il danno è fatto.
