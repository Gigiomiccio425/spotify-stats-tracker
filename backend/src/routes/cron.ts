import { Hono } from 'hono';
import { pollAllUsers, pollUser } from '../jobs/poll.js';
import { requireCronSecret } from '../middleware/auth.js';

export const cronRoutes = new Hono();

cronRoutes.use('*', requireCronSecret);

/**
 * Invocato ogni 15 minuti da un cron esterno (cron-job.org, GitHub Actions).
 *
 * La chiamata ha un doppio scopo: fa scattare l'archiviazione e tiene sveglio
 * il servizio sugli host free tier che lo sospendono dopo qualche minuto di
 * inattività. Se questo endpoint smette di essere chiamato per più di due ore,
 * gli ascolti di quella finestra sono persi in modo definitivo.
 */
cronRoutes.post('/poll', async (c) => {
  const started = Date.now();
  const results = await pollAllUsers();

  return c.json({
    durationMs: Date.now() - started,
    users: results.length,
    inserted: results.reduce((sum, r) => sum + r.inserted, 0),
    errors: results.filter((r) => r.status === 'error').length,
    skipped: results.filter((r) => r.status === 'skipped').length,
    possibleGaps: results.filter((r) => r.hitPageLimit).map((r) => r.userId),
    results,
  });
});

/** Poll di un singolo utente: utile per il test end-to-end manuale. */
cronRoutes.post('/poll/:userId', async (c) => {
  return c.json(await pollUser(c.req.param('userId')));
});
