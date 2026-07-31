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
export async function runMigrations(): Promise<void> {
  // Connessione dedicata con `max: 1`: il migrator deve eseguire tutto sulla
  // stessa sessione, altrimenti i lock non valgono nulla.
  const client = postgres(env.DATABASE_URL, { max: 1 });

  try {
    // `process.cwd()` e non un percorso relativo al modulo: in sviluppo il
    // codice sta in src/, in produzione in dist/src/, e la cartella `drizzle`
    // resta accanto a package.json in entrambi i casi.
    const migrationsFolder = resolve(process.cwd(), 'drizzle');
    await migrate(drizzle(client), { migrationsFolder });
    console.log('[migrate] schema aggiornato');
  } finally {
    await client.end();
  }
}
