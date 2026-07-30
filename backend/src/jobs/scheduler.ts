import { env } from '../env.js';
import { pollAllUsers } from './poll.js';

/**
 * Timer interno che archivia gli ascolti a intervalli regolari.
 *
 * Ha senso solo su un server sempre acceso (VPS, Raspberry, ZimaOS). Sugli
 * host free tier che sospendono il processo dopo pochi minuti di inattività il
 * timer si spegne insieme al processo: lì serve un cron esterno che chiami
 * `POST /cron/poll`, perché la chiamata stessa risveglia il servizio.
 */

let inFlight = false;

export function startScheduler(): () => void {
  const intervalMs = env.POLL_INTERVAL_MINUTES * 60_000;

  const tick = async () => {
    // Un giro che si trascina oltre l'intervallo non deve accavallarsi al
    // successivo: due poll paralleli sullo stesso utente sprecherebbero
    // richieste all'API e farebbero scattare il rate limit.
    if (inFlight) {
      console.warn('[scheduler] giro precedente ancora in corso, salto questo');
      return;
    }

    inFlight = true;
    const started = Date.now();
    try {
      const results = await pollAllUsers();
      const inserted = results.reduce((sum, r) => sum + r.inserted, 0);
      const errors = results.filter((r) => r.status === 'error').length;
      const gaps = results.filter((r) => r.hitPageLimit).length;

      console.log(
        `[scheduler] ${results.length} utenti, ${inserted} nuovi ascolti, ` +
          `${errors} errori, ${gaps} possibili buchi (${Date.now() - started}ms)`,
      );
    } catch (err) {
      // Un errore qui non deve fermare il timer: il giro dopo può riuscire.
      console.error('[scheduler] giro fallito', err);
    } finally {
      inFlight = false;
    }
  };

  // Primo giro poco dopo l'avvio, non subito: al riavvio del container il
  // database potrebbe non accettare ancora connessioni.
  const firstRun = setTimeout(tick, 15_000);
  const timer = setInterval(tick, intervalMs);

  console.log(`[scheduler] attivo, un giro ogni ${env.POLL_INTERVAL_MINUTES} minuti`);

  return () => {
    clearTimeout(firstRun);
    clearInterval(timer);
  };
}
