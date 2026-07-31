#!/usr/bin/env bash
#
# Installazione dello stack su una macchina sempre accesa (VPS, ZimaOS,
# Raspberry). Scarica i file, genera i segreti, sceglie la configurazione che
# funziona su questo sistema e avvia tutto.
#
#   curl -fsSL -O https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy/install.sh
#   sudo bash install.sh
#
# Scarica ed esegui in due passaggi invece di `curl | bash`: cosi' puoi
# leggere cosa fa prima di dargli i tuoi segreti.

set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
info() { printf '\033[36m%s\033[0m\n' "$1"; }
fail() { printf '\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

# --- Requisiti -----------------------------------------------------------

command -v docker >/dev/null 2>&1 || fail "Docker non trovato. Serve Docker per procedere."

# Verificato subito, prima di generare segreti e scrivere file: scoprire di
# non poter parlare col daemon a meta' installazione lascia in giro un .env
# gia' compilato e un lavoro da rifare.
if ! docker info >/dev/null 2>&1; then
  printf '\033[31m%s\033[0m\n' "Non riesco a contattare il daemon Docker." >&2
  echo >&2
  echo "L'utente '$(id -un)' non ha accesso a /var/run/docker.sock. Due modi:" >&2
  echo >&2
  echo "  sudo bash $0" >&2
  echo >&2
  echo "oppure, una volta sola, per non dover usare sudo ogni volta:" >&2
  echo >&2
  echo "  sudo usermod -aG docker $(id -un)" >&2
  echo "  # poi esci e rientra nella sessione, o esegui: newgrp docker" >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  fail "Docker Compose non trovato."
fi

if command -v curl >/dev/null 2>&1; then
  DOWNLOAD="curl -fsSL -o"
elif command -v wget >/dev/null 2>&1; then
  DOWNLOAD="wget -qO"
else
  fail "Serve curl oppure wget."
fi

# --- Utilita' sul .env ---------------------------------------------------

env_get() {
  [ -f .env ] || return 0
  grep -E "^$1=" .env | head -n1 | cut -d= -f2-
}

# Aggiorna la riga se c'e', altrimenti la aggiunge in fondo. Serve a portare
# avanti i .env creati da versioni precedenti dello script senza costringere a
# rigenerare i segreti.
env_set() {
  if grep -qE "^$1=" .env 2>/dev/null; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}

random_base64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

# La password del database finisce dentro una URL
# (postgresql://utente:PASSWORD@db:5432/nome). Base64 produce anche `/` e `+`,
# che spezzano il parsing: qui restano solo lettere e cifre.
random_alnum() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$1"; }

port_busy() {
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${1}\$"
  elif command -v netstat >/dev/null 2>&1; then
    netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${1}\$"
  else
    return 1
  fi
}

# --- Configurazione ------------------------------------------------------

bold "Configurazione"
echo

REUSE_ENV=""
if [ -f .env ]; then
  warn "Esiste gia' un file .env in questa cartella."
  echo
  echo "  1) Tenerlo e proseguire  (predefinito)"
  echo "  2) Rigenerarlo da zero"
  echo
  read -r -p "Scelta [1/2]: " env_choice
  case "$env_choice" in
    2)
      warn "Con una TOKEN_ENC_KEY nuova gli account gia' collegati dovranno rifare il login."
      read -r -p "Confermi? [s/N] " confirm
      case "$confirm" in
        s|S|y|Y) ;;
        *) echo "Interrotto."; exit 0 ;;
      esac
      ;;
    *) REUSE_ENV="si" ;;
  esac
  echo
fi

if [ -n "$REUSE_ENV" ]; then
  echo "Uso il .env esistente, completando le voci mancanti."
  DOMAIN="$(env_get DOMAIN)"
  [ -n "$DOMAIN" ] || fail "Nel .env manca DOMAIN. Aggiungilo o rilancia scegliendo 2."

  # Voci introdotte dopo: senza queste il .env di una versione precedente
  # fermerebbe lo stack con un errore poco chiaro.
  [ -n "$(env_get POSTGRES_IMAGE)" ] || env_set POSTGRES_IMAGE "postgres:16-alpine"

  if [ -z "$(env_get DATABASE_URL)" ]; then
    pg_user="$(env_get POSTGRES_USER)"; pg_user="${pg_user:-stats}"
    pg_db="$(env_get POSTGRES_DB)"; pg_db="${pg_db:-stats}"
    pg_pass="$(env_get POSTGRES_PASSWORD)"
    [ -n "$pg_pass" ] || fail "Nel .env mancano sia DATABASE_URL sia POSTGRES_PASSWORD."
    env_set DATABASE_URL "postgresql://${pg_user}:${pg_pass}@db:5432/${pg_db}"
    info "Aggiunta DATABASE_URL."
  fi
