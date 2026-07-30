import { eq } from 'drizzle-orm';
import { Hono } from 'hono';
import { signSession } from '../auth/jwt.js';
import {
  beginAuthorization,
  consumeState,
  exchangeCode,
  saveCredentials,
} from '../auth/spotify.js';
import { db } from '../db/client.js';
import { users } from '../db/schema.js';
import { env } from '../env.js';
import { runBackfill } from '../jobs/backfill.js';
import { getMe, pickImage } from '../spotify/client.js';

export const authRoutes = new Hono();

/**
 * L'app apre questa URL in una Chrome Custom Tab. Il redirect verso Spotify
 * avviene lato server così il client_id e gli scope restano in un solo posto.
 */
authRoutes.get('/spotify/start', async (c) => {
  const { url } = await beginAuthorization();
  // `?json=1` serve al client per ottenere l'URL senza seguire il redirect.
  if (c.req.query('json') === '1') return c.json({ url });
  return c.redirect(url, 302);
});

/**
 * Redirect URI registrato nella dashboard Spotify. Chiude il flusso e rimanda
 * dentro l'app via deep link con il JWT applicativo.
 */
authRoutes.get('/spotify/callback', async (c) => {
  const error = c.req.query('error');
  if (error) return c.redirect(deepLink({ error }), 302);

  const code = c.req.query('code');
  const state = c.req.query('state');
  if (!code || !state) return c.redirect(deepLink({ error: 'missing_code' }), 302);

  const codeVerifier = await consumeState(state);
  if (!codeVerifier) return c.redirect(deepLink({ error: 'invalid_state' }), 302);

  try {
    const tokens = await exchangeCode(code, codeVerifier);
    const profile = await getMe(tokens.access_token);

    const existing = await db
      .select()
      .from(users)
      .where(eq(users.spotifyUserId, profile.id))
      .limit(1);

    const profileFields = {
      displayName: profile.display_name,
      email: profile.email ?? null,
      country: profile.country ?? null,
      product: profile.product ?? null,
      imageUrl: pickImage(profile.images),
      updatedAt: new Date(),
    };

    let userId: string;
    let isNew = false;

    if (existing[0]) {
      // Ricollegamento: `trackingSince` NON si tocca, altrimenti si azzererebbe
      // il punto di partenza delle statistiche dell'utente.
      userId = existing[0].id;
      await db.update(users).set(profileFields).where(eq(users.id, userId));
    } else {
      const inserted = await db
        .insert(users)
        .values({ spotifyUserId: profile.id, trackingSince: new Date(), ...profileFields })
        .returning({ id: users.id });
      userId = inserted[0]!.id;
      isNew = true;
    }

    await saveCredentials(userId, tokens);

    // Il backfill riempie l'app di dati fin dal primo avvio. Se fallisce non
    // deve bloccare il login: l'archivio vero parte comunque.
    if (isNew) {
      runBackfill(userId).catch((err) => console.error('[backfill] fallito', err));
    }

    const session = await signSession({ sub: userId, spotifyUserId: profile.id });
    return c.redirect(deepLink({ session, new: isNew ? '1' : '0' }), 302);
  } catch (err) {
    console.error('[auth] callback fallito', err);
    const message = err instanceof Error ? err.message : 'unknown';
    // 403 su /me in Development Mode = email non nella lista dei 25 utenti.
    const code = message.includes('403') ? 'not_allowlisted' : 'exchange_failed';
    return c.redirect(deepLink({ error: code }), 302);
  }
});

function deepLink(params: Record<string, string>): string {
  const sep = env.APP_DEEP_LINK.includes('?') ? '&' : '?';
  return `${env.APP_DEEP_LINK}${sep}${new URLSearchParams(params)}`;
}
