/**
 * Calcolo delle finestre temporali per i recap.
 *
 * È la logica più facile da sbagliare di tutto il progetto: fusi orari, ora
 * legale, settimane ISO, mesi di lunghezza diversa, anni bisestili. Per questo
 * sta in un file isolato e senza dipendenze dal database, ed è coperta da test.
 *
 * Due modalità, scelte dall'utente in Impostazioni:
 *  - `calendar`    settimana lunedì-domenica, mese solare, anno solare.
 *                  Il primo periodo è marcato `partial` perché il tracking è
 *                  iniziato a metà.
 *  - `anniversary` periodi ancorati al giorno di collegamento dell'account:
 *                  blocchi di 7 giorni, mesi e anni che scattano in quel giorno.
 */

export type PeriodType = 'day' | 'week' | 'month' | 'year';
export type PeriodMode = 'calendar' | 'anniversary';

export interface Period {
  type: PeriodType;
  /** Istante di inizio, incluso. */
  start: Date;
  /** Istante di fine, escluso. */
  end: Date;
  /** Identificatore stabile usato nelle URL: 2026-W31, 2026-07, 2026. */
  key: string;
  label: string;
  /** true se il tracking è iniziato dopo l'inizio del periodo: i dati coprono
   *  solo una parte della finestra e il confronto con gli altri è sleale. */
  partial: boolean;
}

// --- Utilità fuso orario -------------------------------------------------

interface Parts {
  year: number;
  month: number; // 1-12
  day: number;
  hour: number;
  minute: number;
  second: number;
}

const FORMATTER_CACHE = new Map<string, Intl.DateTimeFormat>();

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = FORMATTER_CACHE.get(timeZone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    FORMATTER_CACHE.set(timeZone, f);
  }
  return f;
}

/** Scompone un istante nei suoi componenti nel fuso indicato. */
export function zonedParts(date: Date, timeZone: string): Parts {
  const map: Record<string, string> = {};
  for (const p of formatter(timeZone).formatToParts(date)) {
    if (p.type !== 'literal') map[p.type] = p.value;
  }
  return {
    year: Number(map.year),
    month: Number(map.month),
    day: Number(map.day),
    hour: Number(map.hour),
    minute: Number(map.minute),
    second: Number(map.second),
  };
}

