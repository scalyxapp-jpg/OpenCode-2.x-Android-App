# OpenCode Android — ab 2.0.0

## Was 2.0 ausmacht

1.5.x war ein **vollständiger Client**. 2.0 gibt der App eine eigene Ebene: sie _antwortet_,
wenn mehrere Agenten gleichzeitig arbeiten — auch wenn sie im Browser oder TUI gestartet wurden.

### Zentrale (Cockpit) — neuer Eintrag unten in der Navigation

- [x] **Läuft jetzt**: alle `busy`/`retry`-Sessions, laufende zuerst; **Stop** pro Zeile;
      **Alle stoppen** (Not-Aus, mit Rückfrage + ehrlicher Zahl danach)
- [x] **Braucht dich**: offene Berechtigungen, offene Fragen, wartende Postausgang-Sendungen
      über alle Sessions; Antippen öffnet die wartende Session
- [x] **Heute**: Kosten/Sessions/Prompts/Steps des Tages (Server-Aggregat)
- [x] **Audit**: letzte 12 Ereignisse, kompletter Export per Kopieren
- [x] Live aus dem globalen Event-Strom (`session.status`, derselbe Reducer wie die Liste) —
      **kein Polling**, keine zusätzliche Hintergrundlast
- [x] Ruhende Zeilen gekappt („… N more sessions on Home") — sonst begraben 50 Sessions die
      wichtigen Abschnitte (am Gerät gefunden)

### Audit-Protokoll (Settings → Audit log)

- [x] Protokolliert: Stoppen, Alle-stoppen, Freigaben/Ablehnungen, Revert, Fork, an den Agenten
      übergebene Entscheidungen
- [x] Lokal, neueste zuerst, Deckel 400, `commit()` auf IO (übersteht harten Stopp)
- [x] Der Schalter greift **im Store** — kein Aufrufort kann ihn vergessen; aus = nichts wird
      aufgezeichnet

### Gefixt unterwegs

- [x] `CentrePolicy.runningIds` sortiert selbst (Not-Aus-Reihenfolge hing vom Aufrufer ab)
- [x] Audit-Eintrag einer Berechtigung nach Protokollwert (`reject` vs. `once`/`always`) —
      sonst wäre „immer erlauben" als Ablehnung protokolliert worden
- [x] Bis 1.5.25: TUI-Parität (Diff, Wortlaute, Werkzeug-Namen, Kontext-Schalter, Datei-Faltung)

## Arbeitsweise (verbindlich ab hier)
- Tests NUR als betroffene Testklassen (`--tests FQCN`), nie die breiten Paket-Globs.
- Test/Build erst nach MEHREREN Schritten, nicht nach jeder Änderung.

## Verifikation

- **513 Tests grün** (betroffene Teilmengen; neu: `CentrePolicyTest`, 9 Fälle)
- **Gerät (2.0.0)**: Zentrale-Tab vorhanden, „Running now" mit der laufenden Session + Stop,
  Not-Aus-Rückfrage mit exaktem Wortlaut, alle vier Abschnitte nach einem Wisch erreichbar,
  `… 44 more sessions on Home`, 0 FATAL. Es wurde **nichts** gestoppt (die laufende Session
  führt dieses Gespräch).
- Nicht am Gerät nachweisbar: das _Aufzeichnen_ eines Audit-Ereignisses (dafür hätte ich eine
  laufende Session beenden oder eine Berechtigung beantworten müssen). Die Policy ist durch
  Tests gepinnt, der Store nutzt das am Gerät bewiesene Muster des Entscheidungsjournals.

## Distribution (v2.0.0)

```
GitHub:    v2.0.0 (Latest), CI grün, latest-Link HTTP 200
Tailscale: http://100.107.159.17:8899/OpenCode-Android-2.0.0.apk -> HTTP 200
sha256 (Tailscale = CI-Artefakt):
  b1755faa994ff61e67394e1253d9b9114262636f3daed0870c5344c2ae611846  ·  3.621.887 B
```

## Ideen für 2.1 (nicht gebaut, bewusst)

- Zeitleiste je Session (was lief wann, mit Kosten pro Turn) aus dem Audit-Protokoll
- „Braucht dich" auch als Glance-Fläche (Widget/Tile-Zähler)
- Postausgang-Einträge direkt aus der Zentrale senden
- Audit zusätzlich serverseitig spiegeln (ClientLog-Infrastruktur ist da)
- Worktrees (`/api/worktree`): isolierte Läufe pro Aufgabe — braucht eigene UX, deshalb offen
