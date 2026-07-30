FROM node:22-alpine

WORKDIR /app

# Le dipendenze di sviluppo restano nell'immagine finale di proposito: servono
# a `npm run db:push` (drizzle-kit legge lo schema TypeScript) e a
# `npm run poll` per un giro manuale dentro il container. Su una VPS i ~150 MB
# in più valgono la possibilità di amministrare senza un secondo container.
COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

ENV NODE_ENV=production
ENV PORT=8787

# Utente non privilegiato: se il processo viene compromesso non è root nel
# container. L'immagine node fornisce già l'utente `node`.
RUN chown -R node:node /app
USER node

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:8787/health || exit 1

CMD ["node", "dist/src/index.js"]
