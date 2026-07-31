import { sql } from 'drizzle-orm';
import {
  bigserial,
  boolean,
  index,
  integer,
  jsonb,
  pgTable,
  primaryKey,
  smallint,
  text,
  timestamp,
  uniqueIndex,
  uuid,
} from 'drizzle-orm/pg-core';

const tz = { withTimezone: true } as const;

/**
 * Un utente dell'app. `trackingSince` è il momento esatto del collegamento
 * dell'account: tutte le statistiche "vere" partono da lì.
 */
export const users = pgTable('users', {
  id: uuid('id').primaryKey().defaultRandom(),
  spotifyUserId: text('spotify_user_id').notNull().unique(),
  displayName: text('display_name'),
  email: text('email'),
  country: text('country'),
  imageUrl: text('image_url'),
  product: text('product'),
  trackingSince: timestamp('tracking_since', tz).notNull().defaultNow(),
  /** 'calendar' = settimana lun-dom / mese solare / anno solare.
   *  'anniversary' = periodi ancorati al giorno di registrazione. */
  periodMode: text('period_mode').notNull().default('calendar'),
  /** IANA tz, usata per raggruppare gli ascolti per giorno locale. */
  timezone: text('timezone').notNull().default('Europe/Rome'),
  /**
   * Ora a cui comincia la "giornata" nei recap giornalieri, 0-23.
   * Chi ascolta musica fino alle due di notte vuole che quegli ascolti
   * finiscano nel riepilogo della sera prima, non in quello del giorno dopo.
   */
  dailyRecapHour: integer('daily_recap_hour').notNull().default(0),
  createdAt: timestamp('created_at', tz).notNull().defaultNow(),
  updatedAt: timestamp('updated_at', tz).notNull().defaultNow(),
});

/**
 * Token Spotify, cifrati a riposo con AES-256-GCM (vedi auth/crypto.ts).
 * Non lasciano mai il server: l'app Android riceve solo un JWT applicativo.
 */
export const spotifyCredentials = pgTable('spotify_credentials', {
  userId: uuid('user_id')
    .primaryKey()
    .references(() => users.id, { onDelete: 'cascade' }),
  accessTokenEnc: text('access_token_enc').notNull(),
  refreshTokenEnc: text('refresh_token_enc').notNull(),
  expiresAt: timestamp('expires_at', tz).notNull(),
  scopes: text('scopes').array().notNull().default(sql`'{}'::text[]`),
  /** Valorizzato quando il refresh token viene revocato: il poller salta l'utente. */
  invalidatedAt: timestamp('invalidated_at', tz),
  invalidReason: text('invalid_reason'),
  updatedAt: timestamp('updated_at', tz).notNull().defaultNow(),
});

// --- Catalogo Spotify (condiviso fra tutti gli utenti) --------------------

export const artists = pgTable('artists', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  imageUrl: text('image_url'),
  genres: text('genres').array().notNull().default(sql`'{}'::text[]`),
  popularity: integer('popularity'),
  followers: integer('followers'),
  fetchedAt: timestamp('fetched_at', tz).notNull().defaultNow(),
});

export const albums = pgTable('albums', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  imageUrl: text('image_url'),
  releaseDate: text('release_date'),
  releaseDatePrecision: text('release_date_precision'),
  totalTracks: integer('total_tracks'),
  albumType: text('album_type'),
  fetchedAt: timestamp('fetched_at', tz).notNull().defaultNow(),
});

export const tracks = pgTable('tracks', {
  id: text('id').primaryKey(),
  name: text('name').notNull(),
  albumId: text('album_id').references(() => albums.id),
  durationMs: integer('duration_ms').notNull(),
  explicit: boolean('explicit').notNull().default(false),
  isrc: text('isrc'),
  popularity: integer('popularity'),
  fetchedAt: timestamp('fetched_at', tz).notNull().defaultNow(),
});

export const trackArtists = pgTable(
  'track_artists',
  {
    trackId: text('track_id')
      .notNull()
      .references(() => tracks.id, { onDelete: 'cascade' }),
    artistId: text('artist_id')
      .notNull()
      .references(() => artists.id, { onDelete: 'cascade' }),
    position: smallint('position').notNull().default(0),
  },
  (t) => [
    primaryKey({ columns: [t.trackId, t.artistId] }),
    index('track_artists_artist_idx').on(t.artistId),
  ],
);

export const albumArtists = pgTable(
  'album_artists',
  {
    albumId: text('album_id')
      .notNull()
      .references(() => albums.id, { onDelete: 'cascade' }),
    artistId: text('artist_id')
      .notNull()
      .references(() => artists.id, { onDelete: 'cascade' }),
    position: smallint('position').notNull().default(0),
  },
  (t) => [primaryKey({ columns: [t.albumId, t.artistId] })],
);

