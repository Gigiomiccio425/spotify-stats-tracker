import { eq, inArray } from 'drizzle-orm';
import { getAppAccessToken } from '../auth/spotify.js';
import { db } from '../db/client.js';
import { importJobs, plays, tracks } from '../db/schema.js';
import { enrichPendingArtists, upsertTracksFromSpotify } from '../lib/catalog.js';
import { getTracksByIds } from '../spotify/client.js';

/**
 * Una riga del file *Extended Streaming History* che Spotify consegna su
 * richiesta (privacy → "Extended streaming history", ~30 giorni di attesa).
 * È l'unico modo per avere dati anteriori al collegamento dell'account.
 */
export interface StreamingHistoryEntry {
  /** ISO 8601: momento in cui lo stream è FINITO, come `played_at` dell'API. */
  ts: string;
  ms_played: number;
  spotify_track_uri: string | null;
  master_metadata_track_name?: string | null;
  master_metadata_album_artist_name?: string | null;
  master_metadata_album_album_name?: string | null;
}

/** Sotto i 30 secondi Spotify stesso non conta l'ascolto: allineiamoci, così
 *  i dati importati sono confrontabili con quelli raccolti dal poller. */
const MIN_MS_PLAYED = 30_000;
const INSERT_CHUNK = 1000;

export interface ImportResult {
  jobId: string;
  rowsTotal: number;
  rowsImported: number;
  rowsSkipped: number;
}

export async function importStreamingHistory(
  userId: string,
  filename: string,
  entries: StreamingHistoryEntry[],
): Promise<ImportResult> {
  const [job] = await db
    .insert(importJobs)
    .values({ userId, filename, status: 'running', rowsTotal: entries.length })
    .returning({ id: importJobs.id });
  const jobId = job!.id;

  try {
    // I podcast non hanno `spotify_track_uri`, gli ascolti brevi non contano.
    const usable = entries.filter(
      (e) =>
        e.spotify_track_uri?.startsWith('spotify:track:') &&
        e.ms_played >= MIN_MS_PLAYED &&
        !Number.isNaN(Date.parse(e.ts)),
    );

    const trackIds = [...new Set(usable.map((e) => e.spotify_track_uri!.slice('spotify:track:'.length)))];

    // Scarichiamo da Spotify solo i brani che non abbiamo già: un archivio di
    // anni contiene decine di migliaia di righe ma poche migliaia di brani
    // distinti, e molti sono già in catalogo da altri import o dal poller.
    const known = new Set(
      (
        await chunked(trackIds, 5000, (chunk) =>
          db.select({ id: tracks.id }).from(tracks).where(inArray(tracks.id, chunk)),
        )
      ).map((r) => r.id),
    );

    const missing = trackIds.filter((id) => !known.has(id));
    if (missing.length) {
      const token = await getAppAccessToken();
      const fetched = await getTracksByIds(token, missing);
      await upsertTracksFromSpotify(fetched);
      for (const t of fetched) if (t.id) known.add(t.id);
    }

    // Alcuni brani vecchi non esistono più nel catalogo Spotify: senza la riga
    // in `tracks` la foreign key rifiuterebbe l'inserimento.
    const rows = usable
      .map((e) => ({ entry: e, trackId: e.spotify_track_uri!.slice('spotify:track:'.length) }))
      .filter(({ trackId }) => known.has(trackId))
      .map(({ entry, trackId }) => ({
        userId,
        trackId,
        playedAt: new Date(entry.ts),
        msPlayed: entry.ms_played,
        // Qui il dato è reale, non stimato: il file riporta i millisecondi
        // effettivamente ascoltati.
        msEstimated: false,
        source: 'import' as const,
      }));

    let imported = 0;
    for (let i = 0; i < rows.length; i += INSERT_CHUNK) {
      const inserted = await db
        .insert(plays)
        .values(dedupeRows(rows.slice(i, i + INSERT_CHUNK)))
        .onConflictDoNothing()
        .returning({ id: plays.id });
      imported += inserted.length;
    }

    // Un archivio di anni porta dentro migliaia di artisti nuovi. Una sola
    // tornata da 500 ne lascerebbe la maggior parte senza foto né generi, e la
    // coda si smaltirebbe al ritmo di una tornata ogni quarto d'ora. Qui si
    // insiste finché non resta nulla, con un tetto che evita di restare
    // appesi su un archivio smisurato.
    for (let round = 0; round < 40; round++) {
      const enriched = await enrichPendingArtists(500);
      if (enriched === 0) break;
    }

    const result = {
      jobId,
      rowsTotal: entries.length,
      rowsImported: imported,
      rowsSkipped: entries.length - imported,
    };

    await db
      .update(importJobs)
      .set({
        status: 'done',
        rowsImported: result.rowsImported,
        rowsSkipped: result.rowsSkipped,
        finishedAt: new Date(),
      })
      .where(eq(importJobs.id, jobId));

    return result;
  } catch (err) {
    await db
      .update(importJobs)
      .set({ status: 'error', error: String(err).slice(0, 1000), finishedAt: new Date() })
      .where(eq(importJobs.id, jobId));
    throw err;
  }
}

/**
 * Il file può contenere due righe con lo stesso brano e lo stesso timestamp.
 * ON CONFLICT non salva da questo: Postgres rifiuta un INSERT che duplica una
 * chiave *all'interno dello stesso comando*, quindi va deduplicato prima.
 */
function dedupeRows<T extends { trackId: string; playedAt: Date }>(rows: T[]): T[] {
  const seen = new Map<string, T>();
  for (const row of rows) seen.set(`${row.trackId}:${row.playedAt.getTime()}`, row);
  return [...seen.values()];
}

async function chunked<TIn, TOut>(
  items: TIn[],
  size: number,
  fn: (chunk: TIn[]) => Promise<TOut[]>,
): Promise<TOut[]> {
  const out: TOut[] = [];
  for (let i = 0; i < items.length; i += size) out.push(...(await fn(items.slice(i, i + size))));
  return out;
}
