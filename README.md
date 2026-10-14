# StatisticsByHeart · Reader (app Android)

App Android personale (Kotlin + Jetpack Compose + Material 3) che **legge i dati di salute da Health Connect** sul
telefono e li **invia al servizio self-hosted "writer"** (FastAPI + PostgreSQL, repo `StatisticsByHeart-writer`).
Non è una dashboard: è un intermediario con una UI di stato in stile HUD / sci-fi.

- `applicationId` **`app.sbh.reader`** · nome visualizzato **StatisticsByHeart** · `minSdk 28` · `targetSdk 36` · `compileSdk 37`
- Distribuzione: **APK sideload** firmato con un keystore personale (nessun Play Store).
- Tutte le decisioni di progetto stanno in [`../PROJECT_GUIDE.md`](../PROJECT_GUIDE.md); il contratto API è nel repo writer
  (`../StatisticsByHeart-writer/docs/api-contract.md`, v1.0.0) ed è la fonte di verità per i payload.
- Design system (skill `ui-ux-pro-max`): [`design-system/statisticsbyheart-reader/MASTER.md`](design-system/statisticsbyheart-reader/MASTER.md).

## Cosa fa

| Scheda | Contenuto |
|---|---|
| **Link** | Gauge radiale dello stato (allineato / sincronizzazione / errore / offline / da configurare), **«ultima sync riuscita: N ore fa»**, elementi in coda, pulsante **Sincronizza ora**, e per ogni problema una card *causa + come risolvere + pulsante* (Health Connect assente, permessi, server, token, offline, batteria HyperOS). |
| **Dati** | Elenco dei tipi (registro) con **toggle on/off**, stato permesso, record inviati e data dell'ultimo; riepilogo «oggi» (passi, FC, sonno) da Health Connect; accesso all'**Inventario**. |
| **Inventario** | Per ogni tipo **e per ogni app sorgente** (Feelfit, Fit, Withings…): conteggio record, primo/ultimo record. Serve alla scrematura e a capire cosa scrive davvero ogni app. Solo conteggi e date; si può condividere come testo. |
| **Log** | Cronologia sync (esito, modalità, durata, conteggi, errori leggibili). Mai valori sanitari. |
| **Config** | URL del server, token (cifrato con Keystore, mostra/nascondi), **prova connessione** (`/health` + `/ping`), intervallo (1–24 h), solo Wi‑Fi, riduci animazioni, permessi Health Connect, backfill completo, guida HyperOS, privacy. |
| **Guida HyperOS** | 4 passi guidati (ottimizzazione batteria, avvio automatico, «nessuna restrizione», blocco nelle recenti) con scorciatoie verso le schermate MIUI/HyperOS. |

## Requisiti

