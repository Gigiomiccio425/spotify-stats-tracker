import { sql } from 'drizzle-orm';
import { db } from '../db/client.js';
import type { Period } from './periods.js';
import {
  getOverview,
  getTopAlbums,
  getTopArtists,
  getTopGenres,
  getTopTracks,
  type Range,
} from './stats.js';

/**
 * Payload di un recap: tutto ciò che serve alla card condivisibile in una sola
 * chiamata. Il client non deve comporre più risposte per disegnare l'immagine.
 */
export interface Recap {
  period: {
    type: Period['type'];
    key: string;
    label: string;
    start: string;
    end: string;
    /** true = il tracking è iniziato dopo l'inizio del periodo, i dati coprono
     *  solo una parte della finestra. Va mostrato sulla card. */
    partial: boolean;
  };
  totals: {
    playCount: number;
    minutesPlayed: number;
    distinctTracks: number;
    distinctArtists: number;
    listeningDays: number;
  };
  topTracks: Awaited<ReturnType<typeof getTopTracks>>;
  topArtists: Awaited<ReturnType<typeof getTopArtists>>;
  topAlbums: Awaited<ReturnType<typeof getTopAlbums>>;
  topGenres: Awaited<ReturnType<typeof getTopGenres>>;
  busiestDay: { day: string; playCount: number; minutesPlayed: number } | null;
  /** Variazione dei minuti rispetto al periodo precedente, in percentuale.
   *  null quando non esiste un periodo precedente con cui confrontarsi. */
  minutesChangePct: number | null;
}

async function getBusiestDay(userId: string, range: Range, timeZone: string) {
  const result = (await db.execute(sql`
    select
      to_char((p.played_at at time zone ${timeZone})::date, 'YYYY-MM-DD') as day,
      count(*)::int            as "playCount",
      sum(p.ms_played)::bigint as "msPlayed"
    from plays p
    where p.user_id = ${userId} and p.played_at >= ${range.from} and p.played_at < ${range.to}
    group by 1
    order by "playCount" desc
    limit 1
  `)) as unknown as { day: string; playCount: number; msPlayed: number }[];

  const row = result[0];
  if (!row) return null;
  return {
    day: row.day,
    playCount: Number(row.playCount),
    minutesPlayed: Math.round(Number(row.msPlayed) / 60000),
  };
}

export async function buildRecap(
  userId: string,
  period: Period,
  timeZone: string,
  previous?: Period,
): Promise<Recap> {
  const range: Range = { from: period.start, to: period.end };

  const [overview, topTracks, topArtists, topAlbums, topGenres, busiestDay, previousOverview] =
    await Promise.all([
      getOverview(userId, range, timeZone),
      getTopTracks(userId, range, 10),
      getTopArtists(userId, range, 10),
      getTopAlbums(userId, range, 5),
      getTopGenres(userId, range, 5),
      getBusiestDay(userId, range, timeZone),
      previous
        ? getOverview(userId, { from: previous.start, to: previous.end }, timeZone)
        : Promise.resolve(null),
    ]);

  // Il confronto ha senso solo se il periodo precedente aveva dati: partire da
  // zero darebbe sempre "+infinito%".
  const minutesChangePct =
    previousOverview && previousOverview.msPlayed > 0
      ? Math.round(((overview.msPlayed - previousOverview.msPlayed) / previousOverview.msPlayed) * 100)
      : null;

  return {
    period: {
      type: period.type,
      key: period.key,
      label: period.label,
      start: period.start.toISOString(),
      end: period.end.toISOString(),
      partial: period.partial,
    },
    totals: {
      playCount: overview.playCount,
      minutesPlayed: Math.round(overview.msPlayed / 60000),
      distinctTracks: overview.distinctTracks,
      distinctArtists: overview.distinctArtists,
      listeningDays: overview.listeningDays,
    },
    topTracks,
    topArtists,
    topAlbums,
    topGenres,
    busiestDay,
    minutesChangePct,
  };
}
