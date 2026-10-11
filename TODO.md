# Auftragsplan — Runde 3

Vorab: Android-Gerät NICHT angeschlossen — keine adb-Schritte.
Compile/Build erst am Ende (vor der APK), nicht nach jedem Auftrag.

Bereits in Runde 1+2 behoben (NICHT erneut melden): Paging-Leiter, Echo-Rollback,
OkHttp-Leaks, Permission-Session-Lookup, ProviderDirectory-Invalidierung, Cache-Stale-Overwrite,
Store-RMW-Locks, SSE-Steuerkanal, partTypes-Deckel, turnEnded/step-start, NotifyDone einmal,
Same-Session-Reload, addAttachments, Lifecycle (onTaskRemoved/Deep-Link/Widget/ClientLog),
Semantik (abort≠success, compaction.failed, session.error, Form-Antworten nie leer,
isKnownMissing, Review→Session info), GUI (Suchfeld, Filter, Unread-Baseline, PullToRefresh,
Tool-Gruppen-IDs, Guard-Token-Draft, Splash-onDone, MoreVert auf eigenen Nachrichten,
Bestätigungen), Workflows (Backend-Verifikation vor Anwenden, Retry Text-oder-Anhang,
Slash-Restore, Archivieren lokal+Undo, Projekt-Rename-Override, Share-Verfügbarkeit,
Polling lifecycles), UX (Close all tabs, Appearance-Toggle, Filter persistiert,
Transkript kopieren, Archived-Count).

- [x] Auftrag 1: Bugs + Race-Conditions (Runde 3) — 15 Fixes
- [x] Auftrag 2: Semantik-Probleme / Unklarheiten — 6 Fixes
- [x] Auftrag 3: GUI-Logik + Usability — 8 Fixes
- [x] Auftrag 4: Workflows / Routinen — 6 Fixes
- [x] Auftrag 5: 5 Verbesserungen (Tab-State, Copy last answer, Projekt im Untertitel, Live-Reasoning-Setting, Archived-Tag)
- [x] Auftrag 6: Release-APK 1.4.0 (versionCode 7) + HTTP-Server auf 100.107.159.17:8899 (HTTP 200)
