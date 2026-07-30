# Configurazione dell'app Spotify

Serve un account Spotify normale, anche gratuito. Non costa nulla.

## 1. Crea l'app

1. Vai su <https://developer.spotify.com/dashboard> e accedi.
2. **Create app**.
3. Compila:
   - **App name** e **description**: quello che vuoi.
   - **Redirect URI**: aggiungine **due**
     - `http://127.0.0.1:8787/auth/spotify/callback` — per lo sviluppo in locale
     - `https://<tuo-dominio>/auth/spotify/callback` — per il backend in produzione
   - **Which API/SDKs are you planning to use**: spunta **Web API**.
4. Salva. Dalla pagina **Settings** copia **Client ID** e **Client Secret** nel tuo
   `backend/.env`.

Il Redirect URI deve combaciare **carattere per carattere** con `SPOTIFY_REDIRECT_URI`.
Uno slash finale di differenza fa fallire il login con `INVALID_CLIENT: Invalid redirect URI`.

## 2. Aggiungi gli utenti (obbligatorio)

Un'app appena creata è in **Development Mode**: possono usarla al massimo **25 account**, e vanno
dichiarati uno per uno. Chi non è in lista riceve un **403** e non riesce a collegarsi.

1. Dashboard → la tua app → **Settings** → **User Management**.
2. Aggiungi nome ed email di ogni persona. Deve essere **l'email dell'account Spotify**, non
   un'altra.

Il tuo account è già incluso e non conta nei 25.

Per superare i 25 utenti serve chiedere l'**Extended Quota Mode**, con revisione manuale da parte di
Spotify. Fuori dallo scopo di questo progetto.

## 3. Cosa non è più disponibile

Per le app create dopo **novembre 2024** Spotify ha rimosso alcuni endpoint. Quindi l'app **non può**
mostrare:

- danceability, energy, valence, tempo (Audio Features e Audio Analysis)
- brani consigliati (Recommendations)
- artisti correlati (Related Artists)
- anteprime audio da 30 secondi

I **generi** restano disponibili, ma solo a livello di artista: Spotify non assegna un genere alle
singole tracce. Le statistiche per genere sono quindi un'aggregazione dei generi degli artisti
ascoltati.

## 4. Il limite che determina tutta l'architettura

`GET /v1/me/player/recently-played` restituisce **al massimo 50 tracce**, e solo le più recenti.
Non esiste alcun modo di chiedere a Spotify che cosa hai ascoltato la settimana scorsa.

50 tracce sono circa **2 ore e mezza** di musica. Se il poller resta fermo più a lungo, gli ascolti
di quella finestra **non sono più recuperabili in nessun modo**. Da qui discende tutto il resto:
il poller gira sul server ogni 15 minuti, non sul telefono.

Altri dettagli utili:

- `played_at` è il momento in cui la traccia è **finita**, non in cui è iniziata.
- Le tracce ascoltate per meno di **30 secondi** non compaiono affatto.
- Non viene detto quanto è stato ascoltato davvero: l'app usa la durata della traccia come stima e
  lo dichiara nel campo `msEstimated`.

## 5. Recuperare lo storico passato

Spotify consegna, su richiesta, un archivio completo di tutto ciò che hai ascoltato da sempre:

1. <https://www.spotify.com/account/privacy/>
2. Spunta **Extended streaming history** (non "Account data", che contiene solo l'ultimo anno).
3. Conferma via email. Il file arriva in circa **30 giorni**.

Quando arriva, si carica dall'app: gli ascolti entrano in archivio con `source = 'import'` e si
uniscono a quelli raccolti dal poller.

## 6. Verifica

Con il backend avviato:

```bash
curl http://127.0.0.1:8787/health
```

poi apri nel browser `http://127.0.0.1:8787/auth/spotify/start`. Dopo aver autorizzato verrai
rimandato al deep link `spotifystats://auth?session=...`: il browser dirà che non sa aprirlo, ed è
normale finché l'app Android non è installata. Il token nella URL è la sessione: copialo e usalo per
provare le API.

```bash
curl -H "Authorization: Bearer <session>" http://127.0.0.1:8787/api/account/me
```
