# Deploy sulla VPS con ZimaOS

ZimaOS gira su Docker, quindi tutto lo stack si installa come un unico progetto
`docker compose`. Rispetto a un host gratuito è nettamente meglio: il servizio non viene mai
sospeso, quindi il poller può girare **dentro il processo** e non serve alcun cron esterno.

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
[deploy/docker-compose.ghcr.yml](../deploy/docker-compose.ghcr.yml), che è anche l'unica variante
importabile dall'interfaccia di ZimaOS (gestisce `image:` ma non `build:`).

**B — compilazione sulla VPS.** Con [deploy/docker-compose.yml](../deploy/docker-compose.yml).
Serve se hai modifiche locali non ancora spinte su GitHub. Va lanciata da SSH.

Il resto della procedura è identico: cambia solo il file compose.

## Installazione

```bash
ssh <utente>@<ip-della-vps>

git clone https://github.com/Gigiomiccio425/spotify-stats-tracker.git spotify-stats
cd spotify-stats

# Genera i tre segreti applicativi
cd backend && npm install && npm run keys && cd ..

# Configura lo stack
cp deploy/.env.example deploy/.env
nano deploy/.env
```

Da compilare in `deploy/.env`: `DOMAIN`, `POSTGRES_PASSWORD` (`openssl rand -base64 32`),
`SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, e i tre segreti generati sopra.

Nella dashboard Spotify registra il Redirect URI **esatto**:

```
https://<DOMAIN>/auth/spotify/callback
```

Poi avvia. **Strada A**, immagine da GitHub:

```bash
cd deploy
docker compose -f docker-compose.ghcr.yml --env-file .env up -d
```

**Strada B**, compilando sulla VPS:

```bash
cd deploy
docker compose --env-file .env up -d --build
```

Crea le tabelle (una volta sola, vale per entrambe):

```bash
docker compose exec backend npm run db:push
```

> Con la strada A aggiungi `-f docker-compose.ghcr.yml` a **ogni** comando `docker compose`
> successivo, altrimenti Compose usa il file di default e prova a ricompilare.

Verifica:

```bash
curl https://<DOMAIN>/health
docker compose logs -f backend
```

Nei log deve comparire `[scheduler] attivo, un giro ogni 15 minuti`.

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
