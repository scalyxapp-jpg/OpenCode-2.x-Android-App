# Auftragsplan — Runde 2

Vorab: Android-Gerät NICHT angeschlossen — keine adb-Schritte.
Compile/Build erst am Ende (vor der APK), nicht nach jedem Auftrag.
Recherche (Web) für Best Practices wird genutzt, wo Entscheidungen anstehen.

Stand Runde 1 (bereits behoben — NICHT erneut melden): Paging-Leiter, Optimistic-Echo-Rollback,
OkHttp-Fehler-Body-Leaks, Permission-Reply-Session-Lookup, ProviderDirectory-Invalidate,
Cache-Stale-Overwrite, Store-RMW-Locks, SSE-Steuer-Event-Verdrängung, partTypes-Deckel,
turnEnded/step-start, NotifyDone einmal pro Turn, Same-Session-Reload, addAttachments atomar,
Service-onTaskRemoved, Deep-Link-Konsum, Widget-Ready-Gate, ClientLog-Leak, SessionError-Badge,
Revert-/Projekt-/Backend-Bestätigungen, Guard-Strings, Suchfeld-Layout (1.2.1).

- [x] Auftrag 1: Bugs + Race-Conditions (Runde 2) — 21 Fixes (State/IO/Plattform)
- [x] Auftrag 2: Semantik-Probleme / Unklarheiten — 12 Fixes
- [x] Auftrag 3: GUI-Logik + Usability — 13 Fixes
- [x] Auftrag 4: Workflows / Routinen — 7 Fixes
- [x] Auftrag 5: 5 Verbesserungen (Close all tabs, Appearance-Toggle, Filter persistieren, Transkript kopieren, Archived-Count + filter-bewusster Leerzustand)
- [x] Auftrag 6: Release-APK 1.3.0 (versionCode 6) + HTTP-Server auf 100.107.159.17:8899 (HTTP 200)
