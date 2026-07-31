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

USER node

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:8787/health || exit 1

CMD ["node", "dist/src/index.js"]
