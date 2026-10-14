# TUI-Parität — Stand nach 1.5.24

## Erledigt (jede Zeile mit Beleg aus Bundle oder Live-API)

- [x] Diff-Block mit Nummern-Leiste, ±-Tönung, Kontext (Patch-Quelle `metadata.files[].patch`)
- [x] Kürzungs-Wortlaute (`... output truncated ...`, `omitted N characters`, `(no output)`,
      `Full output saved to: <pfad>`)
- [x] Badge = Referenz-Namen (`ui.tool.*`: Edit/Read/Write/Patch/Shell/Grep/Glob/List/Task/Skill/
      Webfetch/Web Search) in Chat-Zeile, Live-Zeile, Benachrichtigung
- [x] ±-Statistik + Changes-Karte aus `metadata.files[]`, Kopfzeile „N Changed files"
- [x] Fehlgeschlagener Aufruf: Karte „Failed" + Meldung + „Copy error", Zeile startet offen
- [x] Großer Diff: „Diff too large to render" + „Limit: N changed lines. Current: M changed lines." + „Render anyway"
- [x] Thinking-Block ohne `[REDACTED]`; „Interrupted"; keine Schritt-Trenner (wie dort)
- [x] Compaction „Session compacted"; Ergebnistexte kommen vom Server

## Erforscht und bewusst nicht gebaut

- **Exit-Code:** Server liefert `metadata.exit`, aber kein `ui.*`-Schlüssel und keine Client-Stelle
  liest `.exit` → die Referenz zeigt keinen Exit-Code. Nichts nachzubauen.

## Noch offen (aus dem Katalog, nicht gebaut)

- „Hide steps"/„Show steps" pro Turn (`ui.sessionTurn.steps.*`)
- Diff-Ansicht umschalten Split/Unified (`ui.sessionReview.diffStyle.*`,
  `ui.sessionReviewV2.splitDiff|unifiedDiff`)
- „Hide non-diff lines" / „Show all lines" (`ui.sessionReviewV2.*`)
- „Expand all"/„Collapse all", „Open file", „Session changes"/„Git changes"/„Last turn changes"
  (`ui.sessionReview.title*`)
- „+N more files", „Show all"/„Show less", „Show more changes (N)" in der Diff-Liste
- Große-Diff-Byte-Budget, Datei-Sidebar (`ui.sessionReviewV2.toggleSidebar`)

## Grundsatz

Wortlaute/Format kommen aus dem Katalog der Referenz-Clients (`ui.*` im opencode-Bundle) bzw. vom
Server — nie aus der App. Beleg → Implementierung → Test → Geräteprüfung → EIN Build + Release.
