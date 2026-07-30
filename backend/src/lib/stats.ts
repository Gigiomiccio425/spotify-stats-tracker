import { sql } from 'drizzle-orm';
import { db } from '../db/client.js';

/**
 * Query aggregate sull'archivio.
 *
 * Scritte in SQL esplicito invece che con il query builder: sono tutte
 * aggregazioni con join multipli e window function, e in SQL restano molto più
 * leggibili di una catena di chiamate.
 *
 * Nota sul conteggio per artista: una traccia con un featuring conta un ascolto
 * per OGNI artista accreditato. È il comportamento che si aspettano gli utenti
 * (l'ospite deve comparire fra i suoi artisti), ma significa che la somma degli
 * ascolti per artista può superare il totale degli ascolti.
 */

export interface Range {
  from: Date;
  to: Date;
}

const rows = async <T>(query: ReturnType<typeof sql>): Promise<T[]> =>
  (await db.execute(query)) as unknown as T[];

/** Espressione riutilizzata: artisti di una traccia in ordine di accredito. */
const artistNames = sql`(
  select string_agg(a.name, ', ' order by ta.position)
  from track_artists ta join artists a on a.id = ta.artist_id
  where ta.track_id = t.id
)`;

export interface OverviewStats {
  playCount: number;
  msPlayed: number;
  distinctTracks: number;
  distinctArtists: number;
  distinctAlbums: number;
  listeningDays: number;
  firstPlayAt: string | null;
  lastPlayAt: string | null;
}

export async function getOverview(userId: string, range: Range, timeZone: string): Promise<OverviewStats> {
  const [row] = await rows<OverviewStats>(sql`
    select
      count(*)::int                                          as "playCount",
      coalesce(sum(p.ms_played), 0)::bigint                  as "msPlayed",
      count(distinct p.track_id)::int                        as "distinctTracks",
      count(distinct t.album_id)::int                        as "distinctAlbums",
      count(distinct (p.played_at at time zone ${timeZone})::date)::int as "listeningDays",
      min(p.played_at)                                       as "firstPlayAt",
      max(p.played_at)                                       as "lastPlayAt",
      (
        select count(distinct ta.artist_id)::int
        from plays p2 join track_artists ta on ta.track_id = p2.track_id
        where p2.user_id = ${userId} and p2.played_at >= ${range.from} and p2.played_at < ${range.to}
      )                                                      as "distinctArtists"
    from plays p
    join tracks t on t.id = p.track_id
    where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
  `);

  return {
    playCount: Number(row?.playCount ?? 0),
    msPlayed: Number(row?.msPlayed ?? 0),
    distinctTracks: Number(row?.distinctTracks ?? 0),
    distinctArtists: Number(row?.distinctArtists ?? 0),
    distinctAlbums: Number(row?.distinctAlbums ?? 0),
    listeningDays: Number(row?.listeningDays ?? 0),
    firstPlayAt: row?.firstPlayAt ?? null,
    lastPlayAt: row?.lastPlayAt ?? null,
  };
}

export interface TopTrack {
  id: string;
  name: string;
  artistNames: string | null;
  albumName: string | null;
  imageUrl: string | null;
  durationMs: number;
  playCount: number;
  msPlayed: number;
}

