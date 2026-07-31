# Deploy sulla VPS con ZimaOS

ZimaOS gira su Docker, quindi tutto lo stack si installa come un unico progetto
`docker compose`. Rispetto a un host gratuito è nettamente meglio: il servizio non viene mai
sospeso, quindi il poller può girare **dentro il processo** e non serve alcun cron esterno.

## Se le porte 80 e 443 sono già occupate

Su ZimaOS di solito lo sono: le usa la dashboard. Verifica chi le tiene:

```bash
sudo ss -tlnp | grep -E ':(80|443) '
docker ps --format '{{.Names}}\t{{.Ports}}' | grep -E ':(80|443)->'
```

Due strade.

**Tunnel Cloudflare — nessuna porta da aprire.** Il container `cloudflared` apre una connessione in
*uscita* verso Cloudflare, che espone il backend su `https://<DOMAIN>` e ci mette il certificato.
Niente porte in ingresso, niente record A, niente Let's Encrypt: funziona anche dietro CGNAT.
Richiede che il dominio sia gestito da Cloudflare, piano gratuito. Usa
[deploy/docker-compose.tunnel.yml](../deploy/docker-compose.tunnel.yml), che non contiene affatto
Caddy.

```bash
# Zero Trust > Networks > Tunnels > Create a tunnel > Cloudflared
# Public Hostname:  <DOMAIN>  ->  HTTP  ->  backend:8787
# Copia il token in .env come CLOUDFLARE_TUNNEL_TOKEN
docker compose -f docker-compose.tunnel.yml up -d
```

**Spostare la dashboard di ZimaOS su un'altra porta**, liberando 80 e 443 per Caddy. Tiene lo stack
standard, ma se sbagli qualcosa perdi l'accesso all'interfaccia da cui stai lavorando: fallo solo
con accesso SSH funzionante.

Nota sul tunnel: il piano gratuito di Cloudflare limita il corpo delle richieste a 100 MB. I file
dell'archivio *Extended Streaming History* stanno di solito sotto i 30 MB, ma se ne hai uno più
grande dividilo prima di caricarlo.

## Cosa serve prima

- **Un dominio che punta all'IP della VPS** (record A). Serve davvero: Spotify accetta come Redirect
  URI solo indirizzi HTTPS, con l'unica eccezione del loopback `127.0.0.1` per lo sviluppo locale.
  Un IP nudo o un URL in HTTP viene rifiutato.
- **Porte 80 e 443 aperte** verso la VPS. Caddy le usa per ottenere e rinnovare il certificato.
- Accesso SSH alla macchina.

## Due strade

**A — immagine già compilata (consigliata).** GitHub Actions compila il backend a ogni push e
pubblica l'immagine su `ghcr.io/gigiomiccio425/spotify-stats-tracker/backend:latest`. La VPS la
scarica e basta: niente compilazione sulla macchina, aggiornamenti in pochi secondi. Usa
[deploy/docker-compose.ghcr.yml](../deploy/docker-compose.ghcr.yml).

**B — compilazione sulla VPS.** Con [deploy/docker-compose.yml](../deploy/docker-compose.yml).
Serve se hai modifiche locali non ancora spinte su GitHub. Va lanciata da SSH.

Il resto della procedura è identico: cambia solo il file compose.

## Non usare la finestra "app personalizzata"

L'importer di app personalizzate della dashboard **non** sostituisce le variabili `${...}`: non ha
un `.env` da cui leggerle. Incollandoci un compose di questo progetto risponde *Impossibile salvare
l'app personalizzata*.

Va installato da terminale. Se proprio vuoi vederlo nella dashboard, parti dal compose già risolto:

```bash
docker compose -f docker-compose.ghcr.yml --env-file .env config
```

Stampa lo stesso stack con tutti i valori sostituiti, incollabile nella finestra. Attenzione: quel
testo contiene il Client Secret e la password del database in chiaro.

Non serve comunque: dopo `docker compose up -d` i container compaiono lo stesso nella dashboard di
ZimaOS e si gestiscono da lì.

## Installazione

Dal terminale di ZimaOS o via SSH:

```bash
mkdir -p /DATA/AppData/spotify-stats && cd /DATA/AppData/spotify-stats
curl -fsSL -O https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy/install.sh
bash install.sh
```

Lo script chiede tre cose — dominio, Client ID, Client Secret — genera i segreti, scrive il `.env`
con i permessi giusti, scarica lo stack e lo avvia. A metà si ferma e ti mostra il Redirect URI da
incollare nella dashboard Spotify, perché senza quello il login fallisce comunque.

> Scarica ed esegui in due passaggi invece di `curl | bash`: così puoi leggere cosa fa lo script
> prima di dargli il tuo Client Secret. Vale per qualsiasi script d'installazione, non solo questo.

Le tabelle si creano da sole: il backend applica le migrazioni SQL all'avvio, prima ancora di aprire
la porta. Non c'è nessun comando manuale da dare.

### Installazione manuale

Se preferisci vedere ogni passaggio, o lo script fallisce:

```bash
BASE=https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy
curl -fsSL -O $BASE/docker-compose.ghcr.yml
curl -fsSL -O $BASE/Caddyfile
curl -fsSL $BASE/.env.example -o .env

# Segreti. Niente openssl: /dev/urandom c'è ovunque.
echo "JWT_SECRET=$(head -c 48 /dev/urandom | base64 | tr -d '\n')"
echo "TOKEN_ENC_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
echo "CRON_SECRET=$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
# La password del database va dentro una URL: solo lettere e cifre.
echo "POSTGRES_PASSWORD=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40)"

nano .env && chmod 600 .env
docker compose -f docker-compose.ghcr.yml up -d
```