function offsetMs(date: Date, timeZone: string): number {
  const p = zonedParts(date, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - date.getTime();
}

/**
 * Converte una data/ora locale in istante UTC.
 * Doppio passaggio: il primo offset è calcolato su una stima che può cadere dal
 * lato sbagliato di un cambio di ora legale, il secondo lo corregge.
 */
export function zonedToUtc(
  timeZone: string,
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0,
  second = 0,
): Date {
  const guess = Date.UTC(year, month - 1, day, hour, minute, second);
  const firstOffset = offsetMs(new Date(guess), timeZone);
  const corrected = guess - firstOffset;
  const secondOffset = offsetMs(new Date(corrected), timeZone);
  return new Date(guess - secondOffset);
}

/** Mezzanotte locale del giorno che contiene `date`. */
export function startOfLocalDay(date: Date, timeZone: string): Date {
  const p = zonedParts(date, timeZone);
  return zonedToUtc(timeZone, p.year, p.month, p.day);
}

export function addLocalDays(date: Date, days: number, timeZone: string): Date {
  const p = zonedParts(date, timeZone);
  return zonedToUtc(timeZone, p.year, p.month, p.day + days, p.hour, p.minute, p.second);
}

function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

/** Giorno della settimana con lunedì = 0, come vuole lo standard ISO. */
function isoWeekday(date: Date, timeZone: string): number {
  const p = zonedParts(date, timeZone);
  const utc = new Date(Date.UTC(p.year, p.month - 1, p.day));
  return (utc.getUTCDay() + 6) % 7;
}

// --- Etichette in italiano ----------------------------------------------

const MONTHS = [
  'gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno',
  'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre',
];

const pad = (n: number) => String(n).padStart(2, '0');

/** Numero di settimana ISO 8601 e anno ISO corrispondente. */
function isoWeekNumber(date: Date, timeZone: string): { year: number; week: number } {
  const p = zonedParts(date, timeZone);
  // Il giovedì della settimana determina a quale anno ISO appartiene.
  const thursday = new Date(Date.UTC(p.year, p.month - 1, p.day));
  thursday.setUTCDate(thursday.getUTCDate() + 3 - ((thursday.getUTCDay() + 6) % 7));
  const isoYear = thursday.getUTCFullYear();
  const firstThursday = new Date(Date.UTC(isoYear, 0, 4));
  firstThursday.setUTCDate(firstThursday.getUTCDate() + 3 - ((firstThursday.getUTCDay() + 6) % 7));
  const week = 1 + Math.round((thursday.getTime() - firstThursday.getTime()) / (7 * 86400000));
  return { year: isoYear, week };
}

function rangeLabel(start: Date, endExclusive: Date, timeZone: string): string {
  const a = zonedParts(start, timeZone);
  const b = zonedParts(new Date(endExclusive.getTime() - 1), timeZone);
  if (a.year === b.year && a.month === b.month) {
    return `${a.day}–${b.day} ${MONTHS[a.month - 1]} ${a.year}`;
  }
  if (a.year === b.year) {
    return `${a.day} ${MONTHS[a.month - 1]} – ${b.day} ${MONTHS[b.month - 1]} ${a.year}`;
  }
  return `${a.day} ${MONTHS[a.month - 1]} ${a.year} – ${b.day} ${MONTHS[b.month - 1]} ${b.year}`;
}

// --- Costruzione dei periodi --------------------------------------------

export interface PeriodContext {
  mode: PeriodMode;
  timeZone: string;
  trackingSince: Date;
  /**
   * Ora a cui comincia la giornata nei recap giornalieri, 0-23.
   * Con 4, gli ascolti delle due di notte finiscono nel riepilogo della sera
   * precedente, che è dove chi li ha fatti se li aspetta.
   */
  dayStartHour?: number;
}

/** Inizio del periodo di tipo `type` che contiene `instant`. */
function periodStart(instant: Date, type: PeriodType, ctx: PeriodContext): Date {
  const { timeZone, mode, trackingSince } = ctx;

  // Un giorno è un giorno in entrambe le modalità: non ha senso ancorarlo
  // all'ora del collegamento, nessuno ragiona in giornate che iniziano alle
  // 14:37. L'ora di inizio è però configurabile dall'utente.
  if (type === 'day') {
    const hour = ctx.dayStartHour ?? 0;
    const p = zonedParts(instant, timeZone);
    const startOfToday = zonedToUtc(timeZone, p.year, p.month, p.day, hour);
    // Prima dell'ora di taglio si appartiene ancora alla giornata precedente.
    return p.hour < hour ? addLocalDays(startOfToday, -1, timeZone) : startOfToday;
  }

  if (mode === 'anniversary') {
    const anchor = zonedParts(trackingSince, timeZone);

    if (type === 'week') {
      // Blocchi di 7 giorni a partire dal giorno di collegamento.
      const anchorDay = zonedToUtc(timeZone, anchor.year, anchor.month, anchor.day);
      const dayMs = 86400000;
      const elapsed = Math.floor((startOfLocalDay(instant, timeZone).getTime() - anchorDay.getTime()) / dayMs);
      const blocks = Math.floor(elapsed / 7);
      return addLocalDays(anchorDay, blocks * 7, timeZone);
    }

    const p = zonedParts(instant, timeZone);
    if (type === 'month') {
      // Il mese scatta nello stesso giorno del collegamento. Se quel giorno non
      // esiste nel mese corrente (il 31 a febbraio) si usa l'ultimo disponibile.
      let year = p.year;
      let month = p.month;
      if (p.day < Math.min(anchor.day, daysInMonth(p.year, p.month))) {
        month -= 1;
        if (month === 0) {
          month = 12;
          year -= 1;
        }
      }
      return zonedToUtc(timeZone, year, month, Math.min(anchor.day, daysInMonth(year, month)));
    }

    // Anno: scatta nel giorno e mese del collegamento.
    const beforeAnniversary =
      p.month < anchor.month || (p.month === anchor.month && p.day < anchor.day);
    const year = beforeAnniversary ? p.year - 1 : p.year;
    return zonedToUtc(timeZone, year, anchor.month, Math.min(anchor.day, daysInMonth(year, anchor.month)));
  }

  // Modalità calendario.
  const p = zonedParts(instant, timeZone);
  if (type === 'week') {
    return addLocalDays(startOfLocalDay(instant, timeZone), -isoWeekday(instant, timeZone), timeZone);
  }
  if (type === 'month') return zonedToUtc(timeZone, p.year, p.month, 1);
  return zonedToUtc(timeZone, p.year, 1, 1);
}

function periodEnd(start: Date, type: PeriodType, ctx: PeriodContext): Date {
  const { timeZone, mode, trackingSince } = ctx;

  if (type === 'day') return addLocalDays(start, 1, timeZone);
  if (type === 'week') return addLocalDays(start, 7, timeZone);

  const p = zonedParts(start, timeZone);

  if (mode === 'anniversary') {
    const anchorDay = zonedParts(trackingSince, timeZone).day;
    if (type === 'month') {
      const year = p.month === 12 ? p.year + 1 : p.year;
      const month = p.month === 12 ? 1 : p.month + 1;
      return zonedToUtc(timeZone, year, month, Math.min(anchorDay, daysInMonth(year, month)));
    }
    return zonedToUtc(timeZone, p.year + 1, p.month, Math.min(anchorDay, daysInMonth(p.year + 1, p.month)));
  }

  if (type === 'month') {
    return p.month === 12
      ? zonedToUtc(timeZone, p.year + 1, 1, 1)
      : zonedToUtc(timeZone, p.year, p.month + 1, 1);
  }
  return zonedToUtc(timeZone, p.year + 1, 1, 1);
}

function buildPeriod(start: Date, type: PeriodType, ctx: PeriodContext): Period {
  const end = periodEnd(start, type, ctx);
  const p = zonedParts(start, ctx.timeZone);
  const partial = ctx.trackingSince.getTime() > start.getTime();

  let key: string;
  let label: string;

  if (type === 'day') {
    key = `${p.year}-${pad(p.month)}-${pad(p.day)}`;
    label = `${p.day} ${MONTHS[p.month - 1]} ${p.year}`;
  } else if (type === 'week') {
    if (ctx.mode === 'calendar') {
      const { year, week } = isoWeekNumber(start, ctx.timeZone);
      key = `${year}-W${pad(week)}`;
    } else {
      key = `${p.year}-${pad(p.month)}-${pad(p.day)}`;
    }
    label = rangeLabel(start, end, ctx.timeZone);
  } else if (type === 'month') {
    key = ctx.mode === 'calendar' ? `${p.year}-${pad(p.month)}` : `${p.year}-${pad(p.month)}-${pad(p.day)}`;
    label =
      ctx.mode === 'calendar'
        ? `${MONTHS[p.month - 1]} ${p.year}`
        : rangeLabel(start, end, ctx.timeZone);
  } else {
    key = ctx.mode === 'calendar' ? String(p.year) : `${p.year}-${pad(p.month)}-${pad(p.day)}`;
    label = ctx.mode === 'calendar' ? String(p.year) : rangeLabel(start, end, ctx.timeZone);
  }

  return { type, start, end, key, label, partial };
}

/** Il periodo di tipo `type` che contiene `instant` (default: adesso). */
export function periodContaining(type: PeriodType, ctx: PeriodContext, instant = new Date()): Period {
  return buildPeriod(periodStart(instant, type, ctx), type, ctx);
}

/**
 * Tutti i periodi conclusi dal collegamento a oggi, dal più recente al più
 * vecchio. Il periodo in corso è escluso: un recap ha senso solo su una
 * finestra chiusa.
 */
export function listCompletedPeriods(
  type: PeriodType,
  ctx: PeriodContext,
  now = new Date(),
  limit = 60,
): Period[] {
  const out: Period[] = [];
  let cursor = periodStart(ctx.trackingSince, type, ctx);
  const currentStart = periodStart(now, type, ctx).getTime();

  // Limite di sicurezza: impedisce un ciclo infinito se il calcolo del periodo
  // successivo non avanzasse per un bug sui confini.
  for (let guard = 0; guard < 5000; guard++) {
    if (cursor.getTime() >= currentStart) break;
    const period = buildPeriod(cursor, type, ctx);
    out.push(period);
    if (period.end.getTime() <= cursor.getTime()) break;
    cursor = period.end;
  }

  return out.reverse().slice(0, limit);
}

/** Ricostruisce un periodo dalla sua `key`, per servire le URL dei recap. */
export function periodFromKey(type: PeriodType, key: string, ctx: PeriodContext): Period | null {
  const period = listCompletedPeriods(type, ctx, new Date(), Number.MAX_SAFE_INTEGER).find(
    (p) => p.key === key,
  );
  if (period) return period;

  const current = periodContaining(type, ctx);
  return current.key === key ? current : null;
}

/** Finestre relative usate dai filtri rapidi della schermata Top. */
export function presetRange(
  preset: string,
  ctx: PeriodContext,
  now = new Date(),
): { from: Date; to: Date } {
  const to = now;
  switch (preset) {
    case '4weeks':
      return { from: addLocalDays(now, -28, ctx.timeZone), to };
    case '6months':
      return { from: addLocalDays(now, -182, ctx.timeZone), to };
    case 'year':
      return { from: addLocalDays(now, -365, ctx.timeZone), to };
    case 'week':
      return { from: periodContaining('week', ctx, now).start, to };
    case 'month':
      return { from: periodContaining('month', ctx, now).start, to };
    case 'lifetime':
      // Comprende anche gli ascolti importati dall'archivio Spotify, che sono
      // precedenti al collegamento dell'account.
      return { from: new Date(0), to };
    case 'since_tracking':
    default:
      return { from: ctx.trackingSince, to };
  }
}
