import { resolve } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { env } from '../env.js';

/**
 * Applica le migrazioni all'avvio.
 *
 * Prima questo passaggio era un comando manuale (`npm run db:push`) da dare
 * dentro il container. Due problemi: richiedeva drizzle-kit e npm anche in
 * produzione, e su un container che gira come utente non privilegiato non è
 * detto che npm sia eseguibile. Le migrazioni sono file SQL già generati in
 * `drizzle/`, quindi qui basta drizzle-orm, che c'è comunque.
 */
const RETRY_ATTEMPTS = 30;
const RETRY_DELAY_MS = 2000;

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export async function runMigrations(): Promise<void> {
  // `process.cwd()` e non un percorso relativo al modulo: in sviluppo il
  // codice sta in src/, in produzione in dist/src/, e la cartella `drizzle`
  // resta accanto a package.json in entrambi i casi.
  const migrationsFolder = resolve(process.cwd(), 'drizzle');

  // Postgres impiega qualche secondo ad accettare connessioni al primo avvio.
  // Riprovare qui evita di dipendere dall'healthcheck del container, che su
  // alcuni sistemi non riesce nemmeno a eseguire `pg_isready`, e rende
  // superfluo qualsiasi `wait-for-it` esterno.
  for (let attempt = 1; ; attempt++) {
    // Connessione dedicata con `max: 1`: il migrator deve eseguire tutto sulla
    // stessa sessione, altrimenti i lock non valgono nulla.
    const client = postgres(env.DATABASE_URL, { max: 1, onnotice: () => {} });

    try {
      await migrate(drizzle(client), { migrationsFolder });
      console.log('[migrate] schema aggiornato');
      return;
    } catch (err) {
      if (attempt >= RETRY_ATTEMPTS) {
        console.error(`[migrate] fallito dopo ${attempt} tentativi`);
        throw err;
      }
      const reason = err instanceof Error ? err.message : String(err);
      console.log(`[migrate] database non pronto (${reason}), riprovo… ${attempt}/${RETRY_ATTEMPTS}`);
      await sleep(RETRY_DELAY_MS);
    } finally {
      await client.end({ timeout: 5 }).catch(() => {});
    }
  }
}
