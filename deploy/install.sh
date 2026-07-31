#!/usr/bin/env bash
#
# Installazione dello stack su una macchina sempre accesa (VPS, ZimaOS,
# Raspberry). Scarica i file, genera i segreti, scrive il .env e avvia tutto.
#
#   curl -fsSL -O https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy/install.sh
#   bash install.sh
#
# Scarica ed esegui in due passaggi invece di `curl | bash`: cosi' puoi
# leggere cosa fa prima di dargli i tuoi segreti.

set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/Gigiomiccio425/spotify-stats-tracker/main/deploy"
COMPOSE_FILE="docker-compose.ghcr.yml"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
warn() { printf '\033[33m%s\033[0m\n' "$1"; }
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

DOWNLOAD=""
command -v curl >/dev/null 2>&1 && DOWNLOAD="curl -fsSL -o"
[ -z "$DOWNLOAD" ] && command -v wget >/dev/null 2>&1 && DOWNLOAD="wget -qO"
[ -z "$DOWNLOAD" ] && fail "Serve curl oppure wget."

# --- Generatore di segreti ----------------------------------------------

# /dev/urandom invece di openssl: su un sistema minimale openssl puo' non
# esserci, /dev/urandom c'e' sempre.
random_base64() {
  head -c "$1" /dev/urandom | base64 | tr -d '\n'
}

# La password del database finisce dentro una URL
# (postgresql://utente:PASSWORD@db:5432/nome). Base64 produce anche `/` e `+`,
# che spezzano il parsing della URL: qui restano solo lettere e cifre.
random_alnum() {
  LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c "$1"
}

# --- Raccolta dei dati ---------------------------------------------------

bold "Configurazione"
echo

# Se un .env c'e' gia', l'impostazione predefinita e' tenerlo e proseguire:
# quasi sempre si rilancia lo script dopo un errore, e rigenerare i segreti a
# ogni tentativo scollegherebbe gli account gia' associati.
REUSE_ENV=""
if [ -f .env ]; then
  warn "Esiste gia' un file .env in questa cartella."
  echo
  echo "  1) Tenerlo e proseguire con l'avvio dello stack  (predefinito)"
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
    *)
      REUSE_ENV="si"
      echo "Uso il .env esistente."
      ;;
  esac
  echo
fi

if [ -n "$REUSE_ENV" ]; then
  # Serve solo per il messaggio finale.
  DOMAIN="$(grep -E '^DOMAIN=' .env | head -n1 | cut -d= -f2-)"
else

echo "Il dominio deve gia' puntare all'IP di questa macchina (record A)."
echo "Spotify accetta redirect URI solo in HTTPS, quindi un IP nudo non basta."
echo "Se non hai un dominio, un sottodominio gratuito (per esempio DuckDNS) va bene."
echo
read -r -p "Dominio (es. stats.miosito.it): " DOMAIN
[ -n "$DOMAIN" ] || fail "Il dominio e' obbligatorio."

# Toglie eventuale schema o slash incollati per abitudine.
DOMAIN="${DOMAIN#http://}"
DOMAIN="${DOMAIN#https://}"
DOMAIN="${DOMAIN%%/*}"

echo
echo "Credenziali da https://developer.spotify.com/dashboard -> la tua app -> Settings"
read -r -p "SPOTIFY_CLIENT_ID: " SPOTIFY_CLIENT_ID
[ -n "$SPOTIFY_CLIENT_ID" ] || fail "Client ID obbligatorio."
read -r -s -p "SPOTIFY_CLIENT_SECRET (non viene mostrato): " SPOTIFY_CLIENT_SECRET
echo
[ -n "$SPOTIFY_CLIENT_SECRET" ] || fail "Client Secret obbligatorio."

# --- Redirect URI: va registrato PRIMA, altrimenti il login fallisce -----

REDIRECT_URI="https://${DOMAIN}/auth/spotify/callback"

echo
bold "Registra questo Redirect URI nella dashboard Spotify"
echo
echo "    ${REDIRECT_URI}"
echo
echo "Deve combaciare carattere per carattere: uno slash finale in piu' e il"
echo "login fallisce con INVALID_CLIENT: Invalid redirect URI."
echo
echo "Nella stessa pagina, sezione User Management, aggiungi l'email dell'account"
echo "Spotify che userai. Senza, il login restituisce 403: un'app in Development"
echo "Mode accetta solo i 25 account dichiarati a mano."
echo
read -r -p "Premi Invio quando l'hai fatto. "

