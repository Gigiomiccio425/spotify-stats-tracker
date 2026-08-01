import { createHash, randomBytes } from 'node:crypto';
import { and, eq, isNull, lt } from 'drizzle-orm';
import { db } from '../db/client.js';
import { oauthStates, spotifyCredentials } from '../db/schema.js';
import { env } from '../env.js';
import { SpotifyError } from '../spotify/client.js';
import type { SpotifyTokenResponse } from '../spotify/types.js';
import { decryptToken, encryptToken } from './crypto.js';

const AUTHORIZE_URL = 'https://accounts.spotify.com/authorize';
const TOKEN_URL = 'https://accounts.spotify.com/api/token';

/**
 * `user-read-recently-played` è l'unico scope indispensabile: gli altri
 * arricchiscono le statistiche ma l'archivio funziona anche senza.
 */
export const SCOPES = [
  'user-read-recently-played',
  'user-read-currently-playing',
  'user-read-playback-state',
  'user-top-read',
  'user-read-private',
  'user-read-email',
] as const;

const STATE_TTL_MS = 10 * 60 * 1000;

function base64url(buf: Buffer): string {
  return buf.toString('base64url');
}

/**
 * Authorization Code + PKCE. Lo scambio avviene comunque sul server, ma PKCE
 * protegge anche dal furto del `code` durante il redirect nel browser.
 */
export async function beginAuthorization(): Promise<{ url: string; state: string }> {
  const state = base64url(randomBytes(24));
  const codeVerifier = base64url(randomBytes(64));
  const codeChallenge = base64url(createHash('sha256').update(codeVerifier).digest());

  await db.delete(oauthStates).where(lt(oauthStates.expiresAt, new Date()));
  await db.insert(oauthStates).values({
    state,
    codeVerifier,
    expiresAt: new Date(Date.now() + STATE_TTL_MS),
  });

  const params = new URLSearchParams({
    client_id: env.SPOTIFY_CLIENT_ID,
    response_type: 'code',
    redirect_uri: env.SPOTIFY_REDIRECT_URI,
    scope: SCOPES.join(' '),
    state,
    code_challenge_method: 'S256',
    code_challenge: codeChallenge,
    // Forza la schermata di consenso: senza, un utente già collegato non può
    // cambiare account e resterebbe bloccato sul profilo sbagliato.
    show_dialog: 'true',
  });

  return { url: `${AUTHORIZE_URL}?${params}`, state };
}

export async function consumeState(state: string): Promise<string | null> {
  const rows = await db.select().from(oauthStates).where(eq(oauthStates.state, state)).limit(1);
  const row = rows[0];
  if (!row) return null;
  await db.delete(oauthStates).where(eq(oauthStates.state, state));
  if (row.expiresAt.getTime() < Date.now()) return null;
  return row.codeVerifier;
}

export async function exchangeCode(code: string, codeVerifier: string): Promise<SpotifyTokenResponse> {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: env.SPOTIFY_REDIRECT_URI,
    client_id: env.SPOTIFY_CLIENT_ID,
    code_verifier: codeVerifier,
  });

  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  if (!res.ok) {
    throw new Error(`Scambio del code fallito (${res.status}): ${await res.text()}`);
  }
  return res.json() as Promise<SpotifyTokenResponse>;
}

async function refreshAccessToken(refreshToken: string): Promise<SpotifyTokenResponse> {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    refresh_token: refreshToken,
    client_id: env.SPOTIFY_CLIENT_ID,
  });

  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });

  if (!res.ok) {
    const text = await res.text();
    const err = new Error(`Refresh fallito (${res.status}): ${text}`);
    // 400 con invalid_grant = l'utente ha revocato l'accesso dalle impostazioni
    // Spotify. Non ha senso riprovare: va disattivato il polling per lui.
    (err as Error & { permanent?: boolean }).permanent =
      res.status === 400 && text.includes('invalid_grant');
    throw err;
  }
  return res.json() as Promise<SpotifyTokenResponse>;
}

export async function saveCredentials(
  userId: string,
  tokens: SpotifyTokenResponse,
  previousRefreshToken?: string,
): Promise<void> {
  // Spotify non sempre restituisce un nuovo refresh token al refresh:
  // se manca, quello vecchio resta valido e va conservato.
  const refreshToken = tokens.refresh_token ?? previousRefreshToken;
  if (!refreshToken) throw new Error('Nessun refresh token disponibile');

  const values = {
    userId,
    accessTokenEnc: await encryptToken(tokens.access_token),
    refreshTokenEnc: await encryptToken(refreshToken),
    expiresAt: new Date(Date.now() + tokens.expires_in * 1000),
    scopes: tokens.scope ? tokens.scope.split(' ') : [...SCOPES],
    invalidatedAt: null,
    invalidReason: null,
    updatedAt: new Date(),
  };

  await db.insert(spotifyCredentials).values(values).onConflictDoUpdate({
    target: spotifyCredentials.userId,
    set: values,
  });
}

/** Margine sotto cui consideriamo il token già scaduto: evita corse sul filo. */
const EXPIRY_SKEW_MS = 60_000;

/**
 * Restituisce un access token valido, rinnovandolo se serve.
 * Lancia `TokenRevokedError` se l'utente ha revocato l'accesso: in quel caso
 * la riga viene marcata `invalidatedAt` e il poller smette di riprovare.
 */
