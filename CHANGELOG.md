# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

- **The "/" picker lists every command, and they actually run.** It was capped at
  six matches (`take(6)`) and matched with `contains`, so most of the server's
  commands were hidden and which ones appeared changed with the catalog order.
  It now lists all matches (prefix first, like the TUI) in a scrollable list, and
  a "/name args" line runs through the server's `POST /api/session/{id}/command`
  endpoint — the same path the TUI uses, where the command template is expanded —
  instead of being posted as literal text.

- **The picker only offers commands that actually run.** Client built-ins are now
  filtered against the set the app implements (`IMPLEMENTED_BUILTIN_COMMANDS`) and
  run through one dispatch, so a command can no longer be listed and then silently
  do nothing — the failure mode the TUI's own client-side commands (/sessions,
  /themes, /undo, /quit, …) would hit, since they have no server endpoint. A unit
  test keeps the advertised list and the handlers in lockstep.

## [1.5.3] - 2026-10-13

### Removed
- **Der Tab „Shortcuts" ist komplett entfernt.** Er listete nur die Desktop-/Web-Tastenkürzel,
  die in der Android-App keine Wirkung haben (`ShortcutsTab`, `SHORTCUT_GROUPS` und die zugehörigen
  Ressourcen sind gelöscht). Die Einstellungen haben jetzt vier Tabs: General · Servers ·
  Providers · Models.
- **„Reset all settings" ist an die richtige Stelle gezogen:** Die Aktion lag auf dem entfernten
  Shortcuts-Tab. Sie setzt ALTE Einstellungen zurück (Theme, Fonts, Notifications, Sounds,
  Guard-Token, Server) und steht jetzt im General-Tab direkt neben Export/Import — mit klarem
  Namen und rot markiert, statt wie ein Shortcut-Reset zu wirken.

### Changed
- Die Tab-Beschriftungen der Einstellungen sind jetzt Ressourcen (waren englische Literale),
  und ein veralteter gespeicherter Tab-Index (aus einer Version mit dem Shortcuts-Tab) fällt
  sauber auf „General" zurück, statt einen leeren Screen zu zeigen.

## [1.5.2] - 2026-10-13

### Fixed
- **Frage-Antworten (question tool) kommen jetzt wirklich beim Server an.** Zwei Ursachen,
  beide am laufenden Server verifiziert:
  1. Der Server validiert die Antwort gegen den **JSON-Typ** des Feldes: `string` verlangt einen
     **String**, `multiselect` ein **Array**, `number`/`integer` eine **Zahl**, `boolean` einen
     **Boolean**. Die App hat **alles als Array** geschickt → `FormInvalidAnswerError`
     ("Expected string for form field: …"), die Antwort erreichte den Server nie und die UI
     meldete „die Frage ist abgelaufen". Jetzt wird pro Feldtyp der passende Typ gesendet.
  2. Bei Auswahlfeldern müssen die **Option-Werte** gesendet werden, nicht die Labels
     (`Invalid option for form field: …`). Die Auswahl liefert jetzt `value` (mit `label` als
     Fallback, falls der Server keinen Wert liefert).
- **Keine verlorene Antwort mehr:** Der Antwortdialog entfernte die Karte optimistisch. Schlug
  das Senden fehl, war sie weg und die Antwort musste von Hand nachgetippt werden. Die Karte
  wird jetzt bei einem Fehlschlag **wiederhergestellt** (nur bei „bereits beantwortet" bleibt
  sie entfernt) und die Fehlermeldung nennt den echten Grund.
- **Echte Fehlerursachen statt „abgelaufen":** Die Server-Antwort wird ausgewertet —
  `FormAlreadySettledError` → „Diese Frage wurde bereits beantwortet", `FormNotFoundError` →
  „Diese Frage ist nicht mehr offen", `FormInvalidAnswerError` → die Server-Begründung.
- **Unbekannte Formular-ID:** Wurde die Karte aus einem SSE-Event gebaut (die Event-ID ist nicht
  immer die Formular-ID `frm_…`), wird die Antwort einmal mit der **echten offenen Formular-ID**
  wiederholt, bevor aufgegeben wird.
