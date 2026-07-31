import { desc, eq, sql } from 'drizzle-orm';
import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { z } from 'zod';
import { db } from '../db/client.js';
import { pollRuns, users } from '../db/schema.js';
import { lastPollStatus, pollUser } from '../jobs/poll.js';
import { getHistory } from '../lib/stats.js';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';

export const accountRoutes = new Hono<AuthedEnv>();

accountRoutes.use('*', requireAuth);

accountRoutes.get('/me', async (c) => {
  const user = c.get('user');
  const { last, possibleGaps } = await lastPollStatus(user.id);

  return c.json({
    id: user.id,
    spotifyUserId: user.spotifyUserId,
    displayName: user.displayName,
    imageUrl: user.imageUrl,
    country: user.country,
    trackingSince: user.trackingSince.toISOString(),
    periodMode: user.periodMode,
    timezone: user.timezone,
    sync: {
      lastRunAt: last?.startedAt?.toISOString() ?? null,
      status: last?.status ?? null,
      error: last?.error ?? null,
      itemsInserted: last?.itemsInserted ?? 0,
      // Quante volte il poller ha trovato la finestra piena: ognuna è un
      // possibile buco nell'archivio. Meglio dirlo che fingere completezza.
      possibleGaps,
    },
  });
});

const settingsSchema = z.object({
  periodMode: z.enum(['calendar', 'anniversary']).optional(),
  timezone: z.string().min(1).optional(),
});

accountRoutes.patch('/me', async (c) => {
  const user = c.get('user');
  const parsed = settingsSchema.safeParse(await c.req.json().catch(() => ({})));
  if (!parsed.success) throw new HTTPException(400, { message: 'Impostazioni non valide' });

  if (parsed.data.timezone) {
    // Un fuso inesistente farebbe esplodere ogni query aggregata: si valida qui.
    try {
      new Intl.DateTimeFormat('en-US', { timeZone: parsed.data.timezone });
    } catch {
      throw new HTTPException(400, { message: `Fuso orario sconosciuto: ${parsed.data.timezone}` });
    }
  }

  const [updated] = await db
    .update(users)
    .set({ ...parsed.data, updatedAt: new Date() })
    .where(eq(users.id, user.id))
    .returning();

  return c.json({ periodMode: updated!.periodMode, timezone: updated!.timezone });
});

/**
 * Interroga Spotify subito, senza aspettare il giro dei 15 minuti.
 * Chiamato quando l'utente tira giù per aggiornare.
 *
 * Il limite di frequenza non è per proteggere il nostro server ma quello di
 * Spotify: il rate limit è per applicazione, non per utente, quindi qualcuno
 * che insiste col gesto di refresh danneggerebbe tutti gli altri.
 */
accountRoutes.post('/sync', async (c) => {
  const user = c.get('user');

  const [last] = await db
    .select({ startedAt: pollRuns.startedAt })
    .from(pollRuns)
    .where(eq(pollRuns.userId, user.id))
    .orderBy(desc(pollRuns.startedAt))
    .limit(1);

  if (last && Date.now() - last.startedAt.getTime() < MIN_SYNC_INTERVAL_MS) {
    return c.json({ skipped: true, inserted: 0, lastRunAt: last.startedAt.toISOString() });
  }

  const result = await pollUser(user.id);
  return c.json({ skipped: false, ...result });
});

const MIN_SYNC_INTERVAL_MS = 20_000;

/**
 * Export completo dell'archivio in JSON.
 * I dati sono dell'utente: deve poterseli portare via senza chiedere nulla.
 */
accountRoutes.get('/export', async (c) => {
  const user = c.get('user');
  const all: Awaited<ReturnType<typeof getHistory>> = [];

  // Paginato invece che in una query sola: un archivio di anni non deve
  // costringere il processo a tenere tutto in memoria in un colpo.
  let cursor: { before: Date; beforeId: number } | undefined;
  for (;;) {
    const page = await getHistory(user.id, { ...cursor, limit: 1000 });
    all.push(...page);
    const last = page[page.length - 1];
    if (page.length < 1000 || !last) break;
    cursor = { before: new Date(last.playedAt), beforeId: last.id };
  }

  c.header('Content-Disposition', `attachment; filename="spotify-stats-${user.spotifyUserId}.json"`);
  return c.json({
    exportedAt: new Date().toISOString(),
    trackingSince: user.trackingSince.toISOString(),
    spotifyUserId: user.spotifyUserId,
    plays: all,
  });
});

/**
 * Cancellazione dell'account e di tutti i dati. Le foreign key sono in
 * ON DELETE CASCADE, quindi una sola DELETE porta via ascolti, credenziali e
 * cronologia dei job. Richiesto dai Developer Terms di Spotify.
 */
accountRoutes.delete('/', async (c) => {
  const user = c.get('user');
  const confirm = c.req.query('confirm');
  if (confirm !== user.spotifyUserId) {
    throw new HTTPException(400, {
      message: 'Per confermare, passa ?confirm=<il tuo spotify user id>',
    });
  }

  const result = (await db.execute(
    sql`select count(*)::int as count from plays where user_id = ${user.id}`,
  )) as unknown as { count: number }[];

  await db.delete(users).where(eq(users.id, user.id));
  return c.json({ deleted: true, playsDeleted: Number(result[0]?.count ?? 0) });
});
