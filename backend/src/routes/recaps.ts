import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { listCompletedPeriods, periodContaining, periodFromKey, type PeriodType } from '../lib/periods.js';
import { buildRecap } from '../lib/recap.js';
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
const DEFAULT_LIMIT: Record<PeriodType, number> = { day: 30, week: 24, month: 24, year: 10 };

/**
 * Elenco dei recap disponibili. Include il periodo in corso marcato
 * `inProgress`, così l'app può mostrarlo in anteprima senza fingere che sia
 * concluso.
 */
recapRoutes.get('/', async (c) => {
  const user = c.get('user');
  const ctx = periodContext(user);
  const requestedLimit = c.req.query('limit') ? intParam(c, 'limit', 24, 200) : null;

  const typeParam = c.req.query('type');
  const types = typeParam ? [parseType(typeParam)] : TYPES;

  const groups = types.map((type) => {
    const completed = listCompletedPeriods(type, ctx, new Date(), requestedLimit ?? DEFAULT_LIMIT[type]);
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

  return c.json({ mode: ctx.mode, trackingSince: user.trackingSince.toISOString(), groups });
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
  const hasPrevious = previous.end.getTime() > ctx.trackingSince.getTime();

  return c.json(await buildRecap(user.id, period, ctx.timeZone, hasPrevious ? previous : undefined));
});
