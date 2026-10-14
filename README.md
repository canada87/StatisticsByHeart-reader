# StatisticsByHeart · Reader (app Android)

App Android personale (Kotlin + Jetpack Compose + Material 3) che **legge i dati di salute da Health Connect** sul
telefono e li **invia al servizio self-hosted "writer"** (FastAPI + PostgreSQL, repo `StatisticsByHeart-writer`).
Non è una dashboard: è un intermediario con una UI di stato in stile HUD / sci-fi.

- `applicationId` **`app.sbh.reader`** · nome visualizzato **StatisticsByHeart** · `minSdk 28` · `targetSdk 36` · `compileSdk 37`
- Distribuzione: **APK sideload** firmato con la chiave personale, pubblicato da GitHub Actions in *Releases* e installato/aggiornato dal telefono con **Obtainium** (nessun Play Store, nessun cavo USB).
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
- Un telefono con Health Connect (nel sistema da Android 14) e **Obtainium** per installare l'app (vedi sotto). **Non serve `adb` né il debug USB.**
  L'emulatore gestisce male Health Connect: usalo solo per vedere la UI.

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

## Installare e aggiornare l'app (GitHub Releases + Obtainium)

**Niente cavo e niente debug USB.** L'app si installa e si aggiorna **solo** da GitHub:
ogni volta che crei un *tag* di versione (`v0.1.0`, `v0.1.1`, …) GitHub compila l'app, la firma con la **tua chiave personale**,
pubblica l'APK in *Releases*, e **Obtainium** sul telefono lo scarica e lo installa (e ti avvisa degli aggiornamenti).

```
 tuo PC ──git push tag──► GitHub Actions ──► Release con APK firmato ──► Obtainium (telefono) ──► app installata/aggiornata
```

Cosa serve, una volta sola: **A** la chiave, **B** i secret su GitHub, **C** Obtainium sul telefono (**D**, **E**).
Poi per ogni nuova versione basta il passo **F**.

> ⚠️ **La chiave di firma è insostituibile.** Android accetta un aggiornamento solo se è firmato con la *stessa* chiave
> dell'app già installata. Se la perdi non puoi più pubblicare aggiornamenti: dovresti disinstallare l'app e reinstallarla da zero,
> e Health Connect rilegge solo i 30 giorni precedenti al nuovo primo permesso. Fai subito le copie di sicurezza del passo A.

### A · Creare la chiave di firma (una sola volta)

Apri **PowerShell** (non serve essere amministratore). Il file della chiave va **fuori dal repo e fuori da OneDrive**:
la cartella del tuo utente, ad esempio `C:\Users\<tuo-nome>\sbh-keys`, va bene.

```powershell
# 1) cartella per la chiave (fuori dal repo, fuori da OneDrive)
New-Item -ItemType Directory -Force "$env:USERPROFILE\sbh-keys"

# 2) crea la chiave (keytool è incluso in Android Studio)
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore "$env:USERPROFILE\sbh-keys\sbh-release.jks" -alias sbh `
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` ti chiede una **password** (scegline una lunga; se chiede anche la «password della chiave» puoi ripetere la stessa) e
qualche dato (nome, ecc.: puoi scrivere quello che vuoi, non viene mostrato a nessuno). Alla fine hai il file `sbh-release.jks`.
Segna questi **quattro valori**, ti serviranno nel passo B:

| Cosa | Valore |
|---|---|
| file | `C:\Users\<tuo-nome>\sbh-keys\sbh-release.jks` |
| password del keystore | quella scelta sopra |
| alias | `sbh` |
| password della chiave | quella scelta sopra (di solito la stessa) |

**Fai subito due copie di sicurezza** (senza, un PC rotto = niente più aggiornamenti):

1. **Password manager**: crea una voce «StatisticsByHeart – chiave di firma», allega il file `sbh-release.jks` e scrivi password e alias.
2. **NAS**: copia `sbh-release.jks` in una cartella del NAS con permessi ristretti (contiene la chiave privata: non nella stessa cartella dei dump del database).

*(Facoltativo)* per riconoscere la chiave in futuro, annota la sua impronta:
`& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\sbh-keys\sbh-release.jks"` → riga `SHA256:`.
La stessa impronta compare nel log di ogni release su GitHub (passo «Name, verify and checksum the APK»): se cambia, hai usato un'altra chiave.

