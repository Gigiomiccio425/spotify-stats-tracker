# --- Compilazione --------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# --- Immagine finale -----------------------------------------------------
FROM node:22-alpine AS runtime

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=8787

# Identifica l'immagine, e la risposta di /health la riporta. Senza, "ho
# aggiornato il backend?" resta una domanda senza risposta certa.
ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION

# Solo dipendenze di produzione. L'immagine precedente teneva anche quelle di
# sviluppo per poter lanciare `npm run db:push` dentro il container: ora le
# migrazioni si applicano da sole all'avvio, quindi npm a runtime non serve
# più. Meno superficie, immagine più piccola, e nessuna dipendenza dal fatto
# che npm sia eseguibile dall'utente non privilegiato.
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=build /app/dist ./dist
# Le migrazioni sono file SQL letti a runtime: devono stare nell'immagine.
COPY drizzle ./drizzle

# Nessun `USER node` e nessuna HEALTHCHECK.
#
# Su ZimaOS eseguire un binario dentro il container come utente non
# privilegiato fallisce con "exec: node: Permission denied", e l'immagine non
# parte affatto. Stessa restrizione che rendeva inutilizzabili `docker exec` e
# le healthcheck.
#
# Girare come root DENTRO il container non equivale a essere root sull'host:
# il processo resta confinato, non pubblica porte proprie e sta dietro al
# reverse proxy. Su un host Docker che non ha quella restrizione si può
# rimettere l'utente non privilegiato dal compose, senza ricompilare:
#
#   backend:
#     user: "node"
#
EXPOSE 8787

CMD ["node", "dist/src/index.js"]
