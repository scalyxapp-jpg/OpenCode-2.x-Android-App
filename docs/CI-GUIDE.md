# CI-Guide — OpenCode Android

Werbegrafik / Icons / Illustrationen, die **exakt zur App passen** sollen, finden hier die
verbindlichen Werte. Alles ist aus dem **Code** abgeleitet (autoritativ, mit `Datei:Zeile`) und wo
möglich gegen eine **Messung am Gerät** geprüft (Methode und Vorbehalt in §12).

> Grundsatz: **Der Code ist die Wahrheit.** Wenn eine Grafik einem Wert hier widerspricht, ist die
> Grafik falsch — nicht die Doku.
>
> **Messbasis (§12):** SM-A176B, 1080 × 2340 px bei **450 dpi** → **1 dp = 2,8125 px**, Bildschirm
> **384 × 832 dp**. Gemessene Anker stehen mit „gemessen" markiert, alles andere kommt aus dem Code.

---

## 1. Markenkern

### 1.1 Logo-Marke („Terminal-Prompt")

| Eigenschaft                          | Wert                                      | Quelle                                    |
| ------------------------------------ | ----------------------------------------- | ----------------------------------------- |
| Adaptive-Icon-Fläche                 | **108 × 108 dp**                          | `res/mipmap-anydpi-v26/ic_launcher.xml`   |
| Vektor-Viewport                      | **512 × 512**                             | `res/drawable/ic_launcher_foreground.xml` |
| Hintergrund                          | **`#262626`** (Neutral-Grau, warm)        | `res/values/colors.xml`                   |
| Marke außen                          | **`#FFFFFF`**                             | ebd.                                      |
| Marke innen                          | **`#5A5858`**                             | ebd.                                      |
| Monochrom (Themed Icon, Android 13+) | dieselbe Foreground-Datei                 | `ic_launcher.xml`                         |
| Notification-Icon                    | **24 dp**, nur Alpha (`tint="#FFFFFFFF"`) | `res/drawable/ic_notification.xml`        |

**Aufbau der Marke:** ein Quadrat mit ausgeschnittenem Zentrum — außen die Kontur (weiß), innen
eine kleinere gefüllte Fläche (`#5A5858`), also ein **Prompt-/Terminal-Symbol**, keine Wortmarke.
Die Marke ist **rein geometrisch** (nur Geraden, rechte Winkel) — keine Rundungen, keine Verläufe,
keine Schatten.

**Schutzraum & Mindestgröße (Regel für neue Layouts):**

- Schutzraum um die Marke: **mindestens 12,5 % der Kantenlänge** (im 108-dp-Icon sind das 13,5 dp;
  die 512er-Datei entspricht dem 4,74-fachen, also ~64 Einheiten).
- Kleinste Verwendung: **16 dp** (z. B. Listen-Icon). Darunter ist das innere Quadrat nicht mehr
  erkennbar.
- Die Marke darf **nicht** verzerrt, gedreht, mit Schatten versehen oder eingefärbt werden außer
  mit einer Vollton-Farbe aus §3 (dann einfarbig).

### 1.2 Wortmarke

| Eigenschaft              | Wert                                                           | Quelle         |
| ------------------------ | -------------------------------------------------------------- | -------------- |
| Text                     | `OpenCode` (kein Leerzeichen, Binnenmajuskel)                  | `ui/Splash.kt` |
| Typo                     | `headlineMedium`, **FontWeight.Bold**                          | ebd.           |
| Letter-Spacing im Splash | 1 dp zwischen den Buchstaben (Stagger)                         | ebd.           |
| Farbe                    | `colorScheme.onBackground` (hell `#1A191D` / dunkel `#E6E4E8`) | ebd.           |

**Logo-Lockup (Splash, die verbindliche Referenz für Werbemotive):**

