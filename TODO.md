# Auftragsplan (Fortschritt)

Vorab: Android-Gerät ist NICHT angeschlossen — keine adb-Schritte.
Build/Compile erst am Ende (vor APK), nicht nach jedem Auftrag.

- [x] Auftrag 1: Bugs + Race-Conditions (Tiefenanalyse + Fixes) — siehe Liste unten
- [x] Auftrag 2: Semantik-Probleme / Unklarheiten
- [x] Auftrag 3: GUI-Module — Logik-Issues + Usability/End-User-Experience
- [x] Auftrag 4: Workflows/Routinen (mehrteilig & einteilig)
- [x] Auftrag 5: 5 Overhaul-Verbesserungen
- [x] Auftrag 6: Release-APK 1.2.0 (versionCode 4) gebaut, HTTP-Server auf 100.107.159.17:8899 (HTTP 200) + GitHub-Release v1.2.0 (Latest, Download-Links HTTP 200)

## Auftrag 1 — behobene Defekte (Kurzliste)

Daten/Netz:

1. ChatRepository: Paging-Leiter brach bei `tooLarge` ab → leere Session (Fix: nur `emptyConfirmed` bricht).
2. BackendSession: OkHttp-Fehler-Body-Leak bei Message-Liste; `hasGuard()` cachte transiente Fehler dauerhaft + schluckte Cancellation; Interceptor-TOCTOU (Host ⇄ Credentials) → ein unveränderlicher Config-Snapshot.
3. OpenCodeApiAdapter: `requireBody` leakte Fehler-Bodies (alle Reply/Rename/Revert-Routen); `getFileContent` wertete 4xx/5xx als Dateiinhalt; globale Permission-Reply-Route war immer tot (`formSessionIds` wurde für Permissions nie befüllt) + Karten unbegrenzt; `sendPrompt` ignorierte fehlgeschlagenes `setModel`/`setAgent`.
4. ProviderDirectory: `invalidate()` war wirkungslos, wenn ein Fetch lief; ein alter Fetch konnte danach noch stale publizieren.
5. MessageCache/HomeCache: stale Disk-Read überschrieb frischen Memory-Eintrag; MessageCache-Header (`#sid:`) liefert die echte Session-ID für die Suche.
6. Stores: BackendStore, ModelVisibilityStore, RecentModelsStore, AppSettingsStore — Read-Modify-Write ohne Lock (verlorene Updates); Keystore-Ausfall löschte Passwörter/Guard-Token nicht mehr.
7. SseClient: Steuer-Events (`sse.connected`) wurden vom Datenpuffer verdrängt (jetzt eigener Kanal); Response-Leak beim Verbindungsaufbau.
8. NetworkMonitor: doppelte Callback-Registrierung (Race).
9. Notifier: Dedupe-Set wurde bei Überlauf geleert (Doppel-Notifications) → LRU-Trim + Lock; Fragen-Dedupe wird bei Auflösung gelöscht.
10. PendingShare: zweiter Share überschrieb den ersten → Merge.

Session/Streaming: 11. Optimistischer Echo wurde bei Modell-unavailable/Conflict/Upload-/Embed-Fehler nicht zurückgerollt (Phantom-Nachricht + verlorener Prompt) → zentraler `rollbackOptimisticEcho`; `optimisticEchoIds` wird in allen Pfaden bereinigt; Conversation-Mirror synchronisiert. 12. `partTypes` wuchs unbegrenzt → gedeckelt (240) + Reset je Step. 13. `turnEnded`-Guard verwarf die ersten Frames eines neuen Turns → `step-start` hebt ihn auf. 14. Doppeltes NotifyDone (status idle + session.idle) → einmal pro Turn. 15. `flushNow` löschte bei jedem Delta den `statusError` (Retry-Anzeige flackerte weg). 16. Reload derselben Session setzte `isGenerating` zurück und verwarf Live-Puffer → Same-Session-Guard. 17. `addAttachments` war kein atomares RMW und blockierte den Main-Thread (ContentResolver) → IO + `update`-Transformation. 18. `_commands.tryEmit` verlor Kommandos (Buffer) → DROP_OLDEST.

Util/Domain: 19. ServerError verwarf gültige Meldungen (present-but-empty `data.message`, nested `error.message`). 20. PathSearch: Home `/` erzeugte Präfix `//` → alles „außerhalb Home". 21. Cost: negative/NaN/Infinity-Beträge. 22. UserMessages: Dedupe-Map wuchs unbegrenzt + nicht-atomar. 23. ModelSelection: expliziter Provider wurde durch Tail-Match überschrieben; Default-Provider-Gefahr; führender/abschließender Slash. 24. MessageEcho: Duplikat-Text (Multiset-Matching), leerer führender Text-Part. 25. StreamPolicies.RefreshCoalescer: erster Request mit `jobActive=true` startete nie. 26. ContextPercent: Overflow bei `totalTokens * 100`. 27. AppLog: Ringpuffer war write-only und hielt Nutzercontent dauerhaft (jetzt nur Debug); CoroutineSafety loggte `throwable.message` im Release. 28. V2EventNormalizer: fehlende `coerceInputValues` → ein `part.type = null` verwarf den ganzen Frame.

App/Lebenszyklus: 29. ConnectionService: kein `onTaskRemoved` → Akku-Leak/hängende FGS-Notification nach Task-Removal. 30. MainActivity: Deep-Link-Extra wurde bei jedem `recreate()` erneut verarbeitet; Intent nur noch beim ersten `onCreate`; Extra wird konsumiert; „letzte Session wiederherstellen" nur einmal pro Prozess. 31. ClientLog hielt die MainActivity (Activity-Leak) → Installation im Application mit Singleton. 32. SessionWidget: renderte vor dem Store-Init (falsches „No session yet") → wartet auf `AppStartup.ready`. 33. NewSessionTileService: ungefangene Exception → `runCatching`. 34. Foreground-Service wird idempotent gestartet (stale `isRunning`-Flag war TOCTOU).
