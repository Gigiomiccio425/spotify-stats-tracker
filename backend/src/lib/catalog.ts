import { inArray, sql } from 'drizzle-orm';
import { getAppAccessToken } from '../auth/spotify.js';
import { db } from '../db/client.js';
import { albumArtists, albums, artists, trackArtists, tracks } from '../db/schema.js';
import { getArtistsByIds, pickImage } from '../spotify/client.js';
import type { SpotifyArtist, SpotifyTrack } from '../spotify/types.js';

/**
 * Salva nel catalogo locale tutto ciò che serve per poter inserire un ascolto.
 *
 * Gli oggetti che arrivano da `recently-played` contengono artisti "semplici"
 * (solo id e nome): niente generi né immagini. Qui li inseriamo con i dati che
 * abbiamo e lasciamo `popularity` a NULL, che diventa il marcatore usato da
 * `enrichPendingArtists` per completarli dopo.
 */
export async function upsertTracksFromSpotify(spotifyTracks: SpotifyTrack[]): Promise<void> {
  const usable = spotifyTracks.filter((t) => t.id && !t.is_local);
  if (usable.length === 0) return;

  const artistRows = new Map<string, { id: string; name: string }>();
  const albumRows = new Map<string, typeof albums.$inferInsert>();
  const trackRows = new Map<string, typeof tracks.$inferInsert>();
  const trackArtistRows: (typeof trackArtists.$inferInsert)[] = [];
  const albumArtistRows: (typeof albumArtists.$inferInsert)[] = [];

  for (const t of usable) {
    const trackId = t.id!;

    for (const [i, a] of t.artists.entries()) {
      if (!a.id) continue;
      artistRows.set(a.id, { id: a.id, name: a.name });
      trackArtistRows.push({ trackId, artistId: a.id, position: i });
    }

    if (t.album?.id) {
      albumRows.set(t.album.id, {
        id: t.album.id,
        name: t.album.name,
        imageUrl: pickImage(t.album.images),
        releaseDate: t.album.release_date ?? null,
        releaseDatePrecision: t.album.release_date_precision ?? null,
        totalTracks: t.album.total_tracks ?? null,
        albumType: t.album.album_type ?? null,
      });
      for (const [i, a] of (t.album.artists ?? []).entries()) {
        if (!a.id) continue;
        artistRows.set(a.id, { id: a.id, name: a.name });
        albumArtistRows.push({ albumId: t.album.id, artistId: a.id, position: i });
      }
    }

    trackRows.set(trackId, {
      id: trackId,
      name: t.name,
      albumId: t.album?.id ?? null,
      durationMs: t.duration_ms,
      explicit: t.explicit ?? false,
      isrc: t.external_ids?.isrc ?? null,
      popularity: t.popularity ?? null,
    });
  }

  // Ordine obbligato dalle foreign key: artisti, album, tracce, poi i ponti.
  if (artistRows.size) {
    await db
      .insert(artists)
      .values([...artistRows.values()])
      .onConflictDoUpdate({ target: artists.id, set: { name: sql`excluded.name` } });
  }

  if (albumRows.size) {
    await db
      .insert(albums)
      .values([...albumRows.values()])
      .onConflictDoUpdate({
        target: albums.id,
        set: {
          name: sql`excluded.name`,
          imageUrl: sql`coalesce(excluded.image_url, ${albums.imageUrl})`,
        },
      });
  }

  if (trackRows.size) {
    await db
      .insert(tracks)
      .values([...trackRows.values()])
      .onConflictDoUpdate({
        target: tracks.id,
        set: {
          name: sql`excluded.name`,
          durationMs: sql`excluded.duration_ms`,
          popularity: sql`coalesce(excluded.popularity, ${tracks.popularity})`,
        },
      });
  }

  if (trackArtistRows.length) {
    await db.insert(trackArtists).values(dedupe(trackArtistRows, (r) => `${r.trackId}:${r.artistId}`)).onConflictDoNothing();
  }
  if (albumArtistRows.length) {
    await db.insert(albumArtists).values(dedupe(albumArtistRows, (r) => `${r.albumId}:${r.artistId}`)).onConflictDoNothing();
  }
}

/**
 * Completa gli artisti inseriti "a metà" con generi, immagine e popolarità.
 * I generi delle statistiche vengono da qui: Spotify non espone un genere per
 * traccia, solo per artista.
 */
export async function enrichPendingArtists(limit = 200): Promise<number> {
  // Due categorie insieme:
  //  - mai arricchiti (popularity ancora NULL): arrivano da `recently-played`,
  //    che manda solo id e nome, quindi non hanno né foto né generi;
  //  - arricchiti ma rimasti senza foto o senza generi da più di una settimana.
  //
  // La seconda esiste perché un errore momentaneo delle credenziali
  // applicative lasciava l'artista con `popularity = 0` e i campi vuoti, e con
  // il solo controllo su NULL non veniva più ritentato: restava senza foto per
  // sempre. `fetched_at` viene riscritto a ogni tentativo, quindi chi davvero
  // non ha immagini su Spotify viene ricontrollato al massimo una volta a
  // settimana.
  const pending = await db
    .select({ id: artists.id })
    .from(artists)
    .where(
      sql`${artists.popularity} is null
          or ((cardinality(${artists.genres}) = 0 or ${artists.imageUrl} is null)
              and ${artists.fetchedAt} < now() - interval '7 days')`,
    )
    .limit(limit);

  if (pending.length === 0) return 0;

  const token = await getAppAccessToken();
  const full = await getArtistsByIds(token, pending.map((a) => a.id));

  if (full.length) {
    await db
      .insert(artists)
      .values(full.map(toArtistRow))
      .onConflictDoUpdate({
        target: artists.id,
        set: {
          name: sql`excluded.name`,
          imageUrl: sql`excluded.image_url`,
          genres: sql`excluded.genres`,
          popularity: sql`excluded.popularity`,
          followers: sql`excluded.followers`,
          fetchedAt: sql`excluded.fetched_at`,
        },
      });
  }

  // Spotify non restituisce nulla per gli artisti spariti dal catalogo. Senza
  // marcarli come tentati resterebbero in cima alla coda per sempre: ogni
  // tornata successiva ripescherebbe gli stessi, e nessun altro artista
  // verrebbe più completato.
  const returned = new Set(full.map((a) => a.id));
  const unresolved = pending.filter((a) => !returned.has(a.id)).map((a) => a.id);

  if (unresolved.length) {
    await db
      .update(artists)
      .set({ popularity: sql`coalesce(${artists.popularity}, 0)`, fetchedAt: new Date() })
      .where(inArray(artists.id, unresolved));
  }

  // Il totale è "quanti artisti sono usciti dalla coda", non "quanti sono
  // stati arricchiti": chi cicla su questa funzione deve poter capire quando
  // non resta più nulla da fare.
  return full.length + unresolved.length;
}

export function toArtistRow(a: SpotifyArtist): typeof artists.$inferInsert {
  return {
    id: a.id,
    name: a.name,
    imageUrl: pickImage(a.images),
    genres: a.genres ?? [],
    // 0 invece di null: la popolarità è nota anche quando vale zero, e serve a
    // distinguere "arricchito" da "da arricchire".
    popularity: a.popularity ?? 0,
    followers: a.followers?.total ?? null,
    fetchedAt: new Date(),
  };
}

function dedupe<T>(rows: T[], key: (row: T) => string): T[] {
  const seen = new Map<string, T>();
  for (const row of rows) seen.set(key(row), row);
  return [...seen.values()];
}
