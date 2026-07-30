import { and, desc, eq, isNull, ne, sql } from 'drizzle-orm';
import { TokenRevokedError, getValidAccessToken } from '../auth/spotify.js';
import { db } from '../db/client.js';
import { plays, pollRuns, spotifyCredentials, users } from '../db/schema.js';
import { enrichPendingArtists, upsertTracksFromSpotify } from '../lib/catalog.js';
import { SpotifyError, getRecentlyPlayed } from '../spotify/client.js';

/** Massimo restituito da `recently-played`. Se ne torniamo esattamente 50,
 *  la finestra era piena e quasi certamente qualcosa è andato perso. */
const PAGE_LIMIT = 50;

export interface PollResult {
  userId: string;
  fetched: number;
  inserted: number;
  hitPageLimit: boolean;
  status: 'ok' | 'error' | 'skipped';
  error?: string;
}

/**
 * Il cuore del sistema: legge le tracce ascoltate da Spotify e le archivia.
 *
 * Idempotente per costruzione: l'inserimento usa il vincolo unico
 * (user, track, played_at) con ON CONFLICT DO NOTHING, quindi rieseguire il
 * poll sulla stessa finestra non crea duplicati.
 */
export async function pollUser(userId: string): Promise<PollResult> {
  const [run] = await db.insert(pollRuns).values({ userId }).returning({ id: pollRuns.id });
  const runId = run!.id;

  const finish = async (result: Omit<PollResult, 'userId'>) => {
    await db
      .update(pollRuns)
      .set({
        finishedAt: new Date(),
        itemsFetched: result.fetched,
        itemsInserted: result.inserted,
        hitPageLimit: result.hitPageLimit,
        status: result.status,
        error: result.error ?? null,
      })
      .where(eq(pollRuns.id, runId));
    return { userId, ...result };
  };

  try {
    const accessToken = await getValidAccessToken(userId);

    // Riprendiamo da dove eravamo rimasti. Gli ascolti importati dal file GDPR
    // sono esclusi: sono storici e sposterebbero il cursore nel passato.
    const [last] = await db
      .select({ playedAt: plays.playedAt })
      .from(plays)
      .where(and(eq(plays.userId, userId), ne(plays.source, 'import')))
      .orderBy(desc(plays.playedAt))
      .limit(1);

    const after = last ? last.playedAt.getTime() : undefined;
    const response = await getRecentlyPlayed(accessToken, { after, limit: PAGE_LIMIT });
    const items = response.items ?? [];

    if (items.length === 0) {
      return finish({ fetched: 0, inserted: 0, hitPageLimit: false, status: 'ok' });
    }

    // Le tracce locali dell'utente non hanno id Spotify: non archiviabili.
    const usable = items.filter((i) => i.track?.id && !i.track.is_local);
    await upsertTracksFromSpotify(usable.map((i) => i.track));

    const rows = usable.map((item) => ({
      userId,
      trackId: item.track.id!,
      playedAt: new Date(item.played_at),
      // `recently-played` non dice quanto è stato ascoltato davvero: la durata
      // della traccia è la stima migliore disponibile. Il campionatore
      // now-playing la corregge per gli ascolti che intercetta.
      msPlayed: item.track.duration_ms,
      msEstimated: true,
      contextType: item.context?.type ?? null,
      contextUri: item.context?.uri ?? null,
      source: 'recently_played' as const,
    }));

    const inserted = rows.length
      ? await db.insert(plays).values(rows).onConflictDoNothing().returning({ id: plays.id })
      : [];

    return finish({
      fetched: items.length,
      inserted: inserted.length,
      hitPageLimit: items.length >= PAGE_LIMIT,
      status: 'ok',
    });
  } catch (err) {
    if (err instanceof TokenRevokedError) {
      return finish({
        fetched: 0,
        inserted: 0,
        hitPageLimit: false,
        status: 'skipped',
        error: err.message,
      });
    }
    const message = err instanceof SpotifyError ? err.message : String(err);
    console.error(`[poll] utente ${userId}`, message);
    return finish({ fetched: 0, inserted: 0, hitPageLimit: false, status: 'error', error: message.slice(0, 500) });
  }
}

/** Utenti da pollare: quelli con credenziali ancora valide. */
async function activeUserIds(): Promise<string[]> {
  const rows = await db
    .select({ id: users.id })
    .from(users)
    .innerJoin(spotifyCredentials, eq(spotifyCredentials.userId, users.id))
    .where(isNull(spotifyCredentials.invalidatedAt));
  return rows.map((r) => r.id);
}

/**
 * Esegue il poll per tutti gli utenti attivi.
 * Concorrenza volutamente bassa: il rate limit di Spotify è per applicazione,
 * non per utente, quindi 25 richieste simultanee farebbero scattare il 429.
 */
export async function pollAllUsers(concurrency = 3): Promise<PollResult[]> {
  const ids = await activeUserIds();
  const results: PollResult[] = [];

  for (let i = 0; i < ids.length; i += concurrency) {
    const chunk = ids.slice(i, i + concurrency);
    results.push(...(await Promise.all(chunk.map((id) => pollUser(id)))));
  }

  // Completa in coda gli artisti nuovi (generi, immagini): non è urgente e non
  // deve rallentare l'archiviazione degli ascolti.
  try {
    await enrichPendingArtists();
  } catch (err) {
    console.error('[poll] arricchimento artisti fallito', err);
  }

  return results;
}

/** Ultimo esito del poll per un utente, mostrato in Impostazioni. */
export async function lastPollStatus(userId: string) {
  const [row] = await db
    .select()
    .from(pollRuns)
    .where(eq(pollRuns.userId, userId))
    .orderBy(desc(pollRuns.startedAt))
    .limit(1);

  const [gaps] = await db
    .select({ count: sql<number>`count(*)::int` })
    .from(pollRuns)
    .where(and(eq(pollRuns.userId, userId), eq(pollRuns.hitPageLimit, true)));

  return { last: row ?? null, possibleGaps: gaps?.count ?? 0 };
}
