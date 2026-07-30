export interface SpotifyImage {
  url: string;
  height: number | null;
  width: number | null;
}

export interface SpotifySimpleArtist {
  id: string;
  name: string;
  uri: string;
}

export interface SpotifyArtist extends SpotifySimpleArtist {
  images?: SpotifyImage[];
  genres?: string[];
  popularity?: number;
  followers?: { total: number };
}

export interface SpotifyAlbum {
  id: string;
  name: string;
  images?: SpotifyImage[];
  release_date?: string;
  release_date_precision?: string;
  total_tracks?: number;
  album_type?: string;
  artists?: SpotifySimpleArtist[];
}

export interface SpotifyTrack {
  id: string | null;
  name: string;
  duration_ms: number;
  explicit: boolean;
  popularity?: number;
  external_ids?: { isrc?: string };
  album?: SpotifyAlbum;
  artists: SpotifySimpleArtist[];
  /** true per le tracce locali dell'utente: non hanno id e vanno scartate. */
  is_local?: boolean;
}

export interface SpotifyPlayHistoryItem {
  track: SpotifyTrack;
  /** ISO 8601. È il momento in cui la traccia è FINITA, non in cui è iniziata. */
  played_at: string;
  context: { type: string; uri: string } | null;
}

export interface SpotifyRecentlyPlayed {
  items: SpotifyPlayHistoryItem[];
  next: string | null;
  cursors: { after?: string; before?: string } | null;
  limit: number;
}

export interface SpotifyUserProfile {
  id: string;
  display_name: string | null;
  email?: string;
  country?: string;
  product?: string;
  images?: SpotifyImage[];
}

export interface SpotifyCurrentlyPlaying {
  is_playing: boolean;
  progress_ms: number | null;
  item: SpotifyTrack | null;
  context: { type: string; uri: string } | null;
  currently_playing_type: string;
}

export interface SpotifyTokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  refresh_token?: string;
  scope?: string;
}

export interface SpotifyPaged<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
  next: string | null;
}
