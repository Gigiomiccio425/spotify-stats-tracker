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

/**
 * Le date vanno passate a Postgres in ISO, non come oggetti `Date`.
 *
 * Dentro un template `sql` grezzo Drizzle non conosce il tipo della colonna a
 * cui il parametro si riferisce, quindi consegna l'oggetto cosi' com'e' al
 * driver, che prova a scriverlo come stringa e fallisce con
 * ERR_INVALID_ARG_TYPE. Nelle query costruite con il query builder il problema
 * non si pone, perche' li' il tipo di colonna e' noto.
 */
export const ts = (value: Date): string => value.toISOString();

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
        where p2.user_id = ${userId} and p2.played_at >= ${ts(range.from)} and p2.played_at < ${ts(range.to)}
      )                                                      as "distinctArtists"
    from plays p
    join tracks t on t.id = p.track_id
    where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
      where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
      where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
      where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
      where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
  return (
    await rows<TimelinePoint>(sql`
      select
        -- L'unità va passata come parametro, non interpolata nel testo della
        -- query: date_trunc vuole un valore di tipo text, e date_trunc(day, ...)
        -- senza apici farebbe cercare a Postgres una colonna di nome "day".
        to_char(date_trunc(${bucket}, p.played_at at time zone ${timeZone}), 'YYYY-MM-DD') as bucket,
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
      group by 1
      order by 1 asc
    `)
  ).map((r) => ({ ...r, playCount: Number(r.playCount), msPlayed: Number(r.msPlayed) }));
}

/** Ascolti per giorno della settimana, con lunedì = 1 come vuole lo standard ISO. */
export async function getWeekdayStats(
  userId: string,
  range: Range,
  timeZone: string,
): Promise<{ weekday: number; playCount: number; msPlayed: number }[]> {
  const found = await rows<{ weekday: number; playCount: number; msPlayed: number }>(sql`
    select
      extract(isodow from p.played_at at time zone ${timeZone})::int as weekday,
      count(*)::int            as "playCount",
      sum(p.ms_played)::bigint as "msPlayed"
    from plays p
    where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
    group by 1
  `);

  // Sempre sette punti, anche a zero: un grafico che salta i giorni vuoti
  // farebbe sembrare il lunedì adiacente al mercoledì.
  const byDay = new Map(found.map((r) => [Number(r.weekday), r]));
  return Array.from({ length: 7 }, (_, i) => {
    const row = byDay.get(i + 1);
    return {
      weekday: i + 1,
      playCount: Number(row?.playCount ?? 0),
      msPlayed: Number(row?.msPlayed ?? 0),
    };
  });
}

/**
 * Quanti degli artisti ascoltati hanno almeno un genere.
 *
 * Serve a distinguere due situazioni che l'utente vedrebbe identiche: una
 * classifica dei generi vuota perché non c'è ancora nulla in archivio, e una
 * vuota perché Spotify non attribuisce generi a quegli artisti.
 */
export async function getGenreCoverage(
  userId: string,
  range: Range,
): Promise<{ artistsTotal: number; artistsWithGenres: number }> {
  const [row] = await rows<{ artistsTotal: number; artistsWithGenres: number }>(sql`
    select
      count(distinct a.id)::int                                              as "artistsTotal",
      count(distinct a.id) filter (where cardinality(a.genres) > 0)::int     as "artistsWithGenres"
    from plays p
    join track_artists ta on ta.track_id = p.track_id
    join artists a on a.id = ta.artist_id
    where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
  `);

  return {
    artistsTotal: Number(row?.artistsTotal ?? 0),
    artistsWithGenres: Number(row?.artistsWithGenres ?? 0),
  };
}

export interface ReleaseYearStats {
  years: { year: number; playCount: number; msPlayed: number }[];
  decades: { decade: number; playCount: number; share: number }[];
  /** Anno di pubblicazione medio, pesato sugli ascolti: l'"età musicale". */
  averageYear: number | null;
  /** Meno sensibile della media a un singolo brano molto vecchio o recente. */
  medianYear: number | null;
  oldestYear: number | null;
  newestYear: number | null;
  /** Ascolti su cui il calcolo si basa: alcuni album non hanno data. */
  coveredPlays: number;
}

/**
 * Distribuzione degli ascolti per anno di pubblicazione.
 *
 * Spotify espone `release_date` come testo, con precisione variabile: a volte
 * "1998", a volte "1998-04-21". I primi quattro caratteri sono l'anno in
 * entrambi i casi; le righe senza una data riconoscibile vengono escluse
 * invece che contate come anno zero, che sposterebbe la media di secoli.
 */
export async function getReleaseYearStats(
  userId: string,
  range: Range,
): Promise<ReleaseYearStats> {
  const raw = (
    await rows<{ year: number; playCount: number; msPlayed: number }>(sql`
      select
        substring(al.release_date from 1 for 4)::int as year,
        count(*)::int            as "playCount",
        sum(p.ms_played)::bigint as "msPlayed"
      from plays p
      join tracks t on t.id = p.track_id
      join albums al on al.id = t.album_id
      where p.user_id = ${userId}
        and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
        and al.release_date ~ '^[0-9]{4}'
      group by 1
      order by 1 asc
    `)
  ).map((r) => ({
    year: Number(r.year),
    playCount: Number(r.playCount),
    msPlayed: Number(r.msPlayed),
  }));

  const coveredPlays = raw.reduce((sum, r) => sum + r.playCount, 0);

  if (coveredPlays === 0) {
    return {
      years: [],
      decades: [],
      averageYear: null,
      medianYear: null,
      oldestYear: null,
      newestYear: null,
      coveredPlays: 0,
    };
  }

  const weightedSum = raw.reduce((sum, r) => sum + r.year * r.playCount, 0);

  // Mediana pesata: si avanza lungo gli anni ordinati finché non si supera
  // metà degli ascolti. Non serve espandere la lista ascolto per ascolto.
  const half = coveredPlays / 2;
  let running = 0;
  let medianYear = raw[0]!.year;
  for (const entry of raw) {
    running += entry.playCount;
    if (running >= half) {
      medianYear = entry.year;
      break;
    }
  }

  const byDecade = new Map<number, number>();
  for (const entry of raw) {
    const decade = Math.floor(entry.year / 10) * 10;
    byDecade.set(decade, (byDecade.get(decade) ?? 0) + entry.playCount);
  }

  return {
    years: raw,
    decades: [...byDecade.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([decade, playCount]) => ({
        decade,
        playCount,
        share: Math.round((playCount / coveredPlays) * 100),
      })),
    averageYear: Math.round(weightedSum / coveredPlays),
    medianYear,
    oldestYear: raw[0]!.year,
    newestYear: raw[raw.length - 1]!.year,
    coveredPlays,
  };
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
    where p.user_id = ${userId} and p.played_at >= ${ts(range.from)} and p.played_at < ${ts(range.to)}
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
      ? sql`and (p.played_at, p.id) < (${ts(opts.before)}, ${opts.beforeId})`
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
