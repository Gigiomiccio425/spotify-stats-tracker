import { randomBytes } from 'node:crypto';

// Genera i tre segreti richiesti dal .env. Non stampa nulla di persistente:
// copiali a mano nel file.
console.log('Incolla queste righe nel tuo backend/.env\n');
console.log(`JWT_SECRET=${randomBytes(48).toString('base64url')}`);
console.log(`TOKEN_ENC_KEY=${randomBytes(32).toString('base64')}`);
console.log(`CRON_SECRET=${randomBytes(32).toString('base64url')}`);
console.log('\nTOKEN_ENC_KEY cifra i refresh token Spotify: se la perdi o la cambi,');
console.log('gli utenti collegati dovranno rifare il login.');