export async function getTopTracks(
  userId: string,
  range: Range,
  limit = 50,
  offset = 0,
): Promise<TopTrack[]> {
  return (
    await rows<TopTrack>(sql`
      select
        t.id, t.name, t.duration_ms as "durationMs",
        ${artistNames}          as "artistNames",
        al.name                 as "albumName",
        al.image_url            as "imageUrl",
        count(*)::int           as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      join tracks t on t.id = p.track_id
      left join albums al on al.id = t.album_id
      where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
      group by t.id, al.name, al.image_url
      order by "playCount" desc, "msPlayed" desc, t.name asc
      limit ${limit} offset ${offset}
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

export interface TopArtist {
  id: string;
  name: string;
  imageUrl: string | null;
  genres: string[];
  playCount: number;
  msPlayed: number;
}

export async function getTopArtists(
  userId: string,
  range: Range,
  limit = 50,
  offset = 0,
): Promise<TopArtist[]> {
  return (
    await rows<TopArtist>(sql`
      select
        a.id, a.name, a.image_url as "imageUrl", a.genres,
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      join track_artists ta on ta.track_id = p.track_id
      join artists a on a.id = ta.artist_id
      where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
      group by a.id
      order by "playCount" desc, "msPlayed" desc, a.name asc
      limit ${limit} offset ${offset}
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

export interface TopAlbum {
  id: string;
  name: string;
  imageUrl: string | null;
  artistNames: string | null;
  playCount: number;
  msPlayed: number;
}

export async function getTopAlbums(
  userId: string,
  range: Range,
  limit = 50,
  offset = 0,
): Promise<TopAlbum[]> {
  return (
    await rows<TopAlbum>(sql`
      select
        al.id, al.name, al.image_url as "imageUrl",
        (
          select string_agg(a.name, ', ' order by aa.position)
          from album_artists aa join artists a on a.id = aa.artist_id
          where aa.album_id = al.id
        )                        as "artistNames",
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      join tracks t on t.id = p.track_id
      join albums al on al.id = t.album_id
      where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
      group by al.id
      order by "playCount" desc, "msPlayed" desc, al.name asc
      limit ${limit} offset ${offset}
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

export interface TopGenre {
  genre: string;
  playCount: number;
  msPlayed: number;
}

/**
 * Spotify non espone un genere per traccia, solo per artista: i generi qui
 * sono l'aggregazione di quelli degli artisti ascoltati.
 */
export async function getTopGenres(userId: string, range: Range, limit = 30): Promise<TopGenre[]> {
  return (
    await rows<TopGenre>(sql`
      select
        g                        as genre,
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      join track_artists ta on ta.track_id = p.track_id
      join artists a on a.id = ta.artist_id
      cross join lateral unnest(a.genres) as g
      where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
      group by g
      order by "playCount" desc, g asc
      limit ${limit}
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

export interface TimelinePoint {
  bucket: string;
  playCount: number;
  msPlayed: number;
}

export async function getTimeline(
  userId: string,
  range: Range,
  bucket: 'day' | 'week' | 'month',
  timeZone: string,
): Promise<TimelinePoint[]> {
  const unit = sql.raw(bucket);
  return (
    await rows<TimelinePoint>(sql`
      select
        to_char(date_trunc(${unit}, p.played_at at time zone ${timeZone}), 'YYYY-MM-DD') as bucket,
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
      group by 1
      order by 1 asc
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

/** Istogramma degli ascolti per ora del giorno, nel fuso dell'utente. */
export async function getListeningClock(
  userId: string,
  range: Range,
  timeZone: string,
): Promise<{ hour: number; playCount: number }[]> {
  const found = await rows<{ hour: number; playCount: number }>(sql`
    select
      extract(hour from p.played_at at time zone ${timeZone})::int as hour,
      count(*)::int as "playCount"
    from plays p
    where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
    group by 1
  `);

  // Restituiamo sempre 24 punti: un grafico con le ore mancanti avrebbe buchi
  // invece di zeri e sarebbe fuorviante.
  const byHour = new Map(found.map((r) => [Number(r.hour), Number(r.playCount)]));
  return Array.from({ length: 24 }, (_, hour) => ({ hour, playCount: byHour.get(hour) ?? 0 }));
}

export interface HistoryItem {
  id: number;
  playedAt: string;
  trackId: string;
  trackName: string;
  artistNames: string | null;
  albumName: string | null;
  imageUrl: string | null;
  msPlayed: number;
}

/** Feed cronologico paginato con cursore su (played_at, id): stabile anche a
 *  parità di timestamp, cosa che un OFFSET non garantisce. */
export async function getHistory(
  userId: string,
  opts: { before?: Date; beforeId?: number; limit?: number } = {},
): Promise<HistoryItem[]> {
  const limit = Math.min(opts.limit ?? 50, 200);
  const cursor =
    opts.before && opts.beforeId !== undefined
      ? sql`and (p.played_at, p.id) < (${opts.before}, ${opts.beforeId})`
      : sql``;

  return (
    await rows<HistoryItem>(sql`
      select
        p.id, p.played_at as "playedAt", p.ms_played as "msPlayed",
        t.id as "trackId", t.name as "trackName",
        ${artistNames} as "artistNames",
        al.name as "albumName", al.image_url as "imageUrl"
      from plays p
      join tracks t on t.id = p.track_id
      left join albums al on al.id = t.album_id
      where p.user_id = ${userId} ${cursor}
      order by p.played_at desc, p.id desc
      limit ${limit}
    `)
  ).map((r) => ({ ...r, id: Number(r.id), msPlayed: Number(r.msPlayed) }));
}

/**
 * Giorni consecutivi con almeno un ascolto, fino a oggi.
 * Se l'ultimo ascolto è più vecchio di ieri la serie è considerata interrotta:
 * "ieri" e non "oggi" perché la giornata in corso non è ancora finita.
 */
export async function getStreak(userId: string, timeZone: string): Promise<number> {
  const days = await rows<{ day: string }>(sql`
    select distinct to_char((p.played_at at time zone ${timeZone})::date, 'YYYY-MM-DD') as day
    from plays p
    where p.user_id = ${userId}
    order by day desc
    limit 400
  `);
  if (days.length === 0) return 0;

  const today = new Date().toLocaleDateString('en-CA', { timeZone });
  const yesterday = new Date(Date.now() - 86400000).toLocaleDateString('en-CA', { timeZone });

  if (days[0]!.day !== today && days[0]!.day !== yesterday) return 0;

  let streak = 1;
  for (let i = 1; i < days.length; i++) {
    const expected = new Date(`${days[i - 1]!.day}T00:00:00Z`);
    expected.setUTCDate(expected.getUTCDate() - 1);
    if (days[i]!.day !== expected.toISOString().slice(0, 10)) break;
    streak++;
  }
  return streak;
}

/** Dettaglio di una singola traccia per l'utente: quando l'ha scoperta, quanto l'ha consumata. */
export async function getTrackDetail(userId: string, trackId: string) {
  const [row] = await rows<{
    id: string;
    name: string;
    artistNames: string | null;
    albumName: string | null;
    imageUrl: string | null;
    durationMs: number;
    playCount: number;
    msPlayed: number;
    firstPlayedAt: string | null;
    lastPlayedAt: string | null;
  }>(sql`
    select
      t.id, t.name, t.duration_ms as "durationMs",
      ${artistNames} as "artistNames",
      al.name as "albumName", al.image_url as "imageUrl",
      count(p.id)::int                    as "playCount",
      coalesce(sum(p.ms_played), 0)::bigint as "msPlayed",
      min(p.played_at)                    as "firstPlayedAt",
      max(p.played_at)                    as "lastPlayedAt"
    from tracks t
    left join albums al on al.id = t.album_id
    left join plays p on p.track_id = t.id and p.user_id = ${userId}
    where t.id = ${trackId}
    group by t.id, al.name, al.image_url
  `);
  if (!row) return null;
  return { ...row, playCount: Number(row.playCount), msPlayed: Number(row.msPlayed) };
}

export async function getArtistDetail(userId: string, artistId: string) {
  const [row] = await rows<{
    id: string;
    name: string;
    imageUrl: string | null;
    genres: string[];
    playCount: number;
    msPlayed: number;
    firstPlayedAt: string | null;
    lastPlayedAt: string | null;
    distinctTracks: number;
  }>(sql`
    select
      a.id, a.name, a.image_url as "imageUrl", a.genres,
      count(p.id)::int                      as "playCount",
      coalesce(sum(p.ms_played), 0)::bigint as "msPlayed",
      count(distinct p.track_id)::int       as "distinctTracks",
      min(p.played_at)                      as "firstPlayedAt",
      max(p.played_at)                      as "lastPlayedAt"
    from artists a
    left join track_artists ta on ta.artist_id = a.id
    left join plays p on p.track_id = ta.track_id and p.user_id = ${userId}
    where a.id = ${artistId}
    group by a.id
  `);
  if (!row) return null;
  return { ...row, playCount: Number(row.playCount), msPlayed: Number(row.msPlayed) };
}
