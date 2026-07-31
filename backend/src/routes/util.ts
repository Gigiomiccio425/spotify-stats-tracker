import type { Context } from 'hono';
import { HTTPException } from 'hono/http-exception';
import type { User } from '../db/schema.js';
import { type PeriodContext, type PeriodMode, presetRange } from '../lib/periods.js';
import type { Range } from '../lib/stats.js';

export function periodContext(user: User): PeriodContext {
  return {
    mode: user.periodMode as PeriodMode,
    timeZone: user.timezone,
    trackingSince: user.trackingSince,
    dayStartHour: user.dailyRecapHour,
  };
}

/**
 * Risolve la finestra temporale di una richiesta.
 * `from`/`to` espliciti hanno la precedenza; altrimenti si usa il preset
 * `range`, che di default copre tutto dal collegamento dell'account.
 */
export function resolveRange(c: Context, user: User): Range {
  const ctx = periodContext(user);
  const fromParam = c.req.query('from');
  const toParam = c.req.query('to');

  if (fromParam || toParam) {
    const from = fromParam ? new Date(fromParam) : ctx.trackingSince;
    const to = toParam ? new Date(toParam) : new Date();
    if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) {
      throw new HTTPException(400, { message: 'Parametri from/to non validi' });
    }
    return { from, to };
  }

  return presetRange(c.req.query('range') ?? 'since_tracking', ctx);
}

export function intParam(c: Context, name: string, fallback: number, max: number): number {
  const raw = c.req.query(name);
  if (raw === undefined) return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    throw new HTTPException(400, { message: `Parametro ${name} non valido` });
  }
  return Math.min(Math.trunc(value), max);
}
