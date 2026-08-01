import { and, eq, gt, inArray } from 'drizzle-orm';
import { getAppAccessToken } from '../auth/spotify.js';
import { db } from '../db/client.js';
import { importJobs, plays, tracks, users } from '../db/schema.js';
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

/**
 * L'import gira in sottofondo, non dentro la richiesta HTTP.
 *
 * Un archivio di anni contiene decine di migliaia di brani distinti: il server
 * deve chiederli a Spotify 50 per volta, rispettando il rate limit. Sono
 * minuti, a volte parecchi. Nessuna richiesta HTTP sopravvive: il client
 * andrebbe in timeout, e un proxy davanti al server (Cloudflare chiude a 100
 * secondi) taglierebbe comunque la connessione. Il risultato era sempre lo
 * stesso, un errore di rete su un import che nel frattempo stava andando a
 * buon fine.
 *
 * Quindi: la richiesta accoda e risponde subito, il client segue il job.
 */
interface QueuedJob {
  jobId: string;
  userId: string;
  entries: StreamingHistoryEntry[];
}

const queue: QueuedJob[] = [];
let draining = false;

/**
 * Stato dell'arricchimento degli artisti, che parte quando la coda si svuota.
 * Non appartiene a un singolo file: un archivio caricato in dieci pezzi porta
 * gli stessi artisti, e ripetere il recupero per ogni pezzo moltiplicherebbe
 * le chiamate all'API senza aggiungere nulla.
 */
let enrichment = { running: false, done: 0 };

export interface QueuedImport {
  jobId: string;
  rowsTotal: number;
  /** Quanti file ci sono davanti a questo, incluso quello in lavorazione. */
  queuePosition: number;
}

/** Job dell'utente ancora aperto: uno per volta, vedi `queueImport`. */
export async function findOpenImportJob(userId: string) {
  const [open] = await db
    .select()
    .from(importJobs)
    .where(and(eq(importJobs.userId, userId), inArray(importJobs.status, ['pending', 'running'])))
    .limit(1);
  return open ?? null;
}

/**
 * Accoda un file e restituisce subito l'identificativo del job.
 *
 * Un solo job aperto per utente, per due motivi: le righe del file restano in
 * memoria finché il job non parte, e dieci file in coda insieme sono centinaia
 * di MB; e due import paralleli si contenderebbero il rate limit di Spotify
 * rallentando entrambi.
 */
export async function queueImport(
  userId: string,
  filename: string,
  entries: StreamingHistoryEntry[],
): Promise<QueuedImport> {
  const [job] = await db
    .insert(importJobs)
    .values({
      userId,
      filename,
      status: 'pending',
      phase: 'In coda',
      rowsTotal: entries.length,
    })
    .returning({ id: importJobs.id });

  const jobId = job!.id;
  queue.push({ jobId, userId, entries });
  const queuePosition = queue.length;

  // Volutamente senza await: la risposta HTTP non deve aspettare l'import.
  void drain();

  return { jobId, rowsTotal: entries.length, queuePosition };
}

export function enrichmentStatus(): { running: boolean; done: number } {
  return { ...enrichment };
}

async function drain(): Promise<void> {
  if (draining) return;
  draining = true;

  try {
    for (let next = queue.shift(); next; next = queue.shift()) {
      try {
        await runImport(next);
      } catch (err) {
        console.error(`[import] job ${next.jobId} fallito`, err);
      }
    }

    await sweepArtists();
  } finally {
    draining = false;
    // Un file accodato mentre uscivamo dal ciclo resterebbe fermo: qui la coda
    // è di nuovo libera, quindi si riparte.
    if (queue.length) void drain();
  }
}

async function setPhase(jobId: string, phase: string): Promise<void> {
  await db.update(importJobs).set({ phase }).where(eq(importJobs.id, jobId));
}

