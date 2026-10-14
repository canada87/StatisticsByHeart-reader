# Backlog del Reader

Idee decise con l'utente ma **non ancora implementate**. Ogni voce dice cosa, perché e come tagliarla quando sarà il momento.

## Modalità «nuovo dispositivo» (cambio telefono)

**Problema.** Quando si cambia telefono, Fit e Withings riscaricano dal cloud i dati storici sul nuovo Health Connect, ma con
`hc_id` **nuovi** (l'`id` di Health Connect è locale al dispositivo). La chiave di upsert del writer è `(type, hc_id)`, quindi quei
record verrebbero inseriti una seconda volta: stessi passi, stesso sonno, doppi sul server.

**Decisione.** Al passaggio, il Reader si comporta da dispositivo nuovo con una **data di partenza (cutoff)**: backfill e sync
considerano solo i record con `start_time ≥ cutoff`. Lo storico precedente resta quello già inviato dal vecchio telefono.

**Procedura prevista.**

1. **Sul vecchio telefono**, prima del passaggio: un ultimo **sync completo** (Link → *Sincronizza ora*, coda a zero, esito «Riuscita» nel Log).
2. **Sul nuovo telefono**: installare con Obtainium, poi in Config → *Nuovo dispositivo* (voce da creare) confermare il passaggio.
   L'app genera un **nuovo `device_id`** (contratto §1.1) e salva `cutoff = ora del passaggio`.
3. Backfill e sync filtrano per `start_time ≥ cutoff` (finestre di lettura e filtro sui record della Changes API); gli aggregati giornalieri
   si inviano solo per i giorni da `cutoff` in poi. Il token Changes è nuovo, perché il dispositivo è nuovo.

**Dove toccare (stima, lavoro piccolo).**

- `SettingsRepository`: `deviceId` rigenerabile + `cutoff: Instant?`.
- `SyncEngine`: punto di partenza del backfill = `max(EPOCH, cutoff)` e scarto dei record con `startOf(record) < cutoff` anche nelle modifiche.
- Config: azione con conferma + testo che ricorda il sync completo sul vecchio telefono.
- Test: un record prima del cutoff non viene mai inviato; un record dopo sì; il cambio di `device_id` non riusa cursori né token.
- **Writer**: nessuna modifica necessaria (il nuovo `device_id` viene registrato al primo uso, contratto §1.1); da verificare con la sessione writer
  come mostrare i due dispositivi nel pannello Sync e se i valori «ufficiali» giornalieri devono ignorare il vecchio dispositivo dopo il cutoff.

**Limiti noti.** Un record modificato dopo il cutoff ma iniziato prima non viene più aggiornato (accettato: è il comportamento voluto).
Il passaggio non è reversibile senza un nuovo backfill completo.

## Altre idee

- Consenso per-sessione alle rotte GPS (`ExerciseRouteRequestContract`) per inviare `ExerciseSession.data.route`.
- Rilevare l'ora dell'ultima sync riuscita anche lato writer e avvisare dopo 48 h senza sync (Fase 5 della guida).
