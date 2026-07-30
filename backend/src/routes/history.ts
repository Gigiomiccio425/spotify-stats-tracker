import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { getHistory } from '../lib/stats.js';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';
import { intParam } from './util.js';

export const historyRoutes = new Hono<AuthedEnv>();

historyRoutes.use('*', requireAuth);

/**
 * Feed cronologico a scorrimento infinito.
 * Il cursore è "<timestamp>_<id>" e viene restituito già pronto: il client non
 * deve costruirlo, gli basta rimandarlo indietro.
 */
historyRoutes.get('/', async (c) => {
  const user = c.get('user');
  const limit = intParam(c, 'limit', 50, 200);
  const cursor = c.req.query('cursor');

  let before: Date | undefined;
  let beforeId: number | undefined;

  if (cursor) {
    const [ts, id] = cursor.split('_');
    before = new Date(ts ?? '');
    beforeId = Number(id);
    if (Number.isNaN(before.getTime()) || !Number.isFinite(beforeId)) {
      throw new HTTPException(400, { message: 'Cursore non valido' });
    }
  }

  const items = await getHistory(user.id, { before, beforeId, limit });
  const last = items[items.length - 1];

  return c.json({
    items,
    nextCursor: items.length === limit && last ? `${new Date(last.playedAt).toISOString()}_${last.id}` : null,
  });
});