Mai committare la chiave: `*.jks`, `*.keystore` e `keystore.properties` sono già in `.gitignore`.

### B · Creare i secret su GitHub (una sola volta)

1. In PowerShell, trasforma il file della chiave in testo e **copialo negli appunti**:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\sbh-keys\sbh-release.jks")) | Set-Clipboard
   ```

2. Su GitHub apri il repo `StatisticsByHeart-reader` → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
3. Crea questi **4 secret**, con questi nomi **esatti** (maiuscole comprese):

   | Nome del secret | Cosa incollare |
   |---|---|
   | `SBH_KEYSTORE_BASE64` | il testo copiato al punto 1 (lunghissimo, su una riga) |
   | `SBH_KEYSTORE_PASSWORD` | la password del keystore |
   | `SBH_KEY_ALIAS` | `sbh` |
   | `SBH_KEY_PASSWORD` | la password della chiave |

I secret non si possono rileggere dopo averli salvati: se sbagli, ricrea quello sbagliato. Tieni attiva l'autenticazione a due fattori
sul tuo account GitHub: chi può scrivere nel repo può far partire una release firmata con la tua chiave.

### C · Pubblicare una release

La prima volta il codice deve essere già su GitHub (`git push origin main`). Poi, dalla cartella del repo:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

1. Su GitHub, scheda **Actions**: parte il workflow **Release** (circa 5–10 minuti: test, build, firma).
2. Quando è verde, nella scheda **Releases** trovi `StatisticsByHeart-v0.1.0.apk` (e il suo `.sha256`).
3. Per la versione successiva usa un numero più alto: `v0.1.1`, `v0.2.0`, `v1.0.0`…

Come sono calcolati i numeri (sono decisi da `app/build.gradle.kts`, non a mano):

- **versionName** = il tag senza la `v` (`v0.1.0` → `0.1.0`).
- **versionCode** = `MAJOR×10000 + MINOR×100 + PATCH` (`0.1.0` → 100, `0.1.1` → 101, `0.2.0` → 200, `1.0.0` → 10000).
  Cresce sempre quando sale la versione, ed è ciò che permette ad Android di accettare l'aggiornamento (non serve contare nulla a mano).
  Limiti: `MINOR` e `PATCH` devono essere minori di 100 e non si usano suffissi tipo `-rc1`; il workflow rifiuta i tag che non sono `vX.Y.Z`.
- Se una release fallisce o è sbagliata: cancella tag e release e ripeti, mai riusare un numero già installato sul telefono:
  `git push --delete origin v0.1.0` e `git tag -d v0.1.0` (poi elimina la release dalla pagina Releases).

Il workflow **CI** (`.github/workflows/ci.yml`) gira a ogni push su `main` e a ogni pull request: solo build e test, senza firma e senza segreti.

### D · Configurare Obtainium sul telefono

1. Installa **Obtainium** (<https://github.com/ImranR98/Obtainium>, dalla pagina Releases o da F-Droid).
2. In Obtainium: **Aggiungi app** → incolla l'URL del repo: `https://github.com/canada87/StatisticsByHeart-reader` → **Aggiungi**.
   (Facoltativo, nelle opzioni dell'app: filtro APK `StatisticsByHeart-.*\.apk$`, così sceglie sempre e solo l'APK.)
3. Tocca **Installa**. Da qui in poi Obtainium controlla da solo se c'è una nuova release e ti propone l'aggiornamento.

**Repo pubblico o privato? Scegli tu.**

- **Repo pubblico**: Obtainium funziona subito, senza altro. Il repo non contiene segreti né dati (la chiave e i token non sono mai nel codice,
  i test usano dati finti), ma chiunque può leggere codice, documentazione e il nome dei tuoi server di esempio.
