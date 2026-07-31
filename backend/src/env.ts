import 'dotenv/config';
import { z } from 'zod';

const schema = z.object({
  DATABASE_URL: z.string().min(1, 'DATABASE_URL mancante'),
  SPOTIFY_CLIENT_ID: z.string().min(1),
  SPOTIFY_CLIENT_SECRET: z.string().min(1),
  SPOTIFY_REDIRECT_URI: z.string().url(),
  PORT: z.coerce.number().int().positive().default(8787),
  APP_BASE_URL: z.string().url(),
  APP_DEEP_LINK: z.string().min(1).default('spotifystats://auth'),
  JWT_SECRET: z.string().min(32, 'JWT_SECRET troppo corto, usa `npm run keys`'),
  TOKEN_ENC_KEY: z
    .string()
    .refine((v) => Buffer.from(v, 'base64').length === 32, 'TOKEN_ENC_KEY deve essere 32 byte in base64'),
  CRON_SECRET: z.string().min(16, 'CRON_SECRET troppo corto, usa `npm run keys`'),

  // Su un server sempre acceso il polling può girare dentro il processo, senza
  // dipendere da un cron esterno. Sugli host che sospendono il servizio dopo
  // qualche minuto di inattività va invece lasciato disattivato: il timer
  // morirebbe insieme al processo e l'archivio si riempirebbe di buchi.
  ENABLE_INTERNAL_CRON: z
    .string()
    .optional()
    .transform((v) => v === 'true' || v === '1'),
  POLL_INTERVAL_MINUTES: z.coerce.number().int().min(1).max(120).default(15),

  // Le migrazioni girano all'avvio. Da disattivare solo se si hanno più
  // istanze del backend: partirebbero insieme sullo stesso schema.
  RUN_MIGRATIONS: z
    .string()
    .optional()
    .transform((v) => v !== 'false' && v !== '0'),
});

const parsed = schema.safeParse(process.env);

if (!parsed.success) {
  const issues = parsed.error.issues.map((i) => `  - ${i.path.join('.')}: ${i.message}`).join('\n');
  console.error(`Configurazione non valida:\n${issues}\n\nCopia .env.example in .env e compilalo.`);
  process.exit(1);
}

export const env = parsed.data;
