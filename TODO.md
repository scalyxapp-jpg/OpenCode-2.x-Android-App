# TUI-Parität — Stand nach 1.5.25 (Liste neu geprüft)

## Erledigt

- [x] Diff-Block mit Nummern-Leiste, ±-Tönung, Kontext (Quelle `metadata.files[].patch`)
- [x] Kürzungs-Wortlaute (`... output truncated ...`, `omitted N characters`, `(no output)`,
      `Full output saved to: <pfad>`)
- [x] Werkzeug-Namen wie die Referenz (`ui.tool.*`) — Chat, Live-Zeile, Benachrichtigung
- [x] ±-Statistik + Changes-Karte aus `metadata.files[]`, „N Changed files"
- [x] Fehlgeschlagener Aufruf: „Failed" + Meldung + „Copy error", Zeile startet offen
- [x] Großer Diff: „Diff too large to render" / „Limit: … Current: …" / „Render anyway"
- [x] Thinking ohne `[REDACTED]`, „Interrupted", keine Schritt-Trenner, „Session compacted"
- [x] **Eine** Einstellung „Show context lines" für Chat **und** Changes-Tab (1.5.25)
- [x] Dateiliste einer Runde faltet sich: „+N more files" / „Show less" (1.5.25)

## Durch Settings bereits abgedeckt (nichts gebaut)

- „Hide/Show steps" pro Turn → **Settings → Timeline detail: Compact/Advanced** (presetzt die drei
  Aufklapp-Schalter als Gruppe) + aufklappbare Zeilen
- „Hide non-diff lines" → Settings → **Show context lines** (wirkt in beiden Ansichten)
- „Expand all/Collapse all" → jede Zeile einzeln antippbar; Aufklapp-Standards in Settings
- Große Diffs → Deckel + „Render anyway" (1.5.24)

## Bewusst nicht gebaut (mit Begründung)

- **Split/Unified-Diff**: zwei Spalten auf 360 px sind unbrauchbar → App bleibt Unified
- **„Open file"**: Dateien liegen auf dem **Server**; ein Editor auf dem Handy zeigt nichts
- **Datei-Sidebar, Diff-Byte-Budget**: Desktop-Bedienung
- **Exit-Code**: kein Katalog-Schlüssel, kein Client liest `.exit` — die Referenz zeigt keinen

## Grundsatz

Wortlaute/Format aus dem Katalog der Referenz-Clients (`ui.*` im opencode-Bundle) bzw. vom Server.
Für die **Handy-Oberfläche** gilt zusätzlich: keine Desktop-Features, ein Bedienweg pro Sache,
jede Änderung mit Test, danach EIN Build + Release.
