import { pollAllUsers } from '../src/jobs/poll.js';
import { sqlClient } from '../src/db/client.js';

// Esegue un giro di polling e termina. Utile per testare a mano senza
// aspettare il cron.
const results = await pollAllUsers();

for (const r of results) {
  const gap = r.hitPageLimit ? '  [finestra piena: possibile buco]' : '';
  console.log(`${r.userId}  ${r.status}  fetched=${r.fetched} inserted=${r.inserted}${gap}`);
  if (r.error) console.log(`   ${r.error}`);
}

if (results.length === 0) console.log('Nessun utente collegato.');

await sqlClient.end();
