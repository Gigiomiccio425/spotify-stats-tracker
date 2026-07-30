import { drizzle } from 'drizzle-orm/postgres-js';
import postgres from 'postgres';
import { env } from '../env.js';
import * as schema from './schema.js';

// `max: 5` tiene basso il numero di connessioni: i free tier di Neon/Supabase
// hanno limiti stretti e il poller apre una connessione per esecuzione.
const queryClient = postgres(env.DATABASE_URL, {
  max: 5,
  idle_timeout: 20,
  connect_timeout: 15,
  prepare: false,
});

export const db = drizzle(queryClient, { schema, casing: 'snake_case' });
export const sqlClient = queryClient;
export { schema };
