import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import {
  listCompletedPeriods,
  periodContaining,
  periodFromKey,
  zonedToUtc,
  type Period,
  type PeriodType,
} from '../lib/periods.js';
import { buildRecap } from '../lib/recap.js';
import { getActiveDays, getArchiveBounds } from '../lib/stats.js';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';
import { intParam, periodContext } from './util.js';

export const recapRoutes = new Hono<AuthedEnv>();

recapRoutes.use('*', requireAuth);

const TYPES: PeriodType[] = ['day', 'week', 'month', 'year'];

function parseType(raw: string | undefined): PeriodType {
  if (!raw || !TYPES.includes(raw as PeriodType)) {
    throw new HTTPException(400, { message: 'Tipo di recap: day, week, month o year' });
  }
  return raw as PeriodType;
}

/** I giorni si accumulano in fretta: senza un limite più basso la lista dei
 *  recap diventerebbe un elenco di centinaia di voci quasi identiche. */
const DEFAULT_LIMIT: Record<PeriodType, number> = { day: 60, week: 52, month: 36, year: 20 };

/**
 * Elenco dei recap disponibili.
 *
 * Parte dal primo ascolto in archivio e non dal collegamento dell'account: chi
 * ha caricato l'archivio Spotify ha anni di storico precedente, e senza questo
 * resterebbe irraggiungibile. I periodi senza alcun ascolto vengono scartati,
 * perché un archivio importato copre spesso anni con buchi di mesi.
 */
recapRoutes.get('/', async (c) => {
  const user = c.get('user');
  const ctx = periodContext(user);
  const requestedLimit = c.req.query('limit') ? intParam(c, 'limit', 60, 500) : null;

  const typeParam = c.req.query('type');
  const types = typeParam ? [parseType(typeParam)] : TYPES;

  const [bounds, activeDays] = await Promise.all([
    getArchiveBounds(user.id),
    getActiveDays(user.id, ctx.timeZone),
  ]);

  // Mezzogiorno locale di ogni giornata con ascolti: un istante lontano da
  // qualsiasi confine, quindi immune all'ora legale e all'ora di inizio
  // giornata personalizzata.
  const activeInstants = activeDays
    .map((day) => {
      const [y, m, d] = day.split('-').map(Number);
      return zonedToUtc(ctx.timeZone, y!, m!, d!, 12).getTime();
    })
    .sort((a, b) => a - b);

  const hasPlaysIn = (period: Period): boolean => {
    const start = period.start.getTime();
    const end = period.end.getTime();
    // Ricerca binaria del primo istante non precedente all'inizio: con anni di
    // storico una scansione lineare per ogni periodo sarebbe quadratica.
    let low = 0;
    let high = activeInstants.length;
    while (low < high) {
      const mid = (low + high) >> 1;
      if (activeInstants[mid]! < start) low = mid + 1;
      else high = mid;
    }
    return low < activeInstants.length && activeInstants[low]! < end;
  };

  const archiveStart = bounds.firstPlayAt ? new Date(bounds.firstPlayAt) : ctx.trackingSince;
  const since = archiveStart < ctx.trackingSince ? archiveStart : ctx.trackingSince;

  const groups = types.map((type) => {
    const limit = requestedLimit ?? DEFAULT_LIMIT[type];
    const completed = listCompletedPeriods(type, ctx, new Date(), Number.MAX_SAFE_INTEGER, since)
      .filter(hasPlaysIn)
      .slice(0, limit);

    const current = periodContaining(type, ctx);
    return {
      type,
      current: { key: current.key, label: current.label, partial: current.partial, inProgress: true },
      periods: completed.map((p) => ({
        key: p.key,
        label: p.label,
        partial: p.partial,
        start: p.start.toISOString(),
        end: p.end.toISOString(),
      })),
    };
  });

  return c.json({
    mode: ctx.mode,
    trackingSince: user.trackingSince.toISOString(),
    archive: bounds,
    groups,
  });
});

/** Recap completo di un periodo: tutto ciò che serve a disegnare la card. */
recapRoutes.get('/:type/:key', async (c) => {
  const user = c.get('user');
  const ctx = periodContext(user);
  const type = parseType(c.req.param('type'));

  const period = periodFromKey(type, c.req.param('key'), ctx);
  if (!period) throw new HTTPException(404, { message: 'Periodo non trovato' });

  // Periodo precedente per il confronto: si prende quello che contiene
  // l'istante appena prima dell'inizio di questo.
  const previous = periodContaining(type, ctx, new Date(period.start.getTime() - 1));

  return c.json(await buildRecap(user.id, period, ctx.timeZone, previous));
});