- Windows/macOS/Linux con **Android Studio** (testato: Android Studio 2026.2 «AI‑262», con il suo JBR/JDK 25) e Android SDK 37.
- `adb` nel `PATH` (platform-tools dell'SDK) e **debug USB** attivo sul telefono.
- Per il test reale: un telefono con Health Connect (nel sistema da Android 14). **L'emulatore gestisce male Health Connect**: usalo
  solo per vedere la UI.

## Build

Dalla radice del repo (la cartella `gradlew`):

```bash
# 1) JDK: usa quello incluso in Android Studio (o un JDK 17+ qualsiasi)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"      # PowerShell: $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 2) Android SDK: Android Studio scrive local.properties da solo; a mano:
echo "sdk.dir=C:/Users/<tu>/AppData/Local/Android/Sdk" > local.properties   # slash normali, non backslash

# 3) APK debug
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
# 4) test unitari
./gradlew :app:testDebugUnitTest
```

> **Repo dentro OneDrive (o altra cartella sincronizzata).** Il client di sync blocca i file in `build/` e Gradle fallisce con
> *«Unable to delete directory … mergeDebugResources»*. Sposta gli output fuori dalla cartella sincronizzata impostando, una volta,
> in `~/.gradle/gradle.properties`:
>
> ```
> sbh.buildRoot=C:/Users/<tu>/AppData/Local/sbh-build
> ```
>
> (oppure `-Psbh.buildRoot=...` a riga di comando). L'APK finisce in `<buildRoot>/StatisticsByHeart-reader/app/outputs/apk/debug/`.

## Installazione sul telefono

### Debug (sviluppo)

```bash
adb devices                                 # il telefono deve comparire come "device" (non "unauthorized")
adb install -r app-debug.apk
```

Sul **Xiaomi / HyperOS** l'installazione via USB è bloccata finché non attivi, in *Impostazioni → Informazioni sul telefono →
tocca 7 volte «Versione OS» → Impostazioni aggiuntive → Opzioni sviluppatore*: **Debug USB**, **Installa tramite USB** e
**Debug USB (impostazioni di sicurezza)**. Senza questi `adb install` risponde `INSTALL_FAILED_USER_RESTRICTED`.
La build debug accetta HTTP in chiaro **solo** verso `100.84.182.8` (host tailnet della VM, `app/src/debug/res/xml/network_security_config.xml`).

### Release firmata (uso quotidiano)

1. Crea il keystore **personale** (una sola volta, conservalo e fanne una copia: serve per gli aggiornamenti):

   ```bash
   keytool -genkeypair -v -keystore sbh-release.jks -alias sbh -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Crea `keystore.properties` nella radice del repo (è in `.gitignore`, come `*.jks`; **non committarli mai**):

   ```properties
   storeFile=sbh-release.jks
   storePassword=...
   keyAlias=sbh
   keyPassword=...
   ```

3. `./gradlew :app:assembleRelease` → `app-release.apk` (R8 attivo, solo HTTPS). Installalo con `adb install -r` oppure copiandolo sul telefono.

> ⚠️ **Non disinstallare mai l'app** una volta concessi i permessi: Health Connect rende leggibili solo i 30 giorni precedenti al
> *primo* permesso (se non c'è il permesso «storico»), e la finestra riparte a ogni reinstallazione. Per aggiornare usa sempre
> `adb install -r` con lo **stesso keystore**. Concedi il permesso «storico» **prima** del primo backfill.

## Primo avvio (checklist)

1. **Config** → URL del server (es. `https://sbh.local.smarthomeformyhouse.loan`) e token creato dal pannello web del writer → *Prova connessione* → *Salva*.
2. **Link** → *Concedi permessi*: tipi attivi + *storico* + *background* (la richiesta li include se Health Connect li supporta).
3. **Dati → Inventario** → *Avvia scansione* (se vuoi vedere anche i tipi non attivi, *Concedi lettura di tutti i tipi*): è la Fase 0 della guida; *Condividi riepilogo* esporta conteggi e date.
4. **Link** → *Sincronizza ora*: parte il **backfill** (notifica discreta, riprende da dove era arrivato se interrotto).
5. **Config → Guida HyperOS**: completa i 4 passi, poi controlla che «ultima sync riuscita» si aggiorni da solo a schermo spento.

## Architettura

```
app/src/main/java/app/sbh/reader/
├─ health/   HealthTypes (registro: classe HC, permesso, mapper→DTO, aggregati ufficiali) · HealthRepository (astrazione) ·
│            HealthConnectRepository (reale) · HealthAccess (SDK/permessi/feature) · InventoryScanner · OutRecord (DTO del contratto)
├─ sync/     SyncEngine (backfill, Changes API, outbox, consegna) · SyncStore (interfaccia di persistenza)
├─ data/     Room (outbox, stato per tipo, token Changes, log) · DataStore (impostazioni) · RoomSyncStore
├─ net/      SbhApi (interfaccia) · HttpSbhApi (OkHttp, gzip, Bearer) · UrlNormalizer
├─ security/ TokenStore: token API cifrato AES-256-GCM con chiave nell'Android Keystore
├─ work/     SyncWorker/SyncScheduler (WorkManager) · SyncNotifier · SyncRunner/SyncStatusHolder
├─ system/   NetworkMonitor · PowerGuide (intent HyperOS/MIUI)
├─ ui/       theme (token §8.1, font, movimento) · components (HudPanel, gauge, bottoni…) · schermate e ViewModel
└─ di/       modulo Koin
```

**Registro dei tipi.** Aggiungere/togliere un tipo = una voce in `HealthTypes.all` (+ il permesso in `AndroidManifest.xml`, verificato da
`HealthTypesTest`) + la stringa `type_*`. Tier 1 attivo di default, Tier 2 disattivato. Il writer deve conoscere il tipo
(contratto §4.5).

**Algoritmo di sync** (`SyncEngine.run`, una alla volta grazie a un mutex):

1. Precondizioni (server configurato, Health Connect disponibile, permessi) — altrimenti log `FAILED` con causa chiara.
2. **Svuota l'outbox** rimasta da run precedenti (FIFO: un record modificato e poi cancellato arriva come `records` e poi `deletions`).
3. Se esiste un token Changes: **legge le modifiche** (upsert → outbox per tipo, cancellazioni → outbox). Token e outbox sono scritti
   **nella stessa transazione Room**: un crash non perde mai un blocco di modifiche. Token scaduto → `token_expired`, cursori azzerati, backfill completo.
4. Se manca il token (o cambia l'insieme di tipi attivi) ne **crea uno nuovo prima** di leggere lo storico, così nulla va perso durante il backfill.
5. **Backfill a finestre di 7 giorni** per tipo, con paginazione (`pageToken`); salta i tratti vuoti; il cursore per tipo è persistito → riprende dopo interruzioni.
   Dopo ogni finestra consegna l'outbox (l'outbox resta piccola e i problemi di rete/auth emergono subito).
6. Invia gli **aggregati ufficiali giornalieri** di Health Connect (ultimi 2 giorni + giorni toccati dalle modifiche; durante il backfill, i giorni di ogni finestra), perché con più sorgenti (Fit, Feelfit, Withings) sommare i grezzi raddoppia i totali.
7. **Consegna** (`/ingest/records`, `/deletions`, `/daily-aggregates`) con gzip; elimina dall'outbox **solo dopo 2xx**; 401 ferma la run (token non valido); 413 dimezza il batch; 5xx/timeout → backoff di WorkManager (la run riprova con l'outbox intatta); 400/422 scarta l'elemento (bug del client) senza bloccare la coda.
8. `POST /ingest/sync-report` e riga nel Log locale (solo conteggi e codici).