- **Feld-Schlüssel werden bei Bedarf nachgeladen:** Die Antwort muss mit den Feld-SCHLÜSSELN
  verschlüsselt gesendet werden; fehlten sie (Karte aus einem Event), bricht die App nun nicht
  mehr ab, sondern holt das Formular (`GET /api/session/{id}/form/{fid}`) und antwortet korrekt.

## [1.5.1] - 2026-10-13

### Fixed
- **Settings → Servers: die Server-Adresse ist wieder sichtbar.** Name, URL und Passwort-Status
  teilten sich EINE Zeile mit drei Aktions-Buttons. Mit dem zusätzlich hinzugekommenen „Copy URL"
  blieb dem Text kein Platz mehr, sodass die **URL verschwand** und „Remove" **vertikal umbrach**.
  Jetzt stehen die Identität (Name · Adresse · Passwort-Status) und „Active" in der ersten Zeile,
  die Buttons (Rename… · Copy URL · Remove) in einer eigenen Zeile darunter (`FlowRow`, damit sie
  auf schmalen Displays umbrechen statt gequetscht zu werden).
- **Die Adresse wird immer angezeigt.** Vorher erschien sie *nur*, wenn ein Anzeigename gesetzt war;
  bei einem Server ohne Namen stand dort stattdessen der Passwort-Status — die Adresse war also
  grundsätzlich nicht ablesbar, wenn man keinen Namen vergeben hatte.

## [1.5.0] - 2026-10-13

Vierte Tiefenanalyse: Zustand/Streaming, Daten/Netz, Plattform/Android 16, Semantik, GUI, Workflows.

### Fixed (Auswahl)
- **Sicherheit**: Der Verbindungs-Guard verglich nur den Hostnamen. Zwei Server auf demselben Host
  (der Normalfall lokaler `opencode serve`-Instanzen, z. B. `:4096` und `:3000`) teilten ihn, sodass
  ein noch laufender Request des alten Ports die Basic-Credentials und den Guard-Token des NEUEN
  Backends bekam. Der Vergleich schließt jetzt den Port ein.
- **Prompt-Pfade melden Fehler**: Der Legacy-Fallback (`sendPrompt`) meldete Erfolg auch bei einem
  HTTP-Fehler (Prompt still verloren), und `sendPromptAsync` ignorierte ein abgelehntes
  `setModel`/`setAgent` (Prompt lief auf dem vorherigen Modell weiter). Beide prüfen jetzt den Status;
  der Fallback übergibt außerdem einen provider-qualifizierten Model-Ref (eine nackte ID wurde nicht
  geparst und das Modell stillschweigend nicht gesetzt).
- **Zustand**: Ein Session-Wechsel während eines Uploads ließ den Spinner und den deaktivierten
  Senden-Button hängen (`isUploading` wurde nicht zurückgesetzt). Der MCP-Toggle entfernte im
  `finally` den Eintrag eines NEUEREN Jobs (zwei parallele Poll-Loops). Der Datei-Viewer ist jetzt
  gegen verspätete Antworten gefenced.
- **Verpasste Turn-Enden**: Ein terminales `session.idle` kann im begrenzten SSE-Puffer verworfen
  werden; der Watchdog lief aber nur bei getrennter Verbindung. Er resynct jetzt immer, solange ein
  Turn läuft.
- **Android 16**: `ACCESS_LOCAL_NETWORK` deklariert und zur Laufzeit angefragt (ohne die Freigabe
  scheitert jede LAN-Verbindung zum Server), Notification-Reply initialisiert die Stores auf IO statt
  auf dem Main-Thread (Keystore-Entschlüsselung), CI prüft jetzt auch den `versionCode` gegen die
  vorherige Version (ein nicht erhöhter Code blockiert Updates).
- **Lokalisierung**: Benachrichtigungs-Aktionen/-Titel/-Kanalname und Widget-Texte nutzen Ressourcen
  statt Literalen.

### Changed / Added
- **Semantik**: doppelter `statusError`-Banner entfernt (der Composer zeigt den Text vollständig),
  Archived-Markierung unabhängig vom Running-Status sichtbar, Todo-Plural richtet sich nach der
  Gesamtzahl, „Session info" bezieht Agent und Modell aus derselben Quelle, der wirkungslose
  zweite Abort-Aufruf entfällt, „Reset to defaults" heißt jetzt „Reset all settings" (es setzt alles
  zurück, nicht nur Shortcuts).
