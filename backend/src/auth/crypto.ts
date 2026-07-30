import { webcrypto } from 'node:crypto';
import { env } from '../env.js';

const KEY_BYTES = Buffer.from(env.TOKEN_ENC_KEY, 'base64');
const IV_LENGTH = 12; // GCM standard

// `CryptoKey` non è un tipo globale in Node senza la lib DOM: lo ricaviamo
// dalla firma di importKey invece di trascinarci dentro tutto il DOM.
type CryptoKeyType = Awaited<ReturnType<typeof webcrypto.subtle.importKey>>;

let cachedKey: CryptoKeyType | null = null;

async function getKey(): Promise<CryptoKeyType> {
  if (!cachedKey) {
    cachedKey = await webcrypto.subtle.importKey('raw', KEY_BYTES, { name: 'AES-GCM' }, false, [
      'encrypt',
      'decrypt',
    ]);
  }
  return cachedKey;
}

/**
 * Cifra un token Spotify per salvarlo a riposo.
 * Formato: base64(iv | ciphertext+tag). L'IV è casuale a ogni chiamata.
 */
export async function encryptToken(plaintext: string): Promise<string> {
  const key = await getKey();
  const iv = webcrypto.getRandomValues(new Uint8Array(IV_LENGTH));
  const ciphertext = await webcrypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    new TextEncoder().encode(plaintext),
  );
  return Buffer.concat([Buffer.from(iv), Buffer.from(ciphertext)]).toString('base64');
}

export async function decryptToken(encoded: string): Promise<string> {
  const key = await getKey();
  const raw = Buffer.from(encoded, 'base64');
  const iv = raw.subarray(0, IV_LENGTH);
  const ciphertext = raw.subarray(IV_LENGTH);
  const plaintext = await webcrypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ciphertext);
  return new TextDecoder().decode(plaintext);
}
