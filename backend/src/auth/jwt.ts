import { SignJWT, jwtVerify } from 'jose';
import { env } from '../env.js';

const secret = new TextEncoder().encode(env.JWT_SECRET);
const ISSUER = 'spotify-stats';

export interface SessionClaims {
  sub: string; // users.id
  spotifyUserId: string;
}

/**
 * Sessione dell'app, non di Spotify. Lunga (90 giorni) perché il client è
 * un'app installata: i token Spotify restano comunque solo sul server.
 */
export async function signSession(claims: SessionClaims): Promise<string> {
  return new SignJWT({ spotifyUserId: claims.spotifyUserId })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(claims.sub)
    .setIssuer(ISSUER)
    .setIssuedAt()
    .setExpirationTime('90d')
    .sign(secret);
}

export async function verifySession(token: string): Promise<SessionClaims> {
  const { payload } = await jwtVerify(token, secret, { issuer: ISSUER });
  if (!payload.sub) throw new Error('token senza subject');
  return { sub: payload.sub, spotifyUserId: String(payload.spotifyUserId ?? '') };
}