```
┌──────────────────────────────┐
│           96 dp              │   ← Kachel: shapes.large = 20 dp Radius,
│      ┌────────────┐          │     Fläche = colorScheme.primary
│      │  64 dp    │          │   ← Marke, getönt mit colorScheme.onPrimary
│      └────────────┘          │
│         18 dp Abstand        │
│        O p e n C o d e       │   ← Wortmarke (headlineMedium, Bold)
│         18 dp Abstand        │
│        ▬▬▬▬▬▬▬               │   ← Akzent-Sweep: 120 × 4 dp, Radius 6 dp,
└──────────────────────────────┘     Swoosh 48 dp darin
```

---

## 2. Farbwelt

Zwei Skalen bilden die Identität: **Wine** (Marke) und **Neutral** (alles Strukturelle).
Quelle: `ui/theme/Theme.kt`.

### 2.1 Wine-Skala (Marke)

| Token   | Hex       |     | Token   | Hex       |
| ------- | --------- | --- | ------- | --------- |
| Wine50  | `#FFF0F3` |     | Wine500 | `#C9365E` |
| Wine100 | `#FFD9E0` |     | Wine600 | `#A81E45` |
| Wine200 | `#FFB2C2` |     | Wine700 | `#800020` |
| Wine300 | `#F98AA3` |     | Wine800 | `#5E0018` |
| Wine400 | `#E85D7E` |     | Wine900 | `#3D000F` |

`#800020` (Wine700) ist das **Kernmarkenrot** des hellen Themes.

### 2.2 Neutral-Skala (Struktur)

`#FFFFFF` · `#FAF9FA` · `#F4F2F4` · `#E8E6E9` · `#D6D3D8` · `#A9A6AD` · `#78767E` · `#7D7B84`
· `#737179` · `#5A5860` · `#3F3D44` · `#2A282E` · `#1A191D` · `#111013`
(= Neutral0 … Neutral950)

### 2.3 Fehler

| Rolle            | Hell      | Dunkel    |
| ---------------- | --------- | --------- |
| error            | `#B3261E` | `#F2B8B5` |
| errorContainer   | `#F9DEDC` | `#8C1D18` |
| onErrorContainer | `#410E0B` | `#F9DEDC` |

### 2.4 Rollen-Zuordnung (was wofür)

| Rolle (Material)                 | Hell                    | Dunkel                | Verwendung in der App                                  |
| -------------------------------- | ----------------------- | --------------------- | ------------------------------------------------------ |
| `primary`                        | Wine700                 | Wine300               | Buttons, aktive Tabs, Links, Composer-Rand, Send-Knopf |
| `onPrimary`                      | `#FFFFFF`               | Wine900               | Text auf primär                                        |
| `primaryContainer`               | Wine100                 | Wine800               | ausgewählte Zeilen, „running"-Karten, Prompt-Chips     |
| `tertiary` / `tertiaryContainer` | Wine500 / Wine50        | Wine200 / Wine700     | „Needs you"-Karten, sekundäre Akzente                  |
| `background` / `surface`         | Neutral50               | Neutral950            | Screen-Hintergrund                                     |
| `surfaceContainerHighest`        | `#E2E0E3`               | `#302E34`             | **Werkzeug-Zeilen** (Tool-Rows)                        |
| `surfaceContainerHigh`           | Neutral200              | `#252429`             | Karten (Reasoning, Queue, Resume), Karten-Tiles        |
| `surfaceContainer`               | `#EFEDEF`               | `#1B1A1E`             | Zwischenebene                                          |
| `surfaceContainerLow/Lowest`     | Neutral100 / Neutral0   | `#161519` / `#0B0A0D` | Hintergründe gestapelter Flächen                       |
| `outline` / `outlineVariant`     | Neutral550 / Neutral300 | `#7D7B84` / `#45434A` | Trennlinien, Rahmen                                    |
| `onSurfaceVariant`               | Neutral600              | Neutral400            | Sekundärtext, Metadaten (Kosten, Zeit, Pfad)           |
| `error`                          | `#B3261E`               | `#F2B8B5`             | Fehlerzeilen, Stop/Diktat aktiv                        |
| `inverseSurface`                 | Neutral800              | `#E6E4E8`             | Snackbar                                               |