I mapper sono funzioni pure e idempotenti: stesso record HC → stesso DTO (`hc_id`, `last_modified`, sorgente, offset di fuso); lo stesso batch inviato due volte non duplica nulla (chiave `(type, hc_id)` lato writer).

## Sicurezza e privacy

- Nessun valore sanitario nei log, nelle notifiche, nei report al server o nelle eccezioni: solo conteggi, tipi e codici tecnici.
- Token API cifrato con il Keystore; `allowBackup=false` e regole di estrazione che escludono tutto dal backup cloud e dal trasferimento dispositivo.
- La **release** accetta solo HTTPS (`network_security_config`); l'eccezione cleartext esiste solo nella build debug, solo per l'host tailnet.
- Manifest: dichiara i permessi di lettura di tutti i tipi del registro (Android lo richiede), ma l'app **richiede a runtime solo quelli dei tipi attivi** (più *storico* e *background*).
- L'outbox Room contiene temporaneamente i payload in attesa di conferma, nella cartella privata dell'app.
- Test e fixture usano solo dati sintetici.

## Accessibilità

Touch target ≥ 48 dp, righe-interruttore annunciate come *Switch* da TalkBack, gauge con descrizione e live region, stato mai affidato al solo colore
(icona + testo), contrasti verificati da `TokensContrastTest`, «Riduci animazioni» in Config (e rispetto della scala animazioni di sistema), edge‑to‑edge.

## Test

`./gradlew :app:testDebugUnitTest` — JUnit con `HealthRepository` e `SbhApi` finti e store in memoria (`app/src/test/.../fakes`): backfill e ripresa dopo errori,
Changes API (upsert, cancellazioni, paginazione, ordine), token scaduto, nuovo tipo attivato, 413/401/5xx, record rifiutati, mapper (unità e campi del contratto),
client HTTP (gzip, header, mappatura errori), URL, contrasti dei token e copertura dei permessi nel manifest.

## Problemi noti / da sapere

- Le **rotte GPS** (`ExerciseSession.data.route`) sono inviate solo se Health Connect le restituisce (serve il consenso per sessione, non ancora richiesto dall'app).
- `MindfulnessSession` non è nel registro (API ancora sperimentale in Health Connect 1.1).
- `targetSdk 36`: porta a 37 dopo averlo provato sul telefono (compileSdk è già 37).
- Licenze: font Share Tech Mono e Fira Code (OFL) e icone Phosphor (MIT) in [`docs/`](docs/).