else
  echo "Il dominio deve gia' puntare all'IP di questa macchina (record A),"
  echo "oppure essere gestito da Cloudflare se userai il tunnel."
  echo
  read -r -p "Dominio (es. stats.miosito.it): " DOMAIN
  [ -n "$DOMAIN" ] || fail "Il dominio e' obbligatorio."

  # Toglie schema e percorsi incollati per abitudine.
  DOMAIN="${DOMAIN#http://}"; DOMAIN="${DOMAIN#https://}"; DOMAIN="${DOMAIN%%/*}"

  echo
  echo "Credenziali da https://developer.spotify.com/dashboard -> la tua app -> Settings"
  read -r -p "SPOTIFY_CLIENT_ID: " SPOTIFY_CLIENT_ID
  [ -n "$SPOTIFY_CLIENT_ID" ] || fail "Client ID obbligatorio."
  read -r -s -p "SPOTIFY_CLIENT_SECRET (non viene mostrato): " SPOTIFY_CLIENT_SECRET
  echo
  [ -n "$SPOTIFY_CLIENT_SECRET" ] || fail "Client Secret obbligatorio."

  echo
  bold "Registra questo Redirect URI nella dashboard Spotify"
  echo
  echo "    https://${DOMAIN}/auth/spotify/callback"
  echo
  echo "Deve combaciare carattere per carattere: uno slash finale in piu' e il"
  echo "login fallisce con INVALID_CLIENT: Invalid redirect URI."
  echo
  echo "Nella stessa pagina, sezione User Management, aggiungi l'email dell'account"
  echo "Spotify che userai. Senza, il login restituisce 403: un'app in Development"
  echo "Mode accetta solo i 25 account dichiarati a mano."
  echo
  read -r -p "Premi Invio quando l'hai fatto. "

  PG_PASSWORD="$(random_alnum 40)"

  umask 077
  cat > .env <<EOF
# Generato da install.sh il $(date -u '+%Y-%m-%d %H:%M UTC')
# Contiene segreti: non copiarlo altrove e non metterlo in git.

DOMAIN=${DOMAIN}

# Per usare un Postgres esterno cambia questa riga e cancella il servizio
# \`db\` dal file compose.
DATABASE_URL=postgresql://stats:${PG_PASSWORD}@db:5432/stats

POSTGRES_IMAGE=postgres:16-alpine
POSTGRES_USER=stats
POSTGRES_DB=stats
POSTGRES_PASSWORD=${PG_PASSWORD}

SPOTIFY_CLIENT_ID=${SPOTIFY_CLIENT_ID}
SPOTIFY_CLIENT_SECRET=${SPOTIFY_CLIENT_SECRET}

JWT_SECRET=$(random_base64 48)
# Chiave AES-256-GCM che cifra i refresh token Spotify: 32 byte esatti.
# Se la cambi, tutti gli account collegati devono rifare il login.
TOKEN_ENC_KEY=$(random_base64 32)
CRON_SECRET=$(random_base64 32)

BACKEND_IMAGE=ghcr.io/gigiomiccio425/spotify-stats-tracker/backend:latest
APP_DEEP_LINK=spotifystats://auth
POLL_INTERVAL_MINUTES=15
EOF
  chmod 600 .env
fi

# --- Come esporre il servizio: Caddy oppure tunnel Cloudflare ------------

echo
if [ -n "$(env_get CLOUDFLARE_TUNNEL_TOKEN)" ]; then
  USE_TUNNEL="si"
  info "Trovato CLOUDFLARE_TUNNEL_TOKEN: uso il tunnel."
elif port_busy 80 || port_busy 443; then
  warn "Le porte 80 e/o 443 sono gia' occupate su questa macchina."
  echo "Su ZimaOS le usa la dashboard, e Caddy non riuscirebbe a legarsi."
  echo
  echo "Il tunnel Cloudflare non richiede nessuna porta: la connessione parte"
  echo "in uscita da qui. Serve che il dominio sia gestito da Cloudflare."
  echo
  echo "  Zero Trust > Networks > Tunnels > Create a tunnel > Cloudflared"
  echo "  Public Hostname:  ${DOMAIN}  ->  HTTP  ->  backend:8787"
  echo
  read -r -p "Token del tunnel (vuoto per usare comunque Caddy): " tunnel_token
  if [ -n "$tunnel_token" ]; then
    env_set CLOUDFLARE_TUNNEL_TOKEN "$tunnel_token"
    USE_TUNNEL="si"
  else
    USE_TUNNEL=""
    warn "Proseguo con Caddy, ma l'avvio fallira' finche' le porte restano occupate."
  fi
else
  USE_TUNNEL=""
fi

if [ -n "${USE_TUNNEL:-}" ]; then
  COMPOSE_FILE="docker-compose.tunnel.yml"
else
  COMPOSE_FILE="docker-compose.ghcr.yml"
fi

# --- File dello stack ----------------------------------------------------

echo
echo "Scarico i file dello stack…"
$DOWNLOAD "$COMPOSE_FILE" "${REPO_RAW}/${COMPOSE_FILE}"
[ -n "${USE_TUNNEL:-}" ] || $DOWNLOAD Caddyfile "${REPO_RAW}/Caddyfile"

# --- Controllo del .env --------------------------------------------------

