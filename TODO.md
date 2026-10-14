# TUI-Parität (Fortsetzung nach 1.5.22) — abgeschlossen in 1.5.23

- [x]   1. Werkzeug-Zeilenkopf: Badge "Edit/Read/Search/…" statt "Called `edit`"; Pfad + ±-Suffix
- [x]   2. Shell-Ausgabe: "(no output)", "...output truncated... / Full output saved to: <pfad>"
- [x]   3. Thinking-Block: `[REDACTED]`-Marker raus (wie TUI); keine Schritt-Trenner (wie TUI);
       "Interrupted" bei Abbruch bestätigt
- [x]   4. Changes-Karte: Daten aus `metadata.files[]`, Kopfzeile über das vorhandene Plural
       ("N changed files") statt hartkodiertem "N Files Changed"
- [x]   5. Tests 500 grün, Doku, EIN Build + Release v1.5.23 + Tailscale

## Grundsatz

Wortlaute/Format kommen aus der TUI bzw. vom Server (opencode-Bundle / Live-API), nicht aus der App.
Jeder Punkt: Beleg → Implementierung → Test → Geräteprüfung.

## Nächste offene Paritätspunkte (falls fortgesetzt)

- Ergebnistext der Tools wird serverseitig geliefert („Edited … (1 replacement)") — bleibt 1:1.
- Exit-Code einer Shell (`metadata.exit`) wird noch nicht angezeigt; TUI-Farblogik nicht
  nachgewiesen → erst belegen, dann bauen.