# --- Scrittura del .env --------------------------------------------------

echo
echo "Genero i segreti e scrivo il .env…"

umask 077
cat > .env <<EOF
# Generato da install.sh il $(date -u '+%Y-%m-%d %H:%M UTC')
# Contiene segreti: non copiarlo altrove e non metterlo in git.

DOMAIN=${DOMAIN}

POSTGRES_USER=stats
POSTGRES_DB=stats
POSTGRES_PASSWORD=$(random_alnum 40)

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

fi   # fine del ramo "genera un .env nuovo"

echo "Scarico i file dello stack…"
$DOWNLOAD "$COMPOSE_FILE" "${REPO_RAW}/${COMPOSE_FILE}"
$DOWNLOAD Caddyfile "${REPO_RAW}/Caddyfile"

# --- Controllo del .env --------------------------------------------------

# Un valore vuoto qui produce errori che non nominano la variabile colpevole:
# Postgres con POSTGRES_PASSWORD vuota esce con "Database is uninitialized",
# e Compose riporta solo "container is unhealthy". Meglio dirlo adesso.
missing=""
for key in DOMAIN POSTGRES_PASSWORD SPOTIFY_CLIENT_ID SPOTIFY_CLIENT_SECRET \
           JWT_SECRET TOKEN_ENC_KEY CRON_SECRET; do
  value="$(grep -E "^${key}=" .env | head -n1 | cut -d= -f2-)"
  [ -n "$value" ] || missing="${missing} ${key}"
done

if [ -n "$missing" ]; then
  fail "Valori mancanti nel .env:${missing}
Compilali con 'nano .env' e rilancia lo script."
fi

# La chiave AES-256-GCM deve essere esattamente 32 byte: in base64 sono 44
# caratteri. Con una lunghezza diversa il backend si ferma all'avvio.
enc_key="$(grep -E '^TOKEN_ENC_KEY=' .env | head -n1 | cut -d= -f2-)"
if [ "${#enc_key}" -ne 44 ]; then
  fail "TOKEN_ENC_KEY non valida: ${#enc_key} caratteri invece di 44.
Deve essere 32 byte in base64:  head -c 32 /dev/urandom | base64"
fi

# Finisce dentro postgresql://utente:PASSWORD@db:5432/nome: un `/`, una `@` o
# un `:` spezzano il parsing della URL e il backend non trova il database.
pg_pass="$(grep -E '^POSTGRES_PASSWORD=' .env | head -n1 | cut -d= -f2-)"
case "$pg_pass" in
  *[/:@#]*)
    fail "POSTGRES_PASSWORD contiene caratteri che spezzano la URL di connessione.
Usa solo lettere e cifre:  tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 40"
    ;;
esac

# --- Avvio ---------------------------------------------------------------

echo
echo "Avvio dello stack…"
$DC -f "$COMPOSE_FILE" pull
$DC -f "$COMPOSE_FILE" up -d

echo
echo "Attendo che il backend risponda…"

ready=""
for _ in $(seq 1 45); do
  if $DC -f "$COMPOSE_FILE" exec -T backend wget -q -O - http://127.0.0.1:8787/health >/dev/null 2>&1; then
    ready="si"
    break
  fi
  sleep 2
done

echo
if [ -n "$ready" ]; then
  bold "Fatto."
  echo
  echo "Backend attivo. Le tabelle sono state create in automatico."
  echo
  echo "1. Installa l'APK sul telefono:"
  echo "   https://github.com/Gigiomiccio425/spotify-stats-tracker/actions/workflows/android.yml"
  echo "2. Al primo avvio inserisci come indirizzo del server:  ${DOMAIN}"
  echo "3. Collega l'account Spotify."
  echo
  echo "Verifica dall'esterno:  curl https://${DOMAIN}/health"
  echo "Se non risponde, il certificato non e' ancora pronto: guarda"
  echo "  $DC -f $COMPOSE_FILE logs caddy"
else
  warn "Il backend non ha risposto entro 90 secondi."
  echo
  echo "Log dei due container che possono averlo impedito:"
  echo
  echo "--- database ---"
  $DC -f "$COMPOSE_FILE" logs --tail 20 db 2>&1 || true
  echo
  echo "--- backend ---"
  $DC -f "$COMPOSE_FILE" logs --tail 20 backend 2>&1 || true
  echo
  echo "Per il resto:  $DC -f $COMPOSE_FILE logs"
  exit 1
fi