Due vincoli sui segreti:

- **`TOKEN_ENC_KEY` deve essere esattamente 32 byte in base64** (44 caratteri che finiscono con `=`).
  È la chiave AES-256-GCM che cifra i refresh token di Spotify: con una lunghezza diversa il backend
  si ferma all'avvio.
- **`POSTGRES_PASSWORD` solo lettere e cifre.** Finisce dentro
  `postgresql://utente:PASSWORD@db:5432/nome`: un `/` o una `@` spezzano il parsing della URL, e
  `openssl rand -base64` li produce.

### Compilare sulla VPS invece di usare l'immagine

Serve solo se hai modifiche non ancora spinte su GitHub. Richiede `git`.

```bash
git clone https://github.com/Gigiomiccio425/spotify-stats-tracker.git spotify-stats
cd spotify-stats/deploy && cp .env.example .env && nano .env
docker compose up -d --build
```

## Verifica

```bash
curl https://<DOMAIN>/health
docker compose -f docker-compose.ghcr.yml logs -f backend
```

Nei log devono comparire `[migrate] schema aggiornato` e
`[scheduler] attivo, un giro ogni 15 minuti`.

> Con l'immagine da GHCR aggiungi `-f docker-compose.ghcr.yml` a **ogni** comando `docker compose`
> successivo, altrimenti Compose usa il file di default e prova a ricompilare.

## Collegare il primo account

Da browser: `https://<DOMAIN>/auth/spotify/start`

Dopo l'autorizzazione il browser prova ad aprire `spotifystats://auth?session=...` e dice che non
conosce quel collegamento: è normale finché l'app Android non è installata. Il valore di `session`
nella URL è il JWT, utile per provare le API:

```bash
curl -H "Authorization: Bearer <session>" https://<DOMAIN>/api/account/me
```

Ricorda: l'account va prima aggiunto in **User Management** nella dashboard Spotify, altrimenti il
login fallisce con `not_allowlisted` (vedi [SPOTIFY_SETUP.md](SPOTIFY_SETUP.md)).

## Configurare l'app Android

Niente da ricompilare: installa l'APK e al primo avvio inserisci `<DOMAIN>` nella schermata
**Il tuo server**. L'app verifica `/health` prima di salvare.

L'APK si scarica dagli artifact della run
[Android](https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml).
È lo stesso per tutti gli utenti: l'indirizzo del server è un'impostazione, non una costante di
compilazione.

## Manutenzione

```bash
cd spotify-stats/deploy

docker compose logs -f backend        # log del poller
docker compose exec backend npm run poll   # forza un giro subito
docker compose restart backend
docker compose down                   # ferma tutto (i dati restano nel volume)
```

Aggiornamento dopo una modifica al codice.

**Strada A** — aspetta che il workflow "Immagine backend" finisca su GitHub, poi:

```bash
docker compose -f docker-compose.ghcr.yml --env-file .env pull backend
docker compose -f docker-compose.ghcr.yml --env-file .env up -d backend
```

**Strada B**:

```bash
git pull
docker compose --env-file .env up -d --build backend
```

## Backup

L'archivio è tutto in un volume Docker. Va salvato altrove: se la VPS muore, quegli ascolti **non
sono recuperabili da Spotify**.

```bash
# Dump compresso, da mettere in un cron settimanale
docker compose exec -T db pg_dump -U stats stats | gzip > backup-$(date +%F).sql.gz
```

Ripristino:

```bash
gunzip -c backup-2026-07-30.sql.gz | docker compose exec -T db psql -U stats stats
```

In alternativa ogni utente può scaricare i propri dati dall'app (Impostazioni → export), ma è un
export per utente, non un backup del sistema.

## Se qualcosa non va

**Caddy non ottiene il certificato.** Controlla che il record A del dominio punti davvero all'IP
della VPS e che le porte 80/443 non siano bloccate dal firewall del provider (non solo da quello
della macchina). `docker compose logs caddy` dice il motivo esatto.

**`INVALID_CLIENT: Invalid redirect URI`.** Il Redirect URI nella dashboard Spotify deve combaciare
carattere per carattere con `https://<DOMAIN>/auth/spotify/callback`. Uno slash finale di troppo
basta a farlo fallire.

**Il backend riparte in ciclo.** Quasi sempre una variabile mancante in `deploy/.env`: il processo
valida la configurazione all'avvio e si ferma elencando cosa manca. `docker compose logs backend`.

**Nessun ascolto archiviato.** Verifica che nei log compaia la riga `[scheduler] attivo`. Se compare
invece `[scheduler] disattivato`, `ENABLE_INTERNAL_CRON` non è arrivato al container: sta nel
compose, quindi controlla di aver usato `--env-file .env`.

## Nota sulla sicurezza

Il database non espone nessuna porta sull'host: è raggiungibile solo dagli altri container. Su una
VPS con IP pubblico, pubblicare la 5432 significa comparire negli scanner automatici nel giro di
poche ore. Se ti serve ispezionarlo, passa da dentro:

```bash
docker compose exec db psql -U stats stats
```
