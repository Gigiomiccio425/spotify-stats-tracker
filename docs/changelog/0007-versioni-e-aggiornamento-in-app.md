# 0007 — Versioni e aggiornamento senza reinstallare

Tocca build, distribuzione e interfaccia. **Ha un equivalente su iOS**, ma diverso: vedi in fondo.

## Contesto

Per aggiornare l'app bisognava disinstallarla e reinstallarla. Non era una scelta: le build erano
APK di **debug**, e la chiave di debug su un runner di CI viene **generata nuova a ogni esecuzione**.
Due build consecutive avevano due firme diverse, e Android rifiuta di installare sopra un'app firmata
da qualcun altro — è la regola che impedisce a un APK qualsiasi di sostituirne uno legittimo.

Mancava anche il resto della catena: nessun numero di versione che crescesse, nessun posto dove
scaricare l'ultima, nessun modo per l'app di sapere che ne esisteva una più recente.

## Le tre parti

### 1. Firma stabile

L'APK di release viene firmato con una chiave fissa, tenuta nei secret del repository
(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`)
e ricostruita nel runner al momento della build. Se i secret mancano — build locale, pull request da
un fork — si ripiega sulla firma di debug e non si pubblica nulla.

La chiave non sta nel repository. Chi ce l'ha può pubblicare un aggiornamento che i telefoni
installano sopra questo senza chiedere niente.

### 2. Numero di versione che cresce da solo

| Cosa | Da dove | A cosa serve |
|---|---|---|
| `versionCode` | conteggio dei commit (`git rev-list --count HEAD`) | Android confronta questo per decidere se un APK è più recente |
| `versionName` | `appVersionName` in `android/gradle.properties` | quello che legge l'utente, si alza a mano |

Il conteggio dei commit cresce senza che nessuno se ne debba ricordare. Richiede però
`fetch-depth: 0` nel checkout: con un clone superficiale il conteggio è più basso del vero e
l'aggiornamento verrebbe rifiutato come se fosse un ritorno indietro.

R8 è **disattivato** in release. È l'APK che finisce sul telefono e non c'è modo di collaudarlo
prima: un errore nelle regole di conservazione si manifesta solo a runtime, solo in release, spesso
su una schermata che in debug funzionava. Le regole restano in `proguard-rules.pro`, pronte per
quando ci sarà modo di provarle.

### 3. Pubblicazione e controllo aggiornamenti

Ogni push su `main` che tocca `android/**` pubblica una release con tag `v<versionName>-<versionCode>`
(es. `v0.2.0-47`) e l'APK allegato.

L'app interroga `https://api.github.com/repos/<owner>/<repo>/releases/latest` — client HTTP separato,
non quello del backend — legge il numero in coda al `tag_name`, lo confronta con il proprio
`versionCode` e, se è più alto, mostra il link all'asset `.apk`.

Il confronto va fatto **sul numero di build, non sul nome della versione**: "0.2.0" e "0.10.0"
ordinati come testo danno il risultato sbagliato.

Stati distinti, e vanno tenuti distinti: *aggiornato*, *disponibile la versione X*, *controllo non
riuscito*. Il terzo non è il primo — un controllo fallito non dice nulla sull'esistenza di un
aggiornamento, e mostrarlo come "sei aggiornato" è una bugia.

## Versione del backend

`GET /health` ora risponde anche con `version`:

```json
{ "ok": true, "now": "2026-08-01T…", "version": "cd97f59…" }
```

È la revisione con cui l'immagine è stata compilata, iniettata come build arg del Dockerfile. Vale
`dev` per le build locali. L'app la mostra in **Profilo → Versione**, accorciata a sette caratteri.

Serve a rispondere a "il server sta girando la versione nuova?", che senza un numero visibile si può
solo sperare.

## Per il porting su iOS

Il meccanismo di aggiornamento **non si porta**: su iOS la distribuzione passa da App Store o
TestFlight, che gestiscono versioni e aggiornamenti per conto loro. Non serve né il controllo su
GitHub né il link di download.

Si porta invece il resto:

- **Mostrare la versione dell'app** (`CFBundleShortVersionString` e `CFBundleVersion`) nelle
  impostazioni.
- **Mostrare la versione del backend** letta da `GET /health`, accorciata. Questa serve identica su
  entrambe le piattaforme: è l'unico modo di sapere con che server si sta parlando.
- La regola generale: **confrontare le versioni su un intero monotono, mai sul nome**.

## Trappole

| Trappola | Conseguenza |
|---|---|
| Firmare in debug in CI | Chiave nuova a ogni build: nessun aggiornamento è mai installabile |
| Clone superficiale con `versionCode` derivato dai commit | Il numero cala e Android rifiuta l'APK |
| Confrontare le versioni sul nome | 0.10.0 risulta più vecchia di 0.2.0 |
| Presentare un controllo fallito come "sei aggiornato" | L'utente resta indietro senza saperlo |
| Tenere la chiave di firma nel repository | Chiunque può pubblicare un APK che sostituisce il tuo |

## Nota sulla prima volta

Il passaggio dalla firma di debug a quella stabile **richiede una disinstallazione**, una volta sola:
la vecchia app è firmata con un'altra chiave. Da lì in poi ogni APK si installa sopra il precedente.

Non si perde nulla di importante: impostazioni dei periodi e ora del recap vivono sul server, non sul
telefono. Vanno reinseriti solo l'indirizzo del backend e l'accesso a Spotify.