**Ebenen-Modell (wichtig für Grafiken, die auf Flächen liegen):** die App stapelt
`surfaceContainerLowest → Low → (default) → High → Highest`. Eine Grafik gehört **immer** auf genau
eine dieser Ebenen und nutzt **keinen eigenen** Hintergrund außer einer Farbe aus §2.3/§2.4.

**Kontrast-Regel:** Text auf Fläche ≥ **4,5:1** (WCAG AA). Wine700 auf Neutral50 und Wine300 auf
Neutral950 erfüllen das; Wine400/Wine500 sind **Akzent- und Flächenfarben für weißen Text**, nicht
für kleinen Text auf hell.

---

## 3. Typografie

Skala: `ui/theme/Type.kt` (`OpenCodeTypography`). UI-Font ist **wählbar** (System-Sans oder ein
Geräte-Font, `settings_ui_font`), Code-Font ist **Monospace** (`codeFont()`, `settings_code_font`)
— Grafiken mit Text müssen also mit **beliebiger Sans-Familie** funktionieren (keine feste
Schrift voraussetzen).

| Stil          | Größe | Line-Height | Letter-Spacing | Gewicht  | Typische Verwendung                   |
| ------------- | ----- | ----------- | -------------- | -------- | ------------------------------------- |
| displaySmall  | 32 sp | 40 sp       | −0,4 sp        | SemiBold | Leerzustände, große Zahlen            |
| headlineSmall | 24 sp | 32 sp       | −0,2 sp        | SemiBold | Screen-Titel                          |
| titleLarge    | 20 sp | 28 sp       | −0,1 sp        | SemiBold | Abschnittstitel                       |
| titleMedium   | 16 sp | 24 sp       | +0,1 sp        | SemiBold | Karten-Titel, Kennzahlen              |
| titleSmall    | 14 sp | 20 sp       | +0,1 sp        | Medium   | Abschnittsköpfe (z. B. „Running now") |
| bodyLarge     | 16 sp | 24 sp       | +0,15 sp       | Normal   | Fließtext                             |
| bodyMedium    | 14 sp | 21 sp       | +0,15 sp       | Normal   | Chat-Text, Zeilen                     |
| bodySmall     | 12 sp | 17 sp       | +0,2 sp        | Normal   | Werkzeug-Ausgabe, Diffs               |
| labelLarge    | 14 sp | 20 sp       | +0,1 sp        | Medium   | Buttons                               |
| labelMedium   | 12 sp | 16 sp       | +0,4 sp        | Medium   | Chips, Badges, Kennzahlen-Labels      |
| labelSmall    | 11 sp | 15 sp       | +0,4 sp        | Medium   | Metadaten (Kosten, Zeit, Status)      |

Regeln: **maximal zwei Gewichte** pro Grafik (Normal + Medium/SemiBold), negative Letter-Spacing
nur ab 20 sp, Code/Diffs **immer** Monospace und `bodySmall`/`labelSmall`.

---

## 4. Formen & Tiefe

`ui/theme/Type.kt` (`OpenCodeShapes`):

| Token        | Radius    | Verwendung                                  |
| ------------ | --------- | ------------------------------------------- |
| `extraSmall` | **6 dp**  | Badges, Sweep-Balken, kleine Chips          |
| `small`      | **10 dp** | Werkzeug-Zeilen, Listenkarten, Notiz-Karten |
| `medium`     | **14 dp** | Standard-Karten, Dialoge                    |
| `large`      | **20 dp** | große Karten, **Splash-Logo-Kachel**        |
| `extraLarge` | **28 dp** | **Composer-Panel**, Bottom-Sheets           |

**Kein Schatten als Stilelement.** Tiefe entsteht ausschließlich über die Container-Ebenen (§2.4)
und `surfaceTint`. Ausnahme: das Bottom-Sheet/Dialog ohne Elevation-Verlauf.

---

## 5. Abstände & Raster

`ui/theme/Dimens.kt` (`MaterialTheme.spacing`):

| Token         | Wert  | Verwendung                                         |
| ------------- | ----- | -------------------------------------------------- |
| `extraSmall`  | 4 dp  | Icon-zu-Text, Chip-Innenabstand                    |
| `small`       | 8 dp  | Karten-Innenabstand horizontal, Zeilenabstand      |
| `medium`      | 16 dp | **Screen-Rand** (`screenPadding`), Sektionsabstand |
| `large`       | 24 dp | große Sektionstrennung                             |
| `extraLarge`  | 32 dp | Seitenränder bei Hero-Flächen                      |
| `cardPadding` | 12 dp | Innenabstand von Karten                            |
| `listGap`     | 8 dp  | Abstand zwischen Listenzeilen                      |

**Raster: 4 dp.** Praktisch am häufigsten im Code: `2, 16, 4, 18, 40, 48, 14, 6, 20, 1` dp —
d. h. 1/2 dp sind reine Feinjustage (Rahmen, Trennlinien), alles andere liegt auf dem 4-dp-Raster.
**Neue Grafiken rasten auf 4 dp**, Innenabstände kommen aus obiger Tabelle (nicht aus Gefühl).

---

## 6. Bewegung

`ui/theme/Dimens.kt` (`Motion`): drei Federn, **keine** festen langen Kurven.

| Token          | Physik                                  | Verwendung                              |
| -------------- | --------------------------------------- | --------------------------------------- |
| `spatial()`    | Spring, no bouncy, stiffness medium-low | Größe/Position (Chevrons, Karten-Höhe)  |
| `expressive()` | Spring, **low bouncy**, medium-low      | Hero-/Aufmerksamkeitsmomente (Scale-In) |
| `effects()`    | Spring, no bouncy, stiffness medium     | Farbe/Alpha                             |

**Splash-Choreografie** (Vorlage für Werbe-Animationen, `ui/Splash.kt`):
Kachel-Pop `450 ms` (FastOutSlowIn) → Buchstaben-Stagger `220 ms` mit **55 ms Versatz** je Zeichen
→ Akzent-Sweep über `120 dp` mit `48 dp` Swoosh. Alles danach: ruhiges „Breathe".

Erlaubte Zusatz-Dauern im Splash: 220–450 ms. Länger als 500 ms wirkt in der App träge.

---

## 7. Komponenten-Metriken (für Grafik-Assets, die _in_ die App passen)

| Element                  | Wert                                                                                     | Quelle/Beleg                                                    |
| ------------------------ | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------- |
| **Touch-Ziel (Minimum)** | **48 × 48 dp**                                                                           | Composer/`IconButton`s, `minimumInteractiveComponentSize`       |
| Icon-Glyphe (Standard)   | **24 dp**                                                                                | Material-Default; alle Leisten-Icons                            |
| Icon-Glyphe (klein)      | **18 dp**                                                                                | Copy/Explain in Werkzeug-Zeilen                                 |
| Zeilen-Icon              | **16 dp**                                                                                | kleinste Verwendung der Marke                                   |
| Zeilen-Icon / Avatar (Liste) | **40 dp** (kompakt **28 dp**) | `ui/CommonWidgets.kt` |
| Inhalts-Maximalbreite (Tablet) | **840 dp** | `ChatScreen.kt` — darüber zentriert, nie gestreckt |
| Label-Kappung (Dateiname) | **140 dp** | `ChangesTab.kt` |
| Composer-Zeile           | **48 dp** je Knopf                                                                       | Mikrofon, Anhang, Send/Stop                                     |
| Composer-Panel           | Radius **28 dp**, Screenshot: ~1030 px Breite (≈ 375 dp)                                 | `shapes.extraLarge`                                             |
| Stop-Knopf (aktiv)       | **48 dp** Kreis, Glyphe ~16 dp                                                           | Composer                                                        |
| Karten                   | Radius 10/14 dp, Padding **12 dp**                                                       | `spacing.cardPadding`                                           |
| Listen-/Kartenabstand    | **8 dp**                                                                                 | `spacing.listGap`                                               |
| Chat-Dialog (max)        | **460 dp** Höhe                                                                          | `ChatDialogs.kt`                                                |
| Chip / Badge             | Radius 6 dp, Padding 4–8 dp, `labelSmall/Medium`                                         | Tool-Badges, Prompt-Chips                                       |
| Widget                   | min. **180 × 110 dp**, Ziel **3 × 2 Zellen**, Innenabstand **16 dp**, Abstände 8/4/12 dp | `res/xml/session_widget_info.xml`, `ui/widget/SessionWidget.kt` |
| Notification-Icon        | **24 dp**, nur Alpha                                                                     | `ic_notification.xml`                                           |
| Quick-Settings-Tile      | Standard-Systemkachel, Untertitel = Ambiente-Zeile                                       | `NewSessionTileService.kt`                                      |

---

## 8. Bildschirm-Inventar (Layout-Karten)

**Navigation:** Backends → **Home** → **Chat** (mit Tabs) · **Centre** · **Settings** —
Home/Centre/Settings als untere Navigation (`NavigationBar`), Chat als eigene Route.

### 8.1 Home (`ui/HomeScreen.kt`)

```
┌─────────────────────────────────────────────┐
│ TopAppBar  64 dp (M3)   Titel · Suche · ⋮   │
├─────────────────────────────────────────────┤
│ Projekt-Chips (horizontal, 8 dp Abstand)    │
├─────────────────────────────────────────────┤
│ Sitzungsliste                               │
│  ┌───────────────────────────────────────┐  │
│  │ Zeile: Icon/Avatar 40 dp (kompakt 28) │  │  ← Radius 10 dp
│  │        Metadaten (labelSmall)         │  │     Abstand 8 dp
│  └───────────────────────────────────────┘  │
│  …                                          │
├─────────────────────────────────────────────┤
│ NavigationBar  80 dp + Gesteninset          │
│  Home   ·   Centre   ·   Settings           │
└─────────────────────────────────────────────┘
```

### 8.2 Chat (`ui/ChatScreen.kt`)

```
┌─────────────────────────────────────────────┐
│ TopAppBar 64 dp: Zurück · Titel · Kontext % │
│              · ⋮                            │
├─────────────────────────────────────────────┤
│ TabRow 48 dp: Session | Session info | Ch…  │  ← Labels gemessen y 140,1–147,9 dp
├─────────────────────────────────────────────┤
│ Transkript (LazyColumn, reverseLayout)      │
│   Textblasen · Werkzeug-Zeilen (Radius 10)  │
│   Reason-/Queue-Karten (Radius 10)          │
│   Live-Bereich: „Waiting for the model…"    │
├─────────────────────────────────────────────┤
│ Composer (Radius 28 dp, 4 dp Abstand)       │
│  [Mic 48][＋ 48]  Eingabefeld … [Verlauf 40]│  ← Glyphen gemessen: Höhe 24,2 dp,
│  [Agent ▾] [Modell ▾] [Effort ▾]  [Send 48] │    Glyph-Lücke beidseitig 24 dp, Zeile y≈677 dp
└─────────────────────────────────────────────┘
```

### 8.3 Centre (`ui/centre/CentreScreen.kt`)

```
TopAppBar 64 dp: Titel · Aktualisieren · Export
├─ Continue                    (Resume-Zeilen: 3)
├─ What needs attention        (Insights, farbig nach Schwere)
├─ Running now                 (Zeilen + Stop, „… N more" aufklappbar)
├─ Needs you                   (Berechtigungen direkt beantwortbar)
├─ Today                       (4 Kennzahl-Tiles)
├─ Server & projects           (MCP · Skills · Plugins · Branches)
└─ Audit                       (Zeilen + Kopieren)
```

**Kennzahl-Tile** (Today): Radius 10 dp, Padding 8 dp, Wert `titleMedium` Bold, Label `labelSmall`
— 4 Stück je Zeile, jeweils `weight(1f)`.

### 8.4 Settings (`ui/settings/SettingsScreen.kt`)

Tabs (5): **General · Servers · Providers · Models · Extensions**.
Zeilenmuster: Titel (`bodyMedium`) + Untertitel (`bodySmall`, `onSurfaceVariant`) und Schalter
(48 dp) rechts; Abschnittsköpfe als `SectionHeader`.

### 8.5 Weitere Flächen

| Fläche                       | Beschreibung                                                                  |
| ---------------------------- | ----------------------------------------------------------------------------- |
| `BackendPickerScreen`        | Verbindung einrichten, 1 Karte je Backend                                     |
| Dialoge (`ChatDialogs.kt`)   | Journal, MCP, Skills, Prompt-Historie, Umbenennen, Löschen — max. 460 dp hoch |
| `RaceUi`                     | Vergleichskarte 2–3 Modelle                                                   |
| Changes-Tab                  | Dateibaum + Unified-Diff (`DiffBlock`), ±-Tönung                              |
| Widget / Tile / Notification | §7, gleiche Farben/Rollen wie in der App                                      |

---

## 9. Deko-Ebenen (subtile Hintergründe)

Diese vier Elemente sind Teil der visuellen Identität und **dürfen** in Werbemotiven zitiert werden
— aber nur mit ihren echten Werten:

| Effekt         | Werte                                                                                            | Ort                |
| -------------- | ------------------------------------------------------------------------------------------------ | ------------------ |
| **MetalSheen** | Pinsel `#C9D2DA` (dunkel) / `#4E5866` (hell), Alpha **0,09–0,10**, Abstand skaliert mit Viewport | Chat-Hintergrund   |
| **MatrixRain** | Kopf `#CCFFCC`, Schweif `#008F11`, sehr niedriges Alpha, langsam fallend                         | Chat-Hintergrund   |
| **HelloKitty** | Schleife Rot, Rouge-/Blassrosa, Nasen-Gelb für Funkeln                                           | Splash/Leerzustand |
| **AquaFish**   | Fisch-Silhouetten mit Y-Clamping                                                                 | Splash/Leerzustand |

**Regel:** Deko bleibt bei **Alpha ≤ 0,12**, bewegt sich **langsam** und liegt **hinter** Text —
Lesbarkeit schlägt Effekt. Keine zwei Deko-Ebenen gleichzeitig.

---

## 10. Checkliste für neue Grafiken

- [ ] Nur Farben aus §2 (Wine/Neutral/Error oder Rollen-Farben). Keine eigenen Markentöne.
- [ ] Raster **4 dp**; Innenabstände aus §5 (`cardPadding` 12, `small` 8, `medium` 16).
- [ ] Radien aus §4 (6/10/14/20/28) — **keine** Zwischenwerte.
- [ ] Logo: Schutzraum ≥ 12,5 %, Mindestgröße 16 dp, nicht verzerren/einfärben.
- [ ] Text: Stile aus §3, max. 2 Gewichte, Code immer Monospace.
- [ ] Kontrast ≥ 4,5:1 für Text; Akzentfarben (Wine400/500) nur für Flächen/weißen Text.
- [ ] Touch-Ziele ≥ 48 × 48 dp; Glyphen 24 dp (klein 18 dp).
- [ ] Keine Schatten, keine Verläufe (außer MetalSheen), keine 3D-Effekte, keine dünnen Serifen.
- [ ] Animation: Federn aus §6 oder Splash-Dauern (220–450 ms); nichts länger als 500 ms.
- [ ] Widget-Grafik: 180 × 110 dp, 16 dp Rand, 3 × 2 Zellen.
- [ ] Notification-Icon: 24 dp, **nur Alpha** (einfarbig, transparent).
- [ ] Dark **zuerst** gestalten (Hintergrund `#111013`/`#1B1A1E`), dann hell prüfen.
- [ ] Breite Grafiken: Inhaltsbereich max. **840 dp**, danach zentriert (Tablet-Regel).

---

## 11. Kachel-/Store-Formate (abgeleitet, nicht im Code)

| Format          | Größe                             | Hinweis                                                         |
| --------------- | --------------------------------- | --------------------------------------------------------------- |
| Play Store Icon | 512 × 512 px                      | Marke wie §1.1 auf `#262626`, Schutzraum 12,5 %                 |
| Feature-Grafik  | 1024 × 500 px                     | Marke links (16 dp-Raster-Rhythmus), Wortmarke `OpenCode` Bold  |
| Screenshots     | mind. 1080 × 2340 px              | Direkt aus der App; dunkles Theme zeigt die Identität am besten |
| Adaptive Icon   | 108 dp Fläche, 66 dp sichere Zone | Marke muss in der 66-dp-Kreiszone stehen                        |

## 12. Messung am Gerät (vollzogen)

Gemessen mit `adb` auf dem Referenzgerät **Samsung SM-A176B** (Android 17), App **3.8.3**:

| Basiswert | Wert |
| --- | --- |
| Auflösung | **1080 × 2340 px** (`wm size`) |
| Dichte | **450 dpi** (`wm density`) → **1 dp = 2,8125 px** |
| Bildschirm in dp | **384 × 832 dp** |
| Statusleiste | **≈ 100 px ≈ 35,6 dp** (Systeminset) |

> Die Dichte ist **nicht** die oft angenommene 2,75×: wer aus Pixeln rechnet, muss **2,8125** nutzen
> (sonst sind alle Werte ~2 % zu klein).

### 12.1 Gemessene Regionen (Chat, `Session`-Tab)

| Element | Gemessen | Deckt sich mit |
| --- | --- | --- |
| Tab-Beschriftungen („Session info", „Changes") | y **140,1–147,9 dp**, Textband 7,8 dp | `TabRow` (Material, 48 dp) direkt unter der TopAppBar |
| Composer-Glyphen (Mic, „+", Verlauf, Stop) | y **665,2–689,4 dp**, Höhe **24,2 dp** | §7: Glyphe 24 dp |
| Abstand der Composer-Ziele | Glyphen-**Lücke 24 dp** (Zentren 48 bzw. 52 dp) | links: 48 + **0** + 48; rechts: 40 + **4** + 48 |
| Mic / „+" horizontal | x **28,1–52,3** / **80,0–104,2** dp | Mic ist das **linke** Element (seit 3.8.2) |
| Composer-Zeile (Mitte) | **≈ 677 dp** von 832 dp | unteres Zehntel, über der Chip-Zeile |
| Eingabefeld-Text („Ask anything…") | y **668,8–685,5 dp** (16,7 dp) | `bodyLarge`/`bodyMedium` (16/14 sp) |

### 12.2 Gemessene Regionen (Home, Sitzungsliste)

| Element | Gemessen |
| --- | --- |
| Touch-Ziele Suchfeld / Filter-Chips („Running", „Archived") | **48,0 dp** hoch (Suchfeld 158,9 dp breit) |
| Sitzungszeilen-Abstand (Pitch) | **≈ 64,4 dp** (→ Karte ≈ 56 dp + `listGap` 8 dp) |
| Navigations-Beschriftungen (Home · Centre · Settings) | y **752,0–768,0 dp** |
| Sektionskopf („TODAY", „OLDER") | 13,2 dp Textband (`labelSmall`) |

### 12.3 Was aus dem Code kommt (weil nicht messbar)

Containerhöhen wie `TopAppBar` und `NavigationBar` werden von der App **nicht überschrieben** —
es gelten die Material-3-Defaults **64 dp** bzw. **80 dp**. Der Chat ergänzt nur
`WindowInsets.statusBars`. Ebenso sind **Radien** (28 dp Composer, 20 dp Splash-Kachel) aus dem Code
übernommen: eine Pixelmessung an Ecken ist unzuverlässig, der Token ist exakt.

### 12.4 Vorbehalt

`uiautomator dump` liefert **Ankerpositionen** (Text, Icons, beschriftete Ziele) und markierte
Touch-Ziele. Nicht jeder Compose-Container erscheint als eigener Knoten, deshalb wurden Regionen
über ihre Anker gemessen (z. B. Composer-Zeile über die vier Glyphen) und gegen die Code-Werte
geprüft — wo beides vorlag, stimmten sie überein.
