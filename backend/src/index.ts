import { serve } from '@hono/node-server';
import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { HTTPException } from 'hono/http-exception';
import { logger } from 'hono/logger';
import { env } from './env.js';
import { startScheduler } from './jobs/scheduler.js';
import { accountRoutes } from './routes/account.js';
import { authRoutes } from './routes/auth.js';
import { cronRoutes } from './routes/cron.js';
import { historyRoutes } from './routes/history.js';
import { importRoutes } from './routes/import.js';
import { recapRoutes } from './routes/recaps.js';
import { statsRoutes } from './routes/stats.js';

const app = new Hono();

app.use('*', logger());
// Il client è un'app Android nativa, non un browser: CORS serve solo per gli
// strumenti di sviluppo e per un'eventuale dashboard web.
app.use('/api/*', cors({ origin: '*', allowHeaders: ['Authorization', 'Content-Type'] }));

app.get('/health', (c) => c.json({ ok: true, now: new Date().toISOString() }));

app.route('/auth', authRoutes);
app.route('/api/account', accountRoutes);
app.route('/api/stats', statsRoutes);
app.route('/api/history', historyRoutes);
app.route('/api/recaps', recapRoutes);
app.route('/api/import', importRoutes);
app.route('/cron', cronRoutes);

app.onError((err, c) => {
  if (err instanceof HTTPException) {
    return c.json({ error: err.message }, err.status);
  }
  console.error('[error]', err);
  return c.json({ error: 'Errore interno' }, 500);
});

app.notFound((c) => c.json({ error: 'Endpoint non trovato' }, 404));

// `0.0.0.0` e non il loopback: dentro un container, restare in ascolto solo su
// 127.0.0.1 renderebbe il servizio irraggiungibile dagli altri container.
serve({ fetch: app.fetch, port: env.PORT, hostname: '0.0.0.0' }, (info) => {
  console.log(`Backend in ascolto sulla porta ${info.port}`);
  console.log(`Collega un account: ${env.APP_BASE_URL}/auth/spotify/start`);
});

const stopScheduler = env.ENABLE_INTERNAL_CRON ? startScheduler() : null;

if (!stopScheduler) {
  console.log(
    '[scheduler] disattivato. Serve un cron esterno che chiami POST /cron/poll, ' +
      'altrimenti nessun ascolto viene archiviato.',
  );
}

// Docker manda SIGTERM allo stop: chiudere il timer evita che un giro parta
// mentre il processo sta uscendo e lasci una riga di poll_runs mai chiusa.
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => {
    stopScheduler?.();
    process.exit(0);
  });
}

export { app };