export async function getValidAccessToken(userId: string): Promise<string> {
  const rows = await db
    .select()
    .from(spotifyCredentials)
    .where(and(eq(spotifyCredentials.userId, userId), isNull(spotifyCredentials.invalidatedAt)))
    .limit(1);

  const cred = rows[0];
  if (!cred) throw new TokenRevokedError('Nessuna credenziale attiva per questo utente');

  if (cred.expiresAt.getTime() - EXPIRY_SKEW_MS > Date.now()) {
    return decryptToken(cred.accessTokenEnc);
  }

  let refreshToken: string;
  try {
    refreshToken = await decryptToken(cred.refreshTokenEnc);
  } catch {
    // AES-GCM fallisce l'autenticazione se la chiave non è quella con cui il
    // token è stato cifrato. Succede quando TOKEN_ENC_KEY viene rigenerata
    // dopo il collegamento: senza questo ramo il refresh partirebbe con dati
    // illeggibili e Spotify risponderebbe `invalid_grant`, facendo credere a
    // una revoca da parte dell'utente.
    await db
      .update(spotifyCredentials)
      .set({
        invalidatedAt: new Date(),
        invalidReason: 'TOKEN_ENC_KEY cambiata dopo il collegamento: ricollega l account',
      })
      .where(eq(spotifyCredentials.userId, userId));
    throw new TokenRevokedError(
      'Credenziali non decifrabili: la chiave di cifratura è cambiata. Ricollega l account Spotify.',
    );
  }

  try {
    const tokens = await refreshAccessToken(refreshToken);
    await saveCredentials(userId, tokens, refreshToken);
    return tokens.access_token;
  } catch (err) {
    if ((err as { permanent?: boolean }).permanent) {
      await db
        .update(spotifyCredentials)
        .set({ invalidatedAt: new Date(), invalidReason: String((err as Error).message).slice(0, 500) })
        .where(eq(spotifyCredentials.userId, userId));
      throw new TokenRevokedError('Accesso revocato dall utente su Spotify');
    }
    throw err;
  }
}

export class TokenRevokedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TokenRevokedError';
  }
}

// --- Client credentials --------------------------------------------------

let appToken: { value: string; expiresAt: number } | null = null;

/**
 * Token applicativo (senza utente) per leggere il catalogo: tracce, album,
 * artisti. Usarlo al posto del token utente evita di consumare il rate limit
 * personale e continua a funzionare se quell'utente ha revocato l'accesso.
 */
export async function getAppAccessToken(): Promise<string> {
  if (appToken && appToken.expiresAt - EXPIRY_SKEW_MS > Date.now()) return appToken.value;

  const basic = Buffer.from(`${env.SPOTIFY_CLIENT_ID}:${env.SPOTIFY_CLIENT_SECRET}`).toString('base64');
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: `Basic ${basic}`,
    },
    body: new URLSearchParams({ grant_type: 'client_credentials' }),
  });

  if (!res.ok) throw new Error(`Client credentials fallito (${res.status}): ${await res.text()}`);

  const tokens = (await res.json()) as SpotifyTokenResponse;
  appToken = { value: tokens.access_token, expiresAt: Date.now() + tokens.expires_in * 1000 };
  return appToken.value;
}

/**
 * Un token utente qualsiasi, da usare quando quello applicativo non basta.
 *
 * Sul catalogo (tracce, album, artisti) i due leggono gli stessi identici dati:
 * quale account lo chieda non cambia la risposta.
 */
export async function getAnyUserAccessToken(): Promise<string> {
  const rows = await db
    .select({ userId: spotifyCredentials.userId })
    .from(spotifyCredentials)
    .where(isNull(spotifyCredentials.invalidatedAt));

  for (const row of rows) {
    try {
      return await getValidAccessToken(row.userId);
    } catch {
      // Credenziali di quell'utente inservibili: si prova il prossimo.
    }
  }

  throw new Error('Nessun account Spotify collegato con credenziali valide');
}

/**
 * Il token applicativo è stato rifiutato: per il resto della vita del processo
 * si va direttamente di token utente. Senza memoria, ogni chiamata pagherebbe
 * un 403 prima di riuscire.
 */
let appTokenRejected = false;

/**
 * Esegue una lettura del catalogo con il miglior token disponibile.
 *
 * Prima scelta il token applicativo: non consuma il rate limit di nessun
 * utente e continua a valere anche se chi ha importato l'archivio revoca
 * l'accesso. Ma Spotify può rifiutarlo con 403 su `/artists` e `/tracks` — è
 * successo davvero, durante il recupero degli artisti di un archivio
 * importato — e allora l'unica strada è il token di un utente collegato.
 *
 * Il ritentativo rifà `fn` da capo. Sono letture, ripeterle non fa danni.
 */
export async function withCatalogToken<T>(fn: (token: string) => Promise<T>): Promise<T> {
  if (!appTokenRejected) {
    try {
      return await fn(await getAppAccessToken());
    } catch (err) {
      if (!(err instanceof SpotifyError) || err.status !== 403) throw err;
      appTokenRejected = true;
      console.warn(
        '[spotify] token applicativo rifiutato con 403 sul catalogo: ' +
          'passo al token di un utente collegato',
      );
    }
  }

  return fn(await getAnyUserAccessToken());
}
