import { describe, expect, it } from 'vitest';
import {
  listCompletedPeriods,
  periodContaining,
  presetRange,
  startOfLocalDay,
  zonedToUtc,
  type PeriodContext,
} from './periods.js';

const TZ = 'Europe/Rome';
const HOUR = 3600_000;

function ctx(trackingSince: string, mode: PeriodContext['mode'] = 'calendar'): PeriodContext {
  return { mode, timeZone: TZ, trackingSince: new Date(trackingSince) };
}

describe('zonedToUtc', () => {
  it('applica UTC+1 in inverno', () => {
    expect(zonedToUtc(TZ, 2026, 1, 15).toISOString()).toBe('2026-01-14T23:00:00.000Z');
  });

  it('applica UTC+2 in estate', () => {
    expect(zonedToUtc(TZ, 2026, 7, 15).toISOString()).toBe('2026-07-14T22:00:00.000Z');
  });

  it('normalizza i giorni fuori range', () => {
    // Il giorno 32 di gennaio è il 1 febbraio: serve per il clamp dei mesi.
    expect(zonedToUtc(TZ, 2026, 1, 32).toISOString()).toBe('2026-01-31T23:00:00.000Z');
  });
});

describe('startOfLocalDay', () => {
  it('torna la mezzanotte locale, non quella UTC', () => {
    // Le 00:30 UTC del 15 luglio sono già le 02:30 del 15 a Roma.
    const d = startOfLocalDay(new Date('2026-07-15T00:30:00Z'), TZ);
    expect(d.toISOString()).toBe('2026-07-14T22:00:00.000Z');
  });
});

describe('periodo giornaliero', () => {
  it('va da mezzanotte a mezzanotte locale', () => {
    const day = periodContaining('day', ctx('2026-01-01T00:00:00Z'), new Date('2026-07-15T10:00:00Z'));
    expect(day.start.toISOString()).toBe('2026-07-14T22:00:00.000Z');
    expect(day.end.toISOString()).toBe('2026-07-15T22:00:00.000Z');
    expect(day.key).toBe('2026-07-15');
    expect(day.label).toBe('15 luglio 2026');
  });

  it('resta un giorno anche in modalità anniversario', () => {
    // Ancorare la giornata all'ora del collegamento darebbe finestre che
    // iniziano alle 12:00: nessuno ragiona così.
    const c = ctx('2026-07-15T12:00:00Z', 'anniversary')
    const day = periodContaining('day', c, new Date('2026-07-20T08:00:00Z'));
    expect(day.start.toISOString()).toBe('2026-07-19T22:00:00.000Z');
    expect(day.end.toISOString()).toBe('2026-07-20T22:00:00.000Z');
  });

  it('con ora di inizio 4, le due di notte appartengono al giorno prima', () => {
    const c = { ...ctx('2026-01-01T00:00:00Z'), dayStartHour: 4 };

    // 02:30 italiane del 16 luglio: la giornata "del 15" non e' ancora finita.
    const notte = periodContaining('day', c, new Date('2026-07-16T00:30:00Z'));
    expect(notte.start.toISOString()).toBe('2026-07-15T02:00:00.000Z'); // 15 lug 04:00
    expect(notte.key).toBe('2026-07-15');

    // 10:00 italiane dello stesso giorno: siamo gia' nella giornata del 16.
    const mattina = periodContaining('day', c, new Date('2026-07-16T08:00:00Z'));
    expect(mattina.start.toISOString()).toBe('2026-07-16T02:00:00.000Z');
    expect(mattina.start.toISOString()).toBe(notte.end.toISOString());
  });

  it('dura 23 ore nel giorno in cui scatta l ora legale', () => {
    const day = periodContaining('day', ctx('2026-01-01T00:00:00Z'), new Date('2026-03-29T12:00:00Z'));
    expect((day.end.getTime() - day.start.getTime()) / HOUR).toBe(23);
  });
});

describe('periodi calendario', () => {
  const c = ctx('2026-01-01T00:00:00Z');

  it('la settimana parte di lunedì', () => {
    // 2026-07-15 è un mercoledì.
    const week = periodContaining('week', c, new Date('2026-07-15T10:00:00Z'));
    expect(week.start.toISOString()).toBe('2026-07-12T22:00:00.000Z'); // lun 13 lug, 00:00 CEST
    expect(week.end.toISOString()).toBe('2026-07-19T22:00:00.000Z');
    expect(week.key).toBe('2026-W29');
  });

  it('la settimana del cambio ora legale dura 167 ore, non 168', () => {
    // In Italia l'ora legale 2026 scatta domenica 29 marzo.
    const week = periodContaining('week', c, new Date('2026-03-25T12:00:00Z'));
    expect(week.start.toISOString()).toBe('2026-03-22T23:00:00.000Z');
    expect(week.end.toISOString()).toBe('2026-03-29T22:00:00.000Z');
    expect((week.end.getTime() - week.start.getTime()) / HOUR).toBe(167);
  });

  it('il mese va dal primo al primo', () => {
    const month = periodContaining('month', c, new Date('2026-02-15T12:00:00Z'));
    expect(month.start.toISOString()).toBe('2026-01-31T23:00:00.000Z');
    expect(month.end.toISOString()).toBe('2026-02-28T23:00:00.000Z');
    expect(month.key).toBe('2026-02');
    expect(month.label).toBe('febbraio 2026');
  });

  it('l anno va dal primo gennaio al primo gennaio', () => {
    const year = periodContaining('year', c, new Date('2026-08-01T12:00:00Z'));
    expect(year.start.toISOString()).toBe('2025-12-31T23:00:00.000Z');
    expect(year.end.toISOString()).toBe('2026-12-31T23:00:00.000Z');
    expect(year.key).toBe('2026');
  });

  it('marca parziale solo il periodo in cui è iniziato il tracking', () => {
    // Tracking iniziato mercoledì: quella settimana è coperta a metà.
    const c2 = ctx('2026-07-15T10:00:00Z');
    expect(periodContaining('week', c2, new Date('2026-07-16T10:00:00Z')).partial).toBe(true);
    expect(periodContaining('week', c2, new Date('2026-07-23T10:00:00Z')).partial).toBe(false);
  });
});