async function runImport({ jobId, userId, entries }: QueuedJob): Promise<void> {
  await db
    .update(importJobs)
    .set({ status: 'running', phase: 'Lettura del file', startedAt: new Date() })
    .where(eq(importJobs.id, jobId));

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
      // 50 id per chiamata: il conto delle chiamate dice all'utente quanto
      // manca meglio di qualsiasi percentuale inventata.
      for (let i = 0; i < missing.length; i += 1000) {
        const slice = missing.slice(i, i + 1000);
        await setPhase(
          jobId,
          `Brani da Spotify: ${Math.min(i + slice.length, missing.length)} di ${missing.length}`,
        );
        const fetched = await getTracksByIds(token, slice);
        await upsertTracksFromSpotify(fetched);
        for (const t of fetched) if (t.id) known.add(t.id);
      }
    }

    await setPhase(jobId, 'Salvataggio degli ascolti');

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

      // L'avanzamento va scritto man mano: su un file da centomila righe
      // l'inserimento da solo dura minuti, e senza un numero che si muove non
      // si distingue un lavoro lento da un lavoro fermo.
      if (i % (INSERT_CHUNK * 10) === 0) {
        const done = Math.min(i + INSERT_CHUNK, rows.length);
        await db
          .update(importJobs)
          .set({ rowsImported: imported, phase: `Salvataggio ascolti: ${done} di ${rows.length}` })
          .where(eq(importJobs.id, jobId));
      }
    }

    await db
      .update(importJobs)
      .set({
        status: 'done',
        phase: null,
        rowsImported: imported,
        rowsSkipped: entries.length - imported,
        finishedAt: new Date(),
      })
      .where(eq(importJobs.id, jobId));

    // Le righe importate precedono il collegamento dell'account: senza
    // arretrare `tracking_since` i periodi più vecchi resterebbero fuori da
    // statistiche e recap, pur avendo i dati in archivio.
    await lowerTrackingSince(userId, rows);
  } catch (err) {
    await db
      .update(importJobs)
      .set({
        status: 'error',
        phase: null,
        error: String(err).slice(0, 1000),
        finishedAt: new Date(),
      })
      .where(eq(importJobs.id, jobId));
    throw err;
  }
}

/**
 * Recupera foto, generi e popolarità degli artisti nuovi.
 *
 * Gira una volta sola a coda vuota, e senza tetto di tornate: un archivio di
 * anni porta dentro decine di migliaia di artisti, e un limite basso li
 * lascerebbe senza foto per giorni, perché il poller ne smaltisce cento ogni
 * quarto d'ora.
 */
async function sweepArtists(): Promise<void> {
  enrichment = { running: true, done: 0 };
  try {
    for (;;) {
      const enriched = await enrichPendingArtists(500);
      if (enriched === 0) break;
      enrichment = { running: true, done: enrichment.done + enriched };
      // Un file caricato nel frattempo ha la precedenza: gli ascolti valgono
      // più delle copertine.
      if (queue.length) break;
    }
  } catch (err) {
    console.error('[import] recupero artisti interrotto', err);
  } finally {
    enrichment = { running: false, done: enrichment.done };
  }
}

/**
 * I job restano in memoria: un riavvio del server li perde. Senza questa
 * pulizia resterebbero `running` per sempre e, dato che se ne ammette uno solo
 * per utente, bloccherebbero ogni import successivo.
 */
export async function resetStuckImportJobs(): Promise<void> {
  const stuck = await db
    .update(importJobs)
    .set({
      status: 'error',
      phase: null,
      error: 'Interrotto dal riavvio del server. Ricarica il file.',
      finishedAt: new Date(),
    })
    .where(inArray(importJobs.status, ['pending', 'running']))
    .returning({ id: importJobs.id });

  if (stuck.length) {
    console.log(`[import] ${stuck.length} job interrotti dal riavvio, segnati come falliti`);
  }
}

/** Vedi `poll.ts`: stessa correzione, applicata dopo un import. */
async function lowerTrackingSince(userId: string, rows: { playedAt: Date }[]): Promise<void> {
  if (rows.length === 0) return;
  const earliest = rows.reduce((min, r) => (r.playedAt < min ? r.playedAt : min), rows[0]!.playedAt);
  await db
    .update(users)
    .set({ trackingSince: earliest })
    .where(and(eq(users.id, userId), gt(users.trackingSince, earliest)));
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