# Un valore vuoto qui produce errori che non nominano la variabile colpevole:
# Postgres senza password esce con "Database is uninitialized", e Compose
# riporta solo "container is unhealthy". Meglio dirlo adesso.
missing=""
for key in DOMAIN DATABASE_URL SPOTIFY_CLIENT_ID SPOTIFY_CLIENT_SECRET \
           JWT_SECRET TOKEN_ENC_KEY CRON_SECRET; do
  [ -n "$(env_get "$key")" ] || missing="${missing} ${key}"
done
[ -z "$missing" ] || fail "Valori mancanti nel .env:${missing}
Compilali con 'nano .env' e rilancia lo script."

enc_key="$(env_get TOKEN_ENC_KEY)"
if [ "${#enc_key}" -ne 44 ]; then
  fail "TOKEN_ENC_KEY non valida: ${#enc_key} caratteri invece di 44.
Deve essere 32 byte in base64:  head -c 32 /dev/urandom | base64"
fi

case "$(env_get POSTGRES_PASSWORD)" in
  '') ;;
  *[/:@#]*)
    fail "POSTGRES_PASSWORD contiene caratteri che spezzano la URL di connessione.
Usa solo lettere e cifre:  tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40"
    ;;
esac

# --- Avvio ---------------------------------------------------------------

USES_BUNDLED_DB=""
case "$(env_get DATABASE_URL)" in
  *@db:*) USES_BUNDLED_DB="si" ;;
esac

db_is_up() {
  cid="$($DC -f "$COMPOSE_FILE" ps -q db 2>/dev/null || true)"
  [ -n "$cid" ] || return 1
  [ "$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null)" = "running" ]
}

start_stack() {
  $DC -f "$COMPOSE_FILE" pull
  $DC -f "$COMPOSE_FILE" up -d
}

echo
echo "Avvio dello stack…"
start_stack

# Alcune installazioni di Docker rifiutano il modo in cui l'immagine Alpine di
# Postgres passa dall'utente root all'utente postgres, e il container muore
# subito con "exec failed: permission denied". La variante Debian usa un
# meccanismo diverso: se la prima non regge, si cambia e si riparte.
if [ -n "$USES_BUNDLED_DB" ]; then
  echo "Verifico che il database sia partito…"
  sleep 12

  if ! db_is_up && [ "$(env_get POSTGRES_IMAGE)" != "postgres:16" ]; then
    warn "Il container del database non regge con l'immagine Alpine."
    echo "Riprovo con la variante Debian (postgres:16)…"
    echo
    env_set POSTGRES_IMAGE "postgres:16"
    # Il volume creato dal tentativo precedente e' inutilizzabile.
    $DC -f "$COMPOSE_FILE" down -v
    start_stack
    sleep 12
  fi

  if ! db_is_up; then
    warn "Il database non parte con nessuna delle due immagini."
    echo
    $DC -f "$COMPOSE_FILE" logs --tail 20 db 2>&1 || true
    echo
    echo "Strada alternativa: usa un Postgres esterno. Nel .env metti"
    echo "la sua URL in DATABASE_URL e cancella il servizio 'db' da"
    echo "${COMPOSE_FILE}. Il backend crea le tabelle da solo."
    exit 1
  fi

  info "Database attivo con $(env_get POSTGRES_IMAGE)."
fi

# --- Verifica ------------------------------------------------------------

echo
echo "Attendo che il backend risponda su https://${DOMAIN}/health …"

# Il controllo passa dall'esterno e non da `docker compose exec`: su alcuni
# sistemi eseguire comandi dentro un container fallisce, e il controllo direbbe
# "non pronto" per un backend perfettamente funzionante.
ready=""
for _ in $(seq 1 45); do
  if curl -fsS --max-time 5 "https://${DOMAIN}/health" >/dev/null 2>&1; then
    ready="si"
    break
  fi
  sleep 2
done

echo
if [ -n "$ready" ]; then
  bold "Fatto."
  echo
  echo "1. Installa l'APK sul telefono:"
  echo "   https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml"
  echo "2. Al primo avvio inserisci come indirizzo del server:  ${DOMAIN}"
  echo "3. Collega l'account Spotify."
  echo
  echo "Log:  $DC -f $COMPOSE_FILE logs -f backend"
else
  warn "https://${DOMAIN}/health non ha risposto entro 90 secondi."
  echo
  echo "Puo' essere solo il certificato non ancora emesso, o il DNS non ancora"
  echo "propagato: in quel caso i log qui sotto sono puliti e basta riprovare."
  echo
  echo "--- backend ---"
  $DC -f "$COMPOSE_FILE" logs --tail 20 backend 2>&1 || true
  echo
  if [ -n "${USE_TUNNEL:-}" ]; then
    echo "--- cloudflared ---"
    $DC -f "$COMPOSE_FILE" logs --tail 15 cloudflared 2>&1 || true
  else
    echo "--- caddy ---"
    $DC -f "$COMPOSE_FILE" logs --tail 15 caddy 2>&1 || true
  fi
  exit 1
fi
