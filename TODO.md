# Auftragsplan — Runde 4

Vorab: Android-Gerät NICHT angeschlossen — keine adb-Schritte.
Compile/Build erst am Ende (vor der APK), nicht nach jedem Auftrag.

Bereits behoben (Runde 1–3 + Follow-ups) — NICHT erneut melden:
Paging-Leiter, Echo-Rollback, OkHttp-Body-Leaks (inkl. interrupt/prompt/log/oauth), Permission-Lookup,
ProviderDirectory (Invalidierung + Inflight-nicht-stale), Cache-Generation/Stale-Overwrite,
Store-RMW-Locks + Keystore-Preserve, SSE-Steuerkanal, partTypes-Deckel, turnEnded/step-start,
NotifyDone, Session-Fencing (Listen + messagesJob), status-idle/isCompacting, Home-Status-Merge,
Widget-Spiegelung, Deep-Link/monotone Signale, Share-Ziel/Verfügbarkeit, Polling-Lifecycle,
IME-Insets, ACTION_SEND_MULTIPLE, Notifier-Dedupe/Unmark/Permissions-Gate, Android-16-Check im CI,
Semantik (abort≠success, compaction.failed, session.error, isKnownMissing, Review→Session info,
Restore-Label, Usage-Prozent, Archived-Count, Doku), GUI (Suchfeld, Filter, Unread-Baseline,
PullToRefresh, Tool-Gruppen-ID, Guard-Aktion, doppelte Frage-Karte, statusError einmal,
IME-Send, Tagesgruppierung, Mention-Picker, Picker-Clear, Token-Gruppierung), Workflows
(Backend-Verifikation, Retry-Flag, Slash-Restore, Archivieren lokal+Undo, Projekt-Rename-Override,
Form-Antworten nie leer, MCP-Pro-Server-Job, Datei-Browser-Fencing, Provider-Disconnect-Feedback,
Close-all-Tabs-Navigation), UX (Close all tabs, Appearance-Toggle, Filter persistiert, Transkript,
Archived-Count/Tag, Tab-State, Copy-last-answer, Projekt im Untertitel, Reasoning-Setting).
Folgeaufträge: Font-Auswahllisten (1.4.1), Projektliste = eigene Auswahl + kanonische Pfade (1.4.2).

- [x] Auftrag 1: Bugs + Race-Conditions (Runde 4) — 12 Fixes
- [x] Auftrag 2: Semantik-Probleme / Unklarheiten — 5 Fixes
- [x] Auftrag 3: GUI-Logik + Usability — 7 Fixes
- [x] Auftrag 4: Workflows / Routinen — 5 Fixes
- [x] Auftrag 5: 5 Verbesserungen (Copy session dir/project path/server URL/error, Anhangs-Zusammenfassung)
- [x] Auftrag 6: Release-APK 1.5.0 (versionCode 10) + HTTP-Server auf 100.107.159.17:8899 (HTTP 200)