- **GUI**: Ein Provider ohne gemeldete Auth-Methode (z. B. ein „custom"-Eintrag) war nicht
  verbindbar — das Key-Feld fehlte und „Continue" blieb dauerhaft deaktiviert. Enter/Ctrl+Enter
  stoppt jetzt einen laufenden Turn (wie der Stop-Button, statt den Turn zu verwerfen). Dateien
  ohne Text-Diff zeigen einen Hinweis statt ins Leere zu expandieren. Tagesgruppierung nutzt
  lokale Kalendergrenzen. Eine Nur-Reasoning-Nachricht rendert keine leere Bubble mehr.
- **Vergoldung**: „Copy session directory", „Copy project path", „Copy server URL" und „Copy error"
  überall dort, wo der Text sonst nicht kopierbar war; kompakte Anhangs-Zusammenfassung
  (Anzahl + Gesamtgröße) im Composer.

## [1.4.2] - 2026-10-11

### Fixed
- **Die Projektliste ist jetzt genau die Liste, die du selbst festgelegt hast.** Es gibt eine
  persistente „Meine Projekte"-Auswahl: nur Verzeichnisse darin erscheinen auf dem Home-Screen.
  Gelöschte Projekte kommen nicht zurück (auch nicht nach einem App-Neustart) und der Server kann
  keine Projekte mehr „einschmuggeln" (z. B. sein eigenes globales Verzeichnis oder `/`).
  Beim ersten Start nach diesem Update wird die Liste einmalig aus den vorhandenen Projekten
  (abzüglich der bereits gelöschten) übernommen — es geht also nichts verloren.
- **Verzeichnisse werden kanonisch verglichen** (`normalizeProjectDir`: Schrägstriche am Ende,
  doppelte Trenner, Whitespace). Vorher scheiterte der exakte String-Vergleich, wenn der Server
  denselben Pfad anders schrieb — genau dadurch tauchte ein gelöschtes Projekt wieder auf bzw.
  konnte doppelt erscheinen.
- **Löschen/Hinzufügen wird sofort persistiert** (Cache-Snapshot + Einstellungen). Vorher fehlte
  beim Löschen der Cache-Schreibvorgang, sodass ein Kaltstart das gelöschte Projekt kurz (und bei
  je nach Server-Schreibweise dauerhaft) wieder anzeigte. Beim Löschen des letzten Projekts wird
  der Snapshot entfernt.
- **Keine Phantom-Projekte mehr:** Das zuletzt benutzte Verzeichnis wurde bisher zusätzlich als
  Projekt angelegt (auch wenn es nie eines war) und erschien als zufälliger Eintrag. Es dient jetzt
  nur noch der Auswahl unter den eigenen Projekten.
- **Server-Projekte bleiben erreichbar:** Im „Projekt hinzufügen"-Dialog erscheinen die Projekte,
  die der Server kennt, aber nicht in deiner Liste sind („On the server, not in your list") als
  Ein-Tipp-Vorschläge — dort holst du sie bewusst zurück.

## [1.4.1] - 2026-10-11

### Changed
- **Fonts sind jetzt Auswahllisten statt Freitextfelder.** Interface- und Code-Font bieten eine
  kuratierte Liste von Schriftfamilien, die jedes Android-Gerät mitbringt (Auraas-
  Plattform-Aliase wie `sans-serif`, `sans-serif-light`, `sans-serif-condensed`, `serif`,
  `casual`, `cursive`, `monospace`, `serif-monospace`) plus „System default".
  Hintergrund: Das Feld enthielt einen **Geräte-Fontnamen**; ein selbst getippter Name, den das
  Gerät nicht kennt, fiel still auf die Standardschrift zurück — die Änderung sah wirkungslos aus.
- **Live-Vorschau**: jede Zeile des Auswahldialogs und die Zeile im Screen rendern
  „Aa Bb Cc 0123" in der jeweiligen Schrift, so dass die Wahl vor und nach dem Anwenden sichtbar ist.
- **Sofortige Wirkung**: die Auswahl schreibt direkt in die Settings (StateFlow → Theme), die
  gesamte Oberfläche wechselt ohne Neustart; Code-Blöcke folgen dem Code-Font.
- Ein in einer älteren Version gespeicherter eigener Wert bleibt als „Custom"-Eintrag wählbar und
  wird nicht stillschweigend ersetzt.

## [1.4.0] - 2026-10-11

Dritte Tiefenanalyse: Zustand/Streaming, IO/Daten, Plattform/Android-16, Semantik, GUI, Workflows.

### Fixed (Auswahl)
- **Session-Wechsel**: sitzungsgebundene Listen (Fragen, Permissions, Todos, Diff, Kontext) werden
  jetzt geleert, und der laufende Refresh der alten Session wird gefenced (keine „A unter B"-Flackerei).
- **Turn-Ende**: `session.status idle` räumt auch `isCompacting`; Home-Statusmengen werden mit dem
  SSE-Zustand gemerged (Badge blinkt nicht mehr weg).
- **Netz**: verworfene `Response<ResponseBody>` (interrupt, synchroner Prompt, Client-Log,
  OAuth-Callback) werden geschlossen — kein Verbindungsleck pro „Stop".
- **Katalog**: ein laufender Provider-Fetch gilt nicht mehr als „stale" (der ~6 MB Katalog blieb
  sonst dauerhaft auf „Loading" und die Modellauswahl war leer).
- **Cache**: Lesevorgänge können nach einem Backend-Wechsel keinen Snapshot des alten Servers
  wiederbeleben (Generationsprüfung auch im Read-Pfad).
- **Cancellation**: der äußere `loadProjects`-Catch reicht `CancellationException` durch (kein
  falscher Fehler-Banner mehr).
- **Android 16 / Plattform**: doppelte IME-Insets behoben (Root konsumiert das Inset), Manifest
  registriert `ACTION_SEND_MULTIPLE` (Mehrfach-Share war toter Code), Benachrichtigungen werden bei
  verweigerter Berechtigung nicht mehr fälschlich als „schon gemeldet" markiert, Widget-Zustand
  wird zentral gespiegelt (kein ewiges „Working…" nach Verlassen des Chats), Release-CI prüft
  Tag gegen APK-Version.

### Changed / Added
- **Semantik**: „Restore messages" heißt korrekt „Bring back reverted messages" (es ist ein
  Server-Un-Revert, kein Cache-Restore), Nutzungsprozent nutzt überall dieselbe (overflow-sichere)
  Berechnung, Archiv-Zähler ist projektbezogen statt geräteglobal, veraltete Doku in AppLog/Pagination
  und dem Echo-Präfix korrigiert.
- **GUI**: Guard-Button tut, was sein Label verspricht; eine offene Frage wird nicht mehr doppelt
  interaktiv gerendert; `statusError` erscheint nur einmal; IME-Senden respektiert laufende Uploads;
  Tagesgruppierung aktualisiert sich über Mitternacht.
- **Vergoldung**: Tab-Zustand überlebt Rotation/Prozess-Tod (`rememberSaveable`), „Copy last answer",
  Projektname im Chat-Untertitel, Live-Reasoning respektiert die Einstellung, „Archived"-Markierung
  in der Archiv-Ansicht, `@`-Mention-Picker ohne Kappung, eigene Icons/Clear-Buttons in Pickern,
  Tausender-Trennung für Token-Zahlen.

## [1.3.0] - 2026-10-11

Second deep-analysis pass: state/IO/platform bugs, semantics, GUI logic/UX and workflows.

### Fixed (Auswahl)
- **Session-Zustand**: `renameSession`/`compactSession`/Frage-Antworten schreiben nicht mehr in eine
  inzwischen gewechselte Session (Session-Guard).
- **Live-Buffer**: `finalize()` faltet noch nicht geflushte Deltas, sodass der letzte Text nicht
  doppelt (Live-Block + persistierte Bubble) stehen bleibt.
- **Abbruch**: Ein durch Session-Wechsel abgebrochener Send bereinigt den Echo-Id-Satz und stellt
  den Entwurf wieder her.
- **Cancellation**: `loadProjects`/`loadContext`/`loadFiles` schlucken `CancellationException`
  nicht mehr (Single-Flight blieb sonst wirkungslos, falsche Fehlermeldungen).
- **Backend**: Ungültige gespeicherte URLs können den Prozess nicht mehr beim Start/Receiver
  crashen (`isUsableUrl` + `runCatching` + Validierung beim Speichern); der „unhealthy"-Pfad rollt
  sauber zurück.
- **Cache-Wechsel**: Offline-Caches werden prozessweit und generationsgesichert geleert, sodass
  Daten des alten Servers nicht beim neuen erscheinen.
- **BackendStore**: Ein absichtlich gelöschtes Passwort wird nicht mehr „wiederbelebt".
- **Datei-Viewer**: Lesen mit Obergrenze statt unbegrenztem `string()` (OOM vermieden).
- **Benachrichtigungen**: Actionable Prompts nutzen einen HIGH-Importance-Kanal (waren als
  Heads-up wirkungslos); Dedupe-Markierung wird bei Zustellfehler zurückgenommen.
- **Deep-Link**: monotoner Zähler, zweiter Tap auf dieselbe Session funktioniert wieder.
- **Share**: Ziel wird live gelesen (geteilter Inhalt landet nicht mehr im falschen Chat).
- **Polling**: Home-Statusstream und 5-s-Poll sind lifecycle-gebunden, der Chat-Poll pausiert im
  Hintergrund (Akku/Daten).
- **Provider-Katalog**: „nicht geladen" ≠ „nicht verfügbar" (`isKnownMissing`) — ein Send wird nicht
  mehr fälschlich abgebrochen.
- **Agenten**: Wire-`name` (Anzeigename) wird nicht mehr verworfen.
- **Semantik**: „Review"-Tab heißt jetzt „Session info"; ein fehlgeschlagener Session-Load sieht
  nicht mehr wie eine leere Liste aus; „Reconnecting" hat Vorrang vor „Thinking"; Server-Status
  sagt „Checking the server…"; Running/Retrying-Badges und Gruppen-Titel lokalisiert.

### Changed / Added
- **Archivieren wirkt jetzt**: V2 hat keinen Server-Archiv-Schreibpfad, daher wird lokal
  archiviert (mit Undo-Aktion und „Archived"-Filter inkl. Anzahl); der alte Aufruf tat nichts.
- **UX**: sichtbarer Aktions-Button (⋮) auch bei eigenen Nachrichten, Löschbestätigung für
  Backends auch in den Settings, sichtbares Label für den Guard-Filter, Suchfeld mit Ein-Tap-
  Leeren, 48-dp-Touchziele im Composer, filter-bewusster Leerzustand.
- **Verbesserungen**: „Close all tabs", Schnellumschaltung des Erscheinungsbilds, persistente
  Home-Filterchips, „Copy conversation" (Transkript), Anzahl im Archived-Chip.

## [1.2.1] - 2026-10-11

### Fixed
- **Home search field**: the two filter chips (Running / guarded) were squeezed into
  the same row as the search field, leaving it ~150 dp wide — the placeholder wrapped
  and the field read as an oversized card. The field now owns its row (compact 44 dp,
  single-line placeholder) and the filters sit on their own line.

## [1.2.0] - 2026-10-11

Deep-analysis pass: bugs/race conditions, semantics, GUI logic/UX, workflows.

### Fixed (Auswahl)
- **Paging ladder** no longer stops at the first oversized page (heavy sessions opened blank).
- **Optimistic echo** is rolled back on every early failure (phantom message + lost prompt).
- **HTTP error-body leaks** closed on all reply/rename/revert/message-list routes; `getFileContent` no longer shows error bodies as file content.
- **Permission replies** work through the canonical route (session lookup was never populated).
- **Provider catalog**: `invalidate()` is honoured during an in-flight fetch; a stale fetch can no longer publish after it.
- **Caches**: a slow disk read can no longer overwrite a fresher in-memory entry; message cache stores the real session id for search hits.
- **Stores** (backends, model visibility, recent models, settings) serialise read-modify-write; a keystore hiccup no longer deletes saved passwords/token.
- **SSE control events** (`sse.connected`) can no longer be evicted by a data storm; connection-level response leak fixed.
- **Streaming state**: bounded part map, `turnEnded` cleared by a new step, one "finished" notification per turn, retry status no longer flickers away.
- **Reload of the same session** keeps the running flag and live buffers.
- **Attachments**: atomic add off the main thread; base64 embed runs on IO with progress.
- **Lifecycle**: keep-alive service stops on task removal; deep-link intent consumed once; widget waits for store init; ClientLog no longer holds the Activity.
- **Semantics**: aborts no longer report success, failed compactions surface an error, `session.error` ends the "running" badge, per-question submit requires all answers, honest labels/messages (restore, permissions, questions, selection, guard) and German guard strings moved to resources.
- **GUI logic**: body-search results appear, error bubbles render without text, folder navigation no longer jumps to the top, Enter respects an upload, skills dialog reports failures.
- **UX**: discoverable per-message actions (⋮), confirmations for revert/project/backend deletion, empty-state CTA, "Running" filter, Esc stops a turn, copy session id.
- **Workflows**: backend switch is verified before it is applied, notification replies target the saved backend, a failed send retries on text OR attachment, rejected slash commands restore the typed line, settings export no longer reports false success, switch-backend cleanup is consistent, stale widget state is reset, OAuth-only providers no longer send a bogus key type.

## [1.1.1] - 2026-10-11

### Changed

- **Coordinated toolchain bump.** AGP 8.9.1 → 9.4.1 (now with built-in Kotlin,
  so the `org.jetbrains.kotlin.android` plugin is no longer applied), Gradle
  wrapper 8.11.1 → 9.8.1, Kotlin 2.0.20 → 2.4.21, KSP 2.0.20-1.0.25 → 2.3.12,
  Hilt 2.52 → 2.60.1, Compose BOM 2024.09.03 → 2026.09.00 (Compose 1.12.1,
  Material 3 1.4.0) and `compileSdk` 36 → 37.
- androidx libraries: `core-ktx` 1.19.1, `navigation-compose` 2.10.2,
  `hilt-navigation-compose` 1.4.0, `androidx.test.ext:junit` 1.3.0.
- kotlinx: `serialization-json` 1.11.0, `coroutines` 1.11.0.
- Networking: OkHttp 4.12.0 → 5.5.0 and Retrofit 2.11.0 → 3.0.0. The unused
  `okhttp-sse` dependency was dropped instead of bumped.
- `android.enableJetifier` was removed (Jetifier is gone in AGP 9).

### Fixed

- **Providers now match the server, the web UI and the TUI.** Settings →
  Providers listed only the _already active_ providers (~6 instead of ~230)
  because the catalog was built from `/api/provider`; it now uses the full
  `/api/integration` catalog, so there is something to connect again.
- **"Connected" is the server's own signal.** It was derived from "the provider
  has models", which hid an activated provider whose models were not indexed yet
  and could mark a never-authenticated provider as connected. It now follows
  `/api/provider` (the available set the TUI shows), falling back to integrations
  with a live connection.
- **Connection source is real.** The badge/Disconnect decision used the provider
  `activation` (`auto`/`enabled`) where the UI expects `env`/`api`, so
  env-authenticated providers (`nvidia`, `google`) never showed "Environment"
  and wrongly offered a Disconnect the server cannot perform. It is now derived
  from the integration's live `connections` (`env` → Environment,
  `credential` → API key, config-only → Config).
- **The provider catalog is refreshed when stale** (2 minutes) instead of being
  fetched once per process, so providers added, removed or authenticated in the
  TUI, the web UI or `opencode.json` appear without restarting the app.
- **Model identity survives slashes in a model id.** Providers such as Nvidia
  serve ids that carry their own namespace (`z-ai/glm-5.3`,
  `deepseek-ai/deepseek-v4.1-flash`). The client stripped everything before the
  first slash, which (a) made the "Manage models" visibility key disagree with
  the entry the web/TUI writes — so a model hidden there stayed visible here —
  and could collide two namespaces on one key, (b) resolved such a ref to a
  provider that does not exist (`z-ai`), and (c) made the "is this model
  available?" guard mis-match. Only a genuine provider prefix is stripped now and
  resolution is catalog-first (provider-qualified → exact model id → legacy tail
  match), consistently in the chat picker, "Manage models", Settings → Models
  and the send-time availability check.
- **The same model name is now correct per provider.** Two providers can offer the
  same model id with different windows (`qwen3.8-max`: 1,000,000 on
  `opencode-go` vs 262,144 on `opencode`, `minimax-m3`: 512,000 vs 1,000,000). The
  context-limit lookup matched on the model id alone, so the usage ring showed a
  percentage for an arbitrary provider — it now resolves the selected provider
  first, in the chat header, the composer chip and the context dialog.
- The model picker's "selected" check is provider-aware: a bare session ref
  ("deepseek-v4-flash") still highlights the right row, and when a model name is
  offered by several providers the active-model label is suffixed with the
  provider ("DeepSeek V4 Flash · DeepSeek") so the same name is unambiguous.
- Adapt to the newer APIs: `Modifier.sharedElement` now takes
  `sharedContentState`, and resource lookups inside composables use
  `LocalResources` / `rememberUpdatedState` to satisfy Compose's new
  configuration-aware lint check.

- **Starting a session in a project no longer fails silently.** `createSession`
  sent no directory when no project was resolved and the server then created the
  session in its OWN working directory (observed: `~/.config/opencode`) instead
  of the project, so the new session never appeared where the user was looking.
  It now refuses that case with a clear message, logs the attempt (directory,
  resulting session id) and surfaces failures to the user instead of only
  setting a quiet UI field.

- **The release (R8) build crashed on every start.** Room instantiates its
  generated `<Database>_Impl` class reflectively, and R8 kept the class name while
  dropping the no-arg constructor of `androidx.work.impl.WorkDatabase_Impl`
  (pulled in transitively by Glance's app widget). The app died in
  `androidx.startup.InitializationProvider` with "Failed to create an instance of
  androidx.work.impl.WorkDatabase", while the debug build was fine. Explicit
  Room/WorkManager keep rules fix it, and Android CI now also builds
  `assembleRelease` so a minify-only regression cannot ship unnoticed again.

- **Sessions show their real name.** The chat header stayed on the "Session"
  placeholder because `refreshSessionModel` copied only `model`/`agent` from the
  refreshed session. The server names a session from its first turn, so that title
  is now copied too (as is `directory`), and the placeholder reads "New session"
  until the name arrives.

## [1.0.0] - 2026-10-07

### Added

- Initial public release of the Android client for the OpenCode server.
- Live streaming chat over SSE with progressive Markdown and code highlighting.
- Inline tool-call rendering (shell, read, edit, diff) with consecutive-call
  collapsing.
- Permission prompts and agent questions, answerable in-app and from
  notification actions.
- Per-session provider/model/variant/agent selection, synced with the server.
- Provider catalog browsing and credential connect/disconnect.
- Review/Changes tab with branch badge and on-demand working-tree diff.
- Session list with project grouping, offline cache, older-message paging and
  history search.
- Foreground session service, home-screen widget and Quick Settings tile.
- Android share-target support for text and images.
- Multiple themes (default, Matrix, Aqua, Metal, Hello Kitty) and custom fonts.

### Changed

- **Session running state is now turn-scoped.** The composer's send/stop button
  stays on "stop" for the whole turn — including between the many steps of a
  tool-using turn — instead of flickering back to "send" at every step boundary.
  Step-level streaming is tracked separately so the caret still stops between
  steps.
- **Smoother streamed text.** The live bubble keeps its markdown renderer for the
  whole answer and parses incrementally (finished blocks are memoised, only the
  growing tail is re-parsed), so long answers no longer stutter or change style
  when they are persisted.
- Turn state re-syncs from the server's status endpoint on reconnect (in both
  directions) with a watchdog while the event stream is down.

### Removed

- Automatic in-memory log upload to a developer host. The app reports only a
  short, sanitized error summary to the user's own server.

### Security

- Release signing material (`keystore.properties`, `*.jks`) is git-ignored and
  documented via `keystore.properties.example`.
- No analytics or third-party telemetry.
- Credentials, cached conversation text and crash reports are excluded from
  cloud backup and device transfer.

[Unreleased]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.3...HEAD
[1.5.3]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.2...v1.5.3
[1.5.2]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.4.2...v1.5.0
[1.4.2]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.4.1...v1.4.2
[1.4.1]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.2.1...v1.3.0
[1.2.1]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases/tag/v1.0.0