describe('periodi ad anniversario', () => {
  it('la settimana è un blocco di 7 giorni dal collegamento', () => {
    const c = ctx('2026-07-15T10:00:00Z', 'anniversary');

    const first = periodContaining('week', c, new Date('2026-07-20T10:00:00Z'));
    expect(first.start.toISOString()).toBe('2026-07-14T22:00:00.000Z'); // 15 lug 00:00 CEST

    const second = periodContaining('week', c, new Date('2026-07-22T10:00:00Z'));
    expect(second.start.toISOString()).toBe('2026-07-21T22:00:00.000Z');
    expect(second.start.toISOString()).toBe(first.end.toISOString());
  });

  it('il mese si aggancia al giorno 31 e ripiega sull ultimo giorno disponibile', () => {
    const c = ctx('2026-01-31T10:00:00Z', 'anniversary');

    const jan = periodContaining('month', c, new Date('2026-02-15T12:00:00Z'));
    expect(jan.start.toISOString()).toBe('2026-01-30T23:00:00.000Z'); // 31 gen
    expect(jan.end.toISOString()).toBe('2026-02-27T23:00:00.000Z'); // 28 feb, non il 31

    // Nessun buco fra un periodo e il successivo.
    const feb = periodContaining('month', c, new Date('2026-03-01T12:00:00Z'));
    expect(feb.start.toISOString()).toBe(jan.end.toISOString());
  });

  it('l anno scatta nel giorno di registrazione', () => {
    const c = ctx('2026-07-15T10:00:00Z', 'anniversary');
    const year = periodContaining('year', c, new Date('2027-01-10T12:00:00Z'));
    expect(year.start.toISOString()).toBe('2026-07-14T22:00:00.000Z');
    expect(year.end.toISOString()).toBe('2027-07-14T22:00:00.000Z');
  });
});

describe('listCompletedPeriods', () => {
  const c = ctx('2026-01-05T00:00:00Z');
  const now = new Date('2026-02-10T12:00:00Z');

  it('esclude il periodo in corso', () => {
    const months = listCompletedPeriods('month', c, now);
    expect(months.map((m) => m.key)).toEqual(['2026-01']);
  });

  it('ordina dal più recente al più vecchio', () => {
    const weeks = listCompletedPeriods('week', c, now);
    const keys = weeks.map((w) => w.key);
    expect(keys[0]).toBe('2026-W06');
    expect([...keys].sort().reverse()).toEqual(keys);
  });

  it('non produce periodi precedenti al collegamento', () => {
    const weeks = listCompletedPeriods('week', c, now);
    const earliest = weeks[weeks.length - 1]!;
    expect(earliest.end.getTime()).toBeGreaterThan(c.trackingSince.getTime());
  });

  it('rispetta il limite', () => {
    expect(listCompletedPeriods('week', c, now, 2)).toHaveLength(2);
  });

  it('non restituisce nulla se nessun periodo si è ancora chiuso', () => {
    const fresh = ctx('2026-02-09T00:00:00Z');
    expect(listCompletedPeriods('month', fresh, now)).toEqual([]);
  });
});

describe('presetRange', () => {
  const c = ctx('2025-06-01T00:00:00Z');
  const now = new Date('2026-07-15T12:00:00Z');

  it('since_tracking parte dal collegamento', () => {
    expect(presetRange('since_tracking', c, now).from.toISOString()).toBe('2025-06-01T00:00:00.000Z');
  });

  it('lifetime risale oltre il collegamento, per includere gli import', () => {
    expect(presetRange('lifetime', c, now).from.getTime()).toBe(0);
  });

  it('4weeks copre 28 giorni', () => {
    const r = presetRange('4weeks', c, now);
    expect((r.to.getTime() - r.from.getTime()) / (24 * HOUR)).toBe(28);
  });

  it('un preset sconosciuto ricade sul collegamento invece di rompersi', () => {
    expect(presetRange('boh', c, now).from.toISOString()).toBe('2025-06-01T00:00:00.000Z');
  });
});
