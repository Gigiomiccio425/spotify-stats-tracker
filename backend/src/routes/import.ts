import { desc, eq } from 'drizzle-orm';
import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { db } from '../db/client.js';
import { importJobs } from '../db/schema.js';
import { importStreamingHistory, type StreamingHistoryEntry } from '../jobs/import.js';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';

export const importRoutes = new Hono<AuthedEnv>();

importRoutes.use('*', requireAuth);

/**
 * Carica un file dell'archivio *Extended Streaming History*.
 *
 * Spotify consegna l'archivio già spezzato in più file
 * (`Streaming_History_Audio_2019-2020_0.json`, ...): l'app li invia uno alla
 * volta, così una richiesta resta di dimensioni gestibili e un file corrotto
 * non fa fallire l'intero import.
 */
importRoutes.post('/streaming-history', async (c) => {
  const user = c.get('user');
  const filename = c.req.query('filename') ?? 'streaming-history.json';

  let body: unknown;
  try {
    body = await c.req.json();
  } catch {
    throw new HTTPException(400, { message: 'Corpo della richiesta non è JSON valido' });
  }

  const entries = Array.isArray(body) ? body : (body as { entries?: unknown }).entries;
  if (!Array.isArray(entries)) {
    throw new HTTPException(400, {
      message: 'Attesa una lista di ascolti. Carica un file Streaming_History_Audio_*.json.',
    });
  }

  // Un controllo minimo sulla forma prima di lanciare l'import: meglio un 400
  // subito che un job che fallisce a metà.
  const sample = entries[0] as Partial<StreamingHistoryEntry> | undefined;
  if (sample && sample.ts === undefined) {
    throw new HTTPException(400, {
      message: 'Formato non riconosciuto: manca il campo "ts". Serve Extended Streaming History, non Account Data.',
    });
  }

  return c.json(await importStreamingHistory(user.id, filename, entries as StreamingHistoryEntry[]));
});

/** Storico degli import, per mostrare in Impostazioni cosa è già stato caricato. */
importRoutes.get('/jobs', async (c) => {
  const jobs = await db
    .select()
    .from(importJobs)
    .where(eq(importJobs.userId, c.get('user').id))
    .orderBy(desc(importJobs.createdAt))
    .limit(50);
  return c.json({ jobs });
});
