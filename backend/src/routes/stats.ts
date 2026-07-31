import { Hono } from 'hono';
import { HTTPException } from 'hono/http-exception';
import { requireAuth, type AuthedEnv } from '../middleware/auth.js';
import {
  getArtistDetail,
  getGenreCoverage,
  getListeningClock,
  getOverview,
  getReleaseYearStats,
  getStreak,
  getTimeline,
  getTopAlbums,
  getTopArtists,
  getTopGenres,
  getTopTracks,
  getTrackDetail,
  getWeekdayStats,
} from '../lib/stats.js';
import { intParam, resolveRange } from './util.js';

export const statsRoutes = new Hono<AuthedEnv>();

statsRoutes.use('*', requireAuth);

statsRoutes.get('/overview', async (c) => {
  const user = c.get('user');
  const range = resolveRange(c, user);

  const [overview, streak, topTracks, topArtists] = await Promise.all([
    getOverview(user.id, range, user.timezone),
    getStreak(user.id, user.timezone),
    getTopTracks(user.id, range, 5),
    getTopArtists(user.id, range, 5),
  ]);

  return c.json({
    range: { from: range.from.toISOString(), to: range.to.toISOString() },
    trackingSince: user.trackingSince.toISOString(),
    ...overview,
    minutesPlayed: Math.round(overview.msPlayed / 60000),
    streak,
    topTracks,
    topArtists,
  });
});

statsRoutes.get('/top/:kind', async (c) => {
  const user = c.get('user');
  const range = resolveRange(c, user);
  const limit = intParam(c, 'limit', 50, 200);
  const offset = intParam(c, 'offset', 0, 100_000);
  const kind = c.req.param('kind');

  switch (kind) {
    case 'tracks':
      return c.json({ items: await getTopTracks(user.id, range, limit, offset) });
    case 'artists':
      return c.json({ items: await getTopArtists(user.id, range, limit, offset) });
    case 'albums':
      return c.json({ items: await getTopAlbums(user.id, range, limit, offset) });
    case 'genres': {
      // La copertura viaggia insieme alla classifica: senza, una lista vuota
      // non direbbe se manca la musica o i generi.
      const [items, coverage] = await Promise.all([
        getTopGenres(user.id, range, limit),
        getGenreCoverage(user.id, range),
      ]);
      return c.json({ items, ...coverage });
    }
    default:
      throw new HTTPException(404, { message: `Tipo "${kind}" sconosciuto` });
  }
});

statsRoutes.get('/timeline', async (c) => {
  const user = c.get('user');
  const range = resolveRange(c, user);
  const bucket = c.req.query('bucket') ?? 'day';
  if (bucket !== 'day' && bucket !== 'week' && bucket !== 'month') {
    throw new HTTPException(400, { message: 'bucket deve essere day, week o month' });
  }
  return c.json({ points: await getTimeline(user.id, range, bucket, user.timezone) });
});

statsRoutes.get('/clock', async (c) => {
  const user = c.get('user');
  const range = resolveRange(c, user);
  return c.json({ hours: await getListeningClock(user.id, range, user.timezone) });
});

/**
 * Distribuzione degli ascolti per anno di pubblicazione, con l'anno medio
 * ponderato: quanto è "vecchia" la musica che si ascolta.
 */
statsRoutes.get('/weekdays', async (c) => {
  const user = c.get('user');
  const range = resolveRange(c, user);
  return c.json({ days: await getWeekdayStats(user.id, range, user.timezone) });
});

statsRoutes.get('/release-years', async (c) => {
  const user = c.get('user');
  return c.json(await getReleaseYearStats(user.id, resolveRange(c, user)));
});

statsRoutes.get('/track/:id', async (c) => {
  const detail = await getTrackDetail(c.get('user').id, c.req.param('id'));
  if (!detail) throw new HTTPException(404, { message: 'Traccia non trovata' });
  return c.json(detail);
});

statsRoutes.get('/artist/:id', async (c) => {
  const user = c.get('user');
  const detail = await getArtistDetail(user.id, c.req.param('id'));
  if (!detail) throw new HTTPException(404, { message: 'Artista non trovato' });
  const range = resolveRange(c, user);
  const topTracks = await getTopTracks(user.id, range, 100);
  return c.json({
    ...detail,
    // Le tracce di questo artista fra le più ascoltate dell'utente.
    topTracks: topTracks.filter((t) => (t.artistNames ?? '').includes(detail.name)).slice(0, 10),
  });
});