- **Repo privato**: serve un **token GitHub a sola lettura**, da inserire in Obtainium (solo sul telefono):
  1. GitHub → foto profilo → **Settings** → **Developer settings** → **Personal access tokens** → **Fine-grained tokens** → **Generate new token**.
  2. *Repository access*: **Only select repositories** → `StatisticsByHeart-reader`.
  3. *Permissions* → *Repository permissions* → **Contents: Read-only** (Metadata read-only viene aggiunto da solo). Niente altro.
  4. Scegli una scadenza (anche 1 anno: poi va rinnovato e riscritto in Obtainium), **Generate token**, copialo subito.
  5. Obtainium → **Impostazioni** → **GitHub personal access token** → incolla il token.

### E · Impostazioni del telefono (Xiaomi / HyperOS)

1. **Disattiva** (in *Impostazioni → Informazioni sul telefono → … → Opzioni sviluppatore*): **Debug USB**, **Installa tramite USB**
   e **Debug USB (impostazioni di sicurezza)**. Non servono più.
2. **Concedi a Obtainium** il permesso di installare app sconosciute: *Impostazioni → App → Autorizzazioni → Installa app sconosciute → Obtainium → Consenti*
   (la prima volta Android o HyperOS te lo chiedono da soli quando tocchi «Installa»).
3. **Disinstalla la build debug attuale** di StatisticsByHeart (tieni premuta l'icona → *Disinstalla*): ha una firma diversa, quindi la versione
   di Obtainium non potrebbe installarsi sopra. La build debug è vuota (nessun permesso, nessun dato), non si perde nulla.
4. Se HyperOS mostra una schermata di «controllo sicurezza» all'installazione, scegli *Installa comunque* (l'APK è firmato da te).
5. Dopo la prima installazione concedi i permessi di Health Connect (**storico incluso, prima del primo backfill**) e segui la *Guida HyperOS* dell'app.

### F · Ogni nuova versione (riepilogo)

```powershell
git push origin main          # il codice
git tag v0.1.1                # un numero più alto dell'ultimo
git push origin v0.1.1        # parte la release
```

Aspetta il workflow verde, poi in Obtainium tocca **Aggiorna** (o aspetta la notifica). I permessi e i dati dell'app restano, perché la chiave è la stessa.

### Build firmata in locale (facoltativa)

Non serve per l'uso normale. Se vuoi comunque costruire l'APK firmato sul tuo PC crea `keystore.properties` nella radice del repo
(ignorato da git, **non committarlo**):

```properties
storeFile=C:/Users/<tuo-nome>/sbh-keys/sbh-release.jks
storePassword=...
keyAlias=sbh
keyPassword=...
```

e lancia `./gradlew :app:assembleRelease` (con `SBH_VERSION_NAME=0.1.0` nell'ambiente se vuoi un numero preciso). In alternativa le stesse
informazioni possono arrivare dalle variabili d'ambiente `SBH_KEYSTORE_FILE`, `SBH_KEYSTORE_PASSWORD`, `SBH_KEY_ALIAS`, `SBH_KEY_PASSWORD`
(è ciò che usa la CI). **Senza chiave la build release si ferma con un messaggio chiaro**: non esiste nessun ripiego silenzioso sulla chiave debug.
Gli APK firmati con una chiave diversa da quella di GitHub non si possono installare sopra quelli di Obtainium.

> ⚠️ **Non disinstallare l'app** una volta concessi i permessi: Health Connect rende leggibili solo i 30 giorni precedenti al *primo* permesso
> (se non c'è il permesso «storico») e la finestra riparte a ogni reinstallazione. Aggiorna sempre dalla stessa chiave (Obtainium).

### Sviluppo (facoltativo)

Per provare la UI senza telefono: emulatore Android Studio (`./gradlew :app:assembleDebug`, APK in `app/build/outputs/apk/debug/`);
Health Connect sull'emulatore funziona male, quindi l'inventario e la sync vanno provati sul telefono vero, installando con Obtainium.
La build debug accetta HTTP in chiaro **solo** verso `100.84.182.8` (host tailnet della VM, `app/src/debug/res/xml/network_security_config.xml`).

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