// --- L'archivio ----------------------------------------------------------

/**
 * Tabella append-only: un ascolto = una riga, per sempre.
 *
 * `playedAt` è il timestamp di FINE traccia riportato da Spotify. Il vincolo
 * unico (user, track, playedAt) è ciò che rende il poller idempotente: girare
 * due volte sulla stessa finestra non duplica nulla.
 */
export const plays = pgTable(
  'plays',
  {
    id: bigserial('id', { mode: 'number' }).primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    trackId: text('track_id')
      .notNull()
      .references(() => tracks.id),
    playedAt: timestamp('played_at', tz).notNull(),
    /** Millisecondi realmente ascoltati. Da `recently-played` non è esposto:
     *  vale `durationMs` come stima. Il campionatore now-playing lo corregge. */
    msPlayed: integer('ms_played').notNull(),
    /** true se msPlayed è una stima e non una misura. */
    msEstimated: boolean('ms_estimated').notNull().default(true),
    contextType: text('context_type'),
    contextUri: text('context_uri'),
    /** 'recently_played' | 'now_playing' | 'import' */
    source: text('source').notNull().default('recently_played'),
    createdAt: timestamp('created_at', tz).notNull().defaultNow(),
  },
  (t) => [
    uniqueIndex('plays_dedup_idx').on(t.userId, t.trackId, t.playedAt),
    index('plays_user_time_idx').on(t.userId, t.playedAt.desc()),
    index('plays_user_track_idx').on(t.userId, t.trackId),
  ],
);

/**
 * Snapshot di /me/top/* preso al primo collegamento, per non mostrare un'app
 * vuota il giorno zero. Marcato come pre-tracking: NON entra nelle statistiche.
 */
export const topSnapshots = pgTable(
  'top_snapshots',
  {
    id: bigserial('id', { mode: 'number' }).primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    /** 'tracks' | 'artists' */
    kind: text('kind').notNull(),
    /** 'short_term' | 'medium_term' | 'long_term' */
    timeRange: text('time_range').notNull(),
    capturedAt: timestamp('captured_at', tz).notNull().defaultNow(),
    payload: jsonb('payload').notNull(),
  },
  (t) => [index('top_snapshots_user_idx').on(t.userId, t.capturedAt.desc())],
);

// Nessuna tabella di rollup: con l'ordine di grandezza previsto (25 utenti,
// ~50k ascolti l'anno a testa) gli indici su `plays` bastano e la fonte di
// verità resta una sola. Se le query diventassero lente, il posto giusto per
// una cache aggregata è qui.

// --- Osservabilità e job -------------------------------------------------

/**
 * Una riga per esecuzione del poller. `hitPageLimit` = Spotify ha restituito
 * 50 item, la finestra era piena e con ogni probabilità si è perso qualcosa:
 * lo mostriamo all'utente invece di fingere completezza.
 */
export const pollRuns = pgTable(
  'poll_runs',
  {
    id: bigserial('id', { mode: 'number' }).primaryKey(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    startedAt: timestamp('started_at', tz).notNull().defaultNow(),
    finishedAt: timestamp('finished_at', tz),
    itemsFetched: integer('items_fetched').notNull().default(0),
    itemsInserted: integer('items_inserted').notNull().default(0),
    hitPageLimit: boolean('hit_page_limit').notNull().default(false),
    /** 'ok' | 'error' | 'skipped' */
    status: text('status').notNull().default('ok'),
    error: text('error'),
  },
  (t) => [index('poll_runs_user_idx').on(t.userId, t.startedAt.desc())],
);

export const importJobs = pgTable(
  'import_jobs',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    userId: uuid('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    filename: text('filename').notNull(),
    /** 'pending' | 'running' | 'done' | 'error' */
    status: text('status').notNull().default('pending'),
    rowsTotal: integer('rows_total').notNull().default(0),
    rowsImported: integer('rows_imported').notNull().default(0),
    rowsSkipped: integer('rows_skipped').notNull().default(0),
    error: text('error'),
    createdAt: timestamp('created_at', tz).notNull().defaultNow(),
    finishedAt: timestamp('finished_at', tz),
  },
  (t) => [index('import_jobs_user_idx').on(t.userId, t.createdAt.desc())],
);

/** State + PKCE verifier dell'OAuth in corso. Righe a vita breve. */
export const oauthStates = pgTable('oauth_states', {
  state: text('state').primaryKey(),
  codeVerifier: text('code_verifier').notNull(),
  createdAt: timestamp('created_at', tz).notNull().defaultNow(),
  expiresAt: timestamp('expires_at', tz).notNull(),
});

export type User = typeof users.$inferSelect;
export type NewPlay = typeof plays.$inferInsert;
export type Track = typeof tracks.$inferSelect;
export type Artist = typeof artists.$inferSelect;
