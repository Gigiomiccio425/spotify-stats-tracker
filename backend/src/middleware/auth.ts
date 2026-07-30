import { eq } from 'drizzle-orm';
import type { MiddlewareHandler } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { verifySession } from '../auth/jwt.js';
import { db } from '../db/client.js';
import { users, type User } from '../db/schema.js';
import { env } from '../env.js';

export type AuthedEnv = { Variables: { user: User } };

export const requireAuth: MiddlewareHandler<AuthedEnv> = async (c, next) => {
  const header = c.req.header('authorization');
  if (!header?.startsWith('Bearer ')) {
    throw new HTTPException(401, { message: 'Token di sessione mancante' });
  }

  let sub: string;
  try {
    ({ sub } = await verifySession(header.slice(7)));
  } catch {
    throw new HTTPException(401, { message: 'Sessione non valida o scaduta' });
  }

  const rows = await db.select().from(users).where(eq(users.id, sub)).limit(1);
  const user = rows[0];
  if (!user) throw new HTTPException(401, { message: 'Utente non trovato' });

  c.set('user', user);
  await next();
};

/** Protegge gli endpoint invocati dal cron esterno. */
export const requireCronSecret: MiddlewareHandler = async (c, next) => {
  const header = c.req.header('authorization');
  const provided = header?.startsWith('Bearer ') ? header.slice(7) : c.req.header('x-cron-secret');
  if (provided !== env.CRON_SECRET) {
    throw new HTTPException(401, { message: 'Cron secret non valido' });
  }
  await next();
};
