import type {
  SpotifyAlbum,
  SpotifyArtist,
  SpotifyCurrentlyPlaying,
  SpotifyPaged,
  SpotifyRecentlyPlayed,
  SpotifyTrack,
  SpotifyUserProfile,
} from './types.js';

const API = 'https://api.spotify.com/v1';

export class SpotifyError extends Error {
  constructor(
    override readonly message: string,
    readonly status: number,
    readonly body: string,
  ) {
    super(message);
    this.name = 'SpotifyError';
  }

  /** 401 = access token scaduto o revocato. Chi chiama deve rifare il refresh. */
  get isUnauthorized() {
    return this.status === 401;
  }

  /** 403 in Development Mode = l'utente non è nella lista dei 25 consentiti. */
  get isForbidden() {
    return this.status === 403;
  }
}

interface RequestOptions {
  /** Quante volte riprovare su 429 / 5xx. */
  retries?: number;
  signal?: AbortSignal;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Oltre questo `Retry-After` non si aspetta più: si segnala l'errore. */
const MAX_RETRY_AFTER_SECONDS = 60;

/**
 * Wrapper unico su tutte le chiamate all'API. Tenerle in un solo punto limita
 * i danni quando Spotify deprecherà il prossimo endpoint.
 */
export async function spotifyFetch<T>(
  path: string,
  accessToken: string,
  options: RequestOptions = {},
): Promise<T> {
  const { retries = 3, signal } = options;
  const url = path.startsWith('http') ? path : `${API}${path}`;

  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${accessToken}` },
      signal,
    });

    if (res.ok) {
      if (res.status === 204) return undefined as T;
      const text = await res.text();
      return (text ? JSON.parse(text) : undefined) as T;
    }

    const body = await res.text().catch(() => '');

    // Spotify indica quanti secondi aspettare. Rispettarlo evita di peggiorare
    // il throttling: ignorarlo allunga il ban.
    //
    // Sopra il tetto però si rinuncia invece di dormire: quando Spotify chiude
    // i rubinetti sul serio l'attesa richiesta è di ore, e restare fermi
    // dentro un `await` per tutto quel tempo bloccherebbe l'import in corso e
    // il poller, senza che nessuno possa accorgersene.
    if (res.status === 429 && attempt < retries) {
      const header = Number(res.headers.get('retry-after') ?? '1');
      const retryAfter = Number.isFinite(header) ? header : 1;
      if (retryAfter > MAX_RETRY_AFTER_SECONDS) {
        throw new SpotifyError(
          `Rate limit di Spotify: chiede di attendere ${retryAfter}s su ${path}`,
          429,
          body,
        );
      }
      await sleep(retryAfter * 1000 + 250);
      continue;
    }

    if (res.status >= 500 && attempt < retries) {
      await sleep(2 ** attempt * 500);
      continue;
    }

    throw new SpotifyError(`${res.status} su ${path}: ${body.slice(0, 300)}`, res.status, body);
  }
}

export function getRecentlyPlayed(
  accessToken: string,
  opts: { after?: number; before?: number; limit?: number } = {},
): Promise<SpotifyRecentlyPlayed> {
  const params = new URLSearchParams({ limit: String(opts.limit ?? 50) });
  // `after` e `before` sono mutuamente esclusivi: Spotify rifiuta entrambi.
  if (opts.after !== undefined) params.set('after', String(opts.after));
  else if (opts.before !== undefined) params.set('before', String(opts.before));
  return spotifyFetch(`/me/player/recently-played?${params}`, accessToken);
}

export function getCurrentlyPlaying(accessToken: string): Promise<SpotifyCurrentlyPlaying | undefined> {
  return spotifyFetch('/me/player/currently-playing?additional_types=track', accessToken);
}

export function getMe(accessToken: string): Promise<SpotifyUserProfile> {
  return spotifyFetch('/me', accessToken);
}

export function getTopItems<T extends SpotifyTrack | SpotifyArtist>(
  accessToken: string,
  kind: 'tracks' | 'artists',
  timeRange: 'short_term' | 'medium_term' | 'long_term',
  limit = 50,
): Promise<SpotifyPaged<T>> {
  return spotifyFetch(`/me/top/${kind}?time_range=${timeRange}&limit=${limit}`, accessToken);
}

/** Spotify accetta 50 id per chiamata: batchare qui riduce di 50x le richieste. */
export async function getTracksByIds(accessToken: string, ids: string[]): Promise<SpotifyTrack[]> {
  return batched(ids, 50, async (chunk) => {
    const res = await spotifyFetch<{ tracks: (SpotifyTrack | null)[] }>(
      `/tracks?ids=${chunk.join(',')}`,
      accessToken,
    );
    return res.tracks.filter((t): t is SpotifyTrack => t !== null);
  });
}

export async function getArtistsByIds(accessToken: string, ids: string[]): Promise<SpotifyArtist[]> {
  return batched(ids, 50, async (chunk) => {
    const res = await spotifyFetch<{ artists: (SpotifyArtist | null)[] }>(
      `/artists?ids=${chunk.join(',')}`,
      accessToken,
    );
    return res.artists.filter((a): a is SpotifyArtist => a !== null);
  });
}

export async function getAlbumsByIds(accessToken: string, ids: string[]): Promise<SpotifyAlbum[]> {
  return batched(ids, 20, async (chunk) => {
    const res = await spotifyFetch<{ albums: (SpotifyAlbum | null)[] }>(
      `/albums?ids=${chunk.join(',')}`,
      accessToken,
    );
    return res.albums.filter((a): a is SpotifyAlbum => a !== null);
  });
}

async function batched<TIn, TOut>(
  items: TIn[],
  size: number,
  fn: (chunk: TIn[]) => Promise<TOut[]>,
): Promise<TOut[]> {
  const out: TOut[] = [];
  for (let i = 0; i < items.length; i += size) {
    out.push(...(await fn(items.slice(i, i + size))));
  }
  return out;
}

/** Copertina più grande disponibile. */
export function pickImage(images?: { url: string; width: number | null }[]): string | null {
  if (!images?.length) return null;
  return [...images].sort((a, b) => (b.width ?? 0) - (a.width ?? 0))[0]?.url ?? null;
}
