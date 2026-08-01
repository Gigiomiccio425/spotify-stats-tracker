import { and, desc, eq } from 'drizzle-orm';
import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { db } from '../db/client.js';
import { importJobs } from '../db/schema.js';
import {
  enrichmentStatus,
  findOpenImportJob,
  queueImport,
  type StreamingHistoryEntry,
} from '../jobs/import.js';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';

export const importRoutes = new Hono<AuthedEnv>();

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

importRoutes.use('*', requireAuth);

/**
 * Carica un file dell'archivio *Extended Streaming History*.
 *
 * Spotify consegna l'archivio già spezzato in più file
 * (`Streaming_History_Audio_2019-2020_0.json`, ...): l'app li invia uno alla
 * volta, così una richiesta resta di dimensioni gestibili e un file corrotto
 * non fa fallire l'intero import.
 *
 * La risposta è `202`: il file viene accodato e lavorato in sottofondo. Non è
 * un dettaglio implementativo, è l'unico modo perché funzioni — l'import di un
 * archivio di anni dura minuti, e nessuna richiesta HTTP resta aperta così a
 * lungo (Cloudflare taglia a 100 secondi, il client anche prima).
 */
importRoutes.post('/streaming-history', async (c) => {
  const user = c.get('user');
  const filename = c.req.query('filename') ?? 'streaming-history.json';

  // Un job aperto per volta: le righe restano in memoria finché non vengono
  // lavorate, e dieci file insieme sono centinaia di MB nel processo.
  const open = await findOpenImportJob(user.id);
  if (open) {
    throw new HTTPException(409, {
      message: `Un import è già in corso (${open.filename}). Attendi che finisca.`,
    });
  }

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

  // Un controllo minimo sulla forma prima di accodare: meglio un 400 subito che
  // un job che fallisce a metà.
  const sample = entries[0] as Partial<StreamingHistoryEntry> | undefined;
  if (sample && sample.ts === undefined) {
    throw new HTTPException(400, {
      message:
        'Formato non riconosciuto: manca il campo "ts". Serve Extended Streaming History, non Account Data.',
    });
  }

  const queued = await queueImport(user.id, filename, entries as StreamingHistoryEntry[]);
  return c.json(queued, 202);
});

/** Storico degli import, per mostrare in Impostazioni cosa è già stato caricato. */
importRoutes.get('/jobs', async (c) => {
  const jobs = await db
    .select()
    .from(importJobs)
    .where(eq(importJobs.userId, c.get('user').id))
    .orderBy(desc(importJobs.createdAt))
    .limit(50);
  return c.json({ jobs, enrichment: enrichmentStatus() });
});

/**
 * Stato di un singolo job. È l'endpoint che l'app interroga mentre l'import
 * gira, per mostrare a che punto è.
 */
importRoutes.get('/jobs/:id', async (c) => {
  // La colonna è di tipo uuid: passarle una stringa qualsiasi non darebbe
  // "non trovato" ma un errore SQL, cioè un 500 al posto di un 404.
  if (!UUID.test(c.req.param('id'))) throw new HTTPException(404, { message: 'Import non trovato' });

  const [job] = await db
    .select()
    .from(importJobs)
    .where(and(eq(importJobs.id, c.req.param('id')), eq(importJobs.userId, c.get('user').id)))
    .limit(1);

  if (!job) throw new HTTPException(404, { message: 'Import non trovato' });
  return c.json({ ...job, enrichment: enrichmentStatus() });
});
