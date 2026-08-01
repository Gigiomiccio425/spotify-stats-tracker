# 0004 — 403 di Spotify sul catalogo con il token applicativo

Correzione lato backend. Nessuna modifica al contratto API: **le app non vanno cambiate**.

> **L'ipotesi di questo documento si è rivelata sbagliata.** Il ripiego sul token utente è scattato
> e ha preso 403 anche lui: non è il tipo di token. La causa vera e la correzione stanno in
> [0006](0006-rate-limit-spotify.md). Il ripiego resta, perché è comunque giusto averlo, ma è
> diventato a tempo invece che definitivo. Il resto qui sotto è la diagnosi originale, tenuta perché
> il ragionamento che ha portato fuori strada è utile quanto quello giusto.

## Contesto

Durante il recupero di foto e generi degli artisti importati:

```
[import] recupero artisti interrotto SpotifyError: 403 su /artists?ids=55Aa2cq…
{"error": {"status": 403, "message": "Forbidden" } }
```

Il server legge il catalogo (tracce, album, artisti) con un token *applicativo*, ottenuto via
**client credentials**: non è legato a nessun utente, non consuma il rate limit personale e continua
a valere anche se chi ha collegato l'account revoca l'accesso.

Il dettaglio che indirizza la diagnosi: Spotify **rilascia** il token senza problemi, e poi rifiuta
la chiamata a `/v1/artists`. Le credenziali quindi sono giuste; è l'endpoint a non accettare quel
tipo di token per questa applicazione. È coerente con le restrizioni introdotte per le app create
dopo novembre 2024, che colpiscono più duramente le app in Development Mode.

Effetto pratico: gli artisti restavano senza foto e senza generi, e la sezione generi restava vuota.

## Correzione

Le letture del catalogo passano da `withCatalogToken`, in `auth/spotify.ts`:

1. prova con il token applicativo;
2. se la risposta è **403**, se lo annota per il resto della vita del processo e ripete la chiamata
   con il token di un utente collegato qualsiasi;
3. sul catalogo i due token leggono gli stessi identici dati: quale account lo chieda non cambia la
   risposta.

Il 403 si ricorda perché altrimenti ogni singola chiamata pagherebbe un tentativo fallito prima di
riuscire. Solo il 403 attiva il ripiego: un 429 o un 500 restano errori veri e vengono propagati.

Usano `withCatalogToken` sia il recupero degli artisti sia lo scarico dei brani mancanti durante
l'import — entrambi giravano con il token applicativo, quindi entrambi erano esposti.

## Due trappole chiuse insieme

**Raffica verso Spotify.** Il recupero degli artisti girava a tornate consecutive senza pause: 500
artisti per tornata sono dieci chiamate, e una tornata partiva appena finita la precedente. Il rate
limit di Spotify guarda una finestra scorrevole di pochi secondi, quindi era il modo migliore per
farlo scattare. Ora c'è una pausa di un secondo e mezzo fra le tornate.

**Attesa senza tetto sul 429.** Su rate limit si rispettava `Retry-After` qualunque valore avesse.
Quando Spotify chiude i rubinetti sul serio quel valore è di ore: il processo sarebbe rimasto fermo
dentro un `await`, bloccando l'import in corso e il poller, senza che nulla lo segnalasse. Sopra i
60 secondi ora si rinuncia e si segnala l'errore.

## Verifica

Per stabilire se il token applicativo è tornato utilizzabile, sul server, nella cartella del `.env`:

```bash
set -a; . ./.env; set +a
TOKEN=$(curl -s -X POST https://accounts.spotify.com/api/token \
  -d grant_type=client_credentials \
  -u "$SPOTIFY_CLIENT_ID:$SPOTIFY_CLIENT_SECRET" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  'https://api.spotify.com/v1/artists?ids=0TnOYISbd1XYRBk9myaseg'
```

`200` = il token applicativo va, il 403 era momentaneo (quota). `403` = l'applicazione non può usare
le client credentials sul catalogo, e il ripiego sul token utente è la sola strada.

## Per il porting

Niente da cambiare nel client. Vale però la regola generale:

> Un token applicativo (client credentials) non è intercambiabile con un token utente. Se una lettura
> del catalogo risponde 403 mentre il token è stato appena rilasciato senza errori, non sono le
> credenziali a essere sbagliate: è l'endpoint che non accetta quel tipo di token. Serve un ripiego,
> non un nuovo tentativo con le stesse credenziali.
