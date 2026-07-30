import { db } from '../db/client.js';
import { topSnapshots } from '../db/schema.js';
import { getValidAccessToken } from '../auth/spotify.js';
import { enrichPendingArtists, toArtistRow, upsertTracksFromSpotify } from '../lib/catalog.js';
import { artists } from '../db/schema.js';
import { getTopItems } from '../spotify/client.js';
import type { SpotifyArtist, SpotifyTrack } from '../spotify/types.js';
import { pollUser } from './poll.js';
import { sql } from 'drizzle-orm';

const TIME_RANGES = ['short_term', 'medium_term', 'long_term'] as const;

/**
 * Eseguito una sola volta, al primo collegamento dell'account.
 *
 * Senza questo, l'app resterebbe vuota per giorni: l'archivio parte da adesso
 * e Spotify non restituisce nulla del passato. `/me/top/*` dà una fotografia
 * dei gusti attuali (ultime 4 settimane / 6 mesi / anni), che salviamo come
 * snapshot chiaramente marcato *pre-tracking* e tenuto FUORI dalle statistiche
 * calcolate su `plays`: mescolarli falserebbe ogni conteggio.
 */
export async function runBackfill(userId: string): Promise<void> {
  // Prima gli ultimi 50 ascolti reali: sono gli unici dati storici veri che
  // Spotify concede, e sono già archiviabili in `plays`.
  await pollUser(userId);

  const accessToken = await getValidAccessToken(userId);

  for (const timeRange of TIME_RANGES) {
    try {
      const [topTracks, topArtists] = await Promise.all([
        getTopItems<SpotifyTrack>(accessToken, 'tracks', timeRange),
        getTopItems<SpotifyArtist>(accessToken, 'artists', timeRange),
      ]);

      await upsertTracksFromSpotify(topTracks.items);

      // Gli artisti da /me/top/artists arrivano già completi di generi e
      // immagini: inserirli qui evita una tornata di arricchimento.
      const artistRows = topArtists.items.filter((a) => a.id).map(toArtistRow);
      if (artistRows.length) {
        await db
          .insert(artists)
          .values(artistRows)
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

      await db.insert(topSnapshots).values([
        {
          userId,
          kind: 'tracks',
          timeRange,
          payload: topTracks.items.map((t, i) => ({ rank: i + 1, id: t.id, name: t.name })),
        },
        {
          userId,
          kind: 'artists',
          timeRange,
          payload: topArtists.items.map((a, i) => ({ rank: i + 1, id: a.id, name: a.name })),
        },
      ]);
    } catch (err) {
      // Un time_range che fallisce non deve bloccare gli altri: un account
      // nuovo di zecca non ha dati `long_term` e Spotify può rispondere vuoto.
      console.error(`[backfill] ${timeRange} fallito per ${userId}`, err);
    }
  }

  await enrichPendingArtists();
}
