# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.8.9] - 2026-10-17

### Fixed

**Haptik war abgespielt, aber nicht spürbar — zweite Ursache gefunden.** Nach 3.8.8 stand im
Vibrator-Dump `played: Step=30ms(amplitude=1.00)`, also **wirklich abgespielt** — nur gefühlt wurde
nichts. Der Grund steht im selben Eintrag: **`usage: TOUCH`**. Genau diese Kategorie skaliert das
Gerät mit dem **„Touch"-Intensitätsregler**, und der gehört zu dem Schalter, der auf deinem Gerät aus
ist (`haptic_feedback_enabled = 0`). Eine Rückmeldung, die ausdrücklich angefordert wurde, darf nicht
von einer Einstellung über *Touch-Feedback* auf null skaliert werden.

**Fix:**
- **Andere Nutzungskategorie:** `VibrationAttributes.USAGE_PHYSICAL_EMULATION` („Vibration, die einen
  physischen Knopf nachahmt") statt `USAGE_TOUCH` (ab API 30).
- **Gerätetaugliche Effekte:** `VibrationEffect.createPredefined(EFFECT_CLICK / EFFECT_TICK)` statt
  selbstgebauter Wellenform — die sind auf den Aktuator des Geräts abgestimmt und spürbar besser;
  Fallback auf eine Einmal-Vibration (45 ms/255 bzw. 20 ms/150) unter API 29.
- Der Impuls ist damit kräftiger (45 ms statt 30 ms) und klar unterscheidbar (klick vs. tick).

**Ehrliche Grenze:** bei „Nicht stören" mit stummgeschalteter Vibration kann die Plattform weiterhin
nichts abspielen — das ist Systemverhalten, nicht die App.

### Fixed

**Das letzte Wort wurde abgeschnitten („dreimal hallo" → das dritte fehlte).** Gemessen war die
Ursache: Der Erkenner beendet bei jeder Pause die Sitzung (Samsung-SODA, `AMBIENT_ONESHOT`), und
meine Fortsetzung **erzeugte den Erkenner neu** — im Log jedes Mal `Initialize Soda` +
`blockingReconnect`, also ~300 ms Aufbau, in denen das Mikrofon zu ist. Das dritte „hallo" fiel
genau in dieses Fenster.

Jetzt wird die **bestehende Instanz weiterverwendet** (`startListening()` erneut, kein Neuaufbau) —
nur wenn das fehlschlägt, greift der bisherige Weg mit frischem Erkenner. Damit sollte die Lücke von
~300 ms auf wenige Millisekunden fallen; das ist am nächsten Log direkt messbar (die
`Initialize Soda`-Zeilen zwischen den Chunks müssen verschwinden).

### Offen (Gerät war während der Änderung nicht verbunden)

- **Am Gerät zu prüfen:** (a) Haptik spürbar? (b) Lücke zwischen den Chunks kleiner?
  Prüfbefehle: `dumpsys vibrator_manager` (Abschnitt „Recent vibrations", `played:` und `usage:`)
  und der Vergleich der `SodaSpeechRecognizer`-Zeilen zwischen zwei `chunk of N chars`-Einträgen.

## [3.8.8] - 2026-10-17

### Fixed

**Haptik am Aufnahme-Knopf war wirkungslos — mein „Beweis" war falsch.** Ich hatte im
Vibrator-Log nur den **Aufruf** gesehen und dabei zwei entscheidende Felder überlesen:

```
| ignored_for_settings | … | reason: performHapticFeedback(constant=0): ViewRootImpl#performHapticFeedback | played: null
```

`played: null` heißt: die Anfrage wurde protokolliert und **nichts abgespielt**. Dazu die Ursachen,
beide am Gerät gemessen:

| Befund | Folge |
| --- | --- |
| `settings get system haptic_feedback_enabled` = **0** | Das System-Touch-Feedback ist **aus**; `performHapticFeedback` wird mit `ignored_for_settings` verworfen. |
| `android.permission.VIBRATE` **fehlte** im Manifest | Ein direkter Vibrator-Aufruf wäre wirkungslos geblieben. |

**Fix:** neuer Helfer `util/Haptics` — der **Vibrator wird direkt** benutzt
(`VibrationEffect.createOneShot`), also **unabhängig** von dieser Systemeinstellung; die
`VIBRATE`-Permission ist ergänzt. Zwei klar unterscheidbare Impulse:

- **Drücken:** 30 ms, Amplitude 255 (kräftig)
- **Loslassen:** 15 ms, Amplitude 140 (leicht)

Ohne Vibrator fällt der Helfer auf `performHapticFeedback` zurück.

**Am Gerät bewiesen** (`dumpsys vibrator_manager`, Abschnitt „Recent vibrations", `played:` ist
gefüllt — vorher stand dort `null`):

```
01:28:35.710 | usage: TOUCH | com.opencode.android | played: Step=30ms(amplitude=1.00)   ← Drücken
01:28:37.182 | usage: TOUCH | com.opencode.android | played: Step=15ms(amplitude=0.55)   ← Loslassen
```

Genau die gesetzten Werte (255/255 = 1.00 und 140/255 = 0.55), 1,47 s auseinander = die gehaltene
Dauer. Zwei Hinweise zur Prüfmethode: der Dump hat **zwei** Abschnitte — die alten
`performHapticFeedback`-**Anfragen** (mit `played: null`) und die tatsächlich **abgespielten**
Vibrationen; mein erster Blick hatte nur den Anfrage-Abschnitt gesehen. Die beiden anderen
Composer-Knöpfe (Queue, Senden/Stop) nutzen denselben Helfer, damit es nur eine Quelle gibt.
Ehrliche Grenze: bei aktivem „Nicht stören" mit abgeschalteter Vibration kann die Plattform
weiterhin stumm schalten — das ist dann Systemverhalten.

### Fixed (aus derselben Runde, durch den Gerätetest gefunden)

**Diktat-Chunks wurden einzeln groß geschrieben.** Dein diktierter Text lautete „…ein Test **Du**
wartest jetzt…" — jeder Chunk wurde für sich poliert und dabei groß geschrieben. Jetzt entscheidet
`DictationPolicy.continuesSentence` gegen den **aktuellen** Feldtext: läuft der Satz weiter (kein
`.`, `!`, `?`, `:`, kein Zeilenumbruch), bleibt der Chunk-Anfang klein. Da diese Entscheidung den
aktuellen Text braucht, wandert sie ins ViewModel (`appendDictated`).

**Testfund:** `continuesSentence` nutzte `trimEnd()` und entfernte damit auch den **Zeilenumbruch** —
Text, der mit „\n" endet, galt fälschlich als fortlaufender Satz. Jetzt werden nur Leerzeichen/Tabs
getrimmt.

### Added (tests)

- `DictationPolicyTest` (10, +1): Satzfortsetzung wird nicht groß geschrieben, am Satzanfang schon;
  `continuesSentence` für leer/Leerzeichen/offen/`.`/`?`/`:`/Zeilenumbruch.
- Betroffen geprüft (nur diese Klassen): **23 Tests, 0 Fehler**.

### Verified am Gerät (aus deinem Testdiktat)

- Ein Halten, **drei Chunks (22 + 47 + 13 = 82 Zeichen)** inklusive Pausen — im Composer stand
  danach der durchgehende Satz „Hallo das ist ein Test … bis ich fertig gesprochen habe …".
- Die Mikrofon-Lücke nach einer Pause sank von **890 ms auf ~90 ms** (drei kurze Versuche
  120/250/500 ms statt einer 350-ms-Wartezeit).
- **Keine** Fehlermeldung, **0 FATAL**.

## [3.8.7] - 2026-10-17

### Fixed

**Nach der Sprechpause wurde nichts mehr aufgezeichnet — Ursache aus der Geräte-Zeitachse bewiesen.**

Der Log (3.8.6) zeigt den ganzen Ablauf einer gehaltenen Aufnahme:

```
00:47:45.734  SodaSpeechRecognizer: #handleFinalResult: 1 hyp      ← Erkenner beendet SELBST (Pause)
00:47:46.191  RecognitionService#onEndOfSpeech
00:47:46.210  RecognitionService#onMicrophoneCloseRequested        ← MIKROFON ZU
00:47:46.649  App: raw=11  → retry in 350 ms
00:47:47.097  RecognitionService#onMicrophoneOpened                ← erst 0,89 s später wieder offen
00:47:48.027  RecognitionService#onStartOfSpeech                   ← hört erst jetzt wieder
```

Drei Erkenntnisse:

1. **Die Stille-Schwellen (60 s) werden ignoriert.** Der Erkenner läuft als
   `applicationDomain: AMBIENT_ONESHOT` — Samsung-`SODA` ist **Einzel-Äußerung**: er beendet die
   Sitzung bei jeder Pause von selbst. Meine Chunk-Fortsetzung war deshalb richtig, aber:
2. **Die Lücke kostet Sprache.** Zwischen „Mikrofon zu" und „wieder offen" lagen **~0,9 s**
   (Engine-Teardown + 350 ms Wartezeit + Engine-Setup). Genau dort verschwanden die Wörter nach der
   Pause.
3. **`raw 11` ist der Reconnect-Zustand**, nicht der Fehler (schon in 3.8.6 behandelt).

**Fixes:**

- **Mikrofon früher zurückholen:** statt **einer** 350-ms-Wartezeit jetzt **drei kurze Versuche
  (120 / 250 / 500 ms)** — sobald die Engine bereit ist, hört sie wieder; jede eingesparte
  Millisekunde ist gerettete Sprache. Weiterhin nur, solange derselbe Versuch läuft **und** der
  Knopf gehalten wird (Push-to-Talk bleibt unangetastet).
- **Online-Modell explizit anfordern:** `EXTRA_PREFER_OFFLINE` wird jetzt **immer** gesendet (und
  ist `false`, solange „Dictate on-device only" aus ist). Das On-Device-SODA ist bauartbedingt
  Einzel-Äußerung; das Online-Modell hält eine lange, pausenreiche Diktat-Sitzung in **einer**
  Sitzung durch. Ob die Engine den Hinweis befolgt, entscheidet das Gerät — der Log sagt es.

### Added

- **Haptisches Feedback am Aufnahme-Knopf.** Beim Drücken/Halten kommt ein **kräftiger Impuls**
  (`HapticFeedbackType.LongPress`, wie an den anderen Composer-Knöpfen), beim Loslassen ein
  **leichterer Tick** (`TextHandleMove`). Beim Diktieren hält man den Knopf mit dem Daumen und liest
  selten auf den Bildschirm — so ist „Aufnahme läuft" und „Aufnahme beendet" auch ohne Blick
  unterscheidbar. Die Rückmeldung kommt nur beim tatsächlichen Drücken (kein Auto-Aufnehmen).

### Verified am Gerät

- **Haptik:** der Vibrator-Dienst protokolliert beide Impulse unseres Pakets —
  `performHapticFeedback(constant=0)` beim Drücken und `(constant=9)` beim Loslassen, 1,5 s
  auseinander (die simulierte Haltedauer) ✓
- **Online-Hinweis wird von Samsungs Engine ignoriert:** im Log steht nach dem expliziten
  `EXTRA_PREFER_OFFLINE=false` weiterhin `SodaSpeechRecognizer: Offline recognizer - start
  detection` (`AMBIENT_ONESHOT`). Die Einzel-Äußerung und damit die Lücke bei Pausen sind hier
  **Geräteverhalten**, nicht App-Verhalten; die drei kurzen Wiederholversuche holen das Mikrofon aber
  so früh zurück, wie die Engine es zulässt (erster Versuch nach 120 ms statt 350 ms).

### Ehrlich offen

- **Pausen-Verhalten mit echter Sprache** ist noch nicht verifiziert (dafür braucht es Sprache).
  Falls die Lücke weiter stört: in den **Systemeinstellungen** den Spracherkennungsdienst wechseln
  (Google statt Samsung) — dessen Online-Modell hält eine lange Aufnahme in einer Sitzung durch.

## [3.8.6] - 2026-10-17

### Fixed

**„speech recognition failed" — Ursache aus dem Gerätelog bewiesen.** Nach dem Einbau der
Immer-an-Diagnose (3.8.3) stand der Fehler endlich schwarz auf weiß:

```
10-17 00:30:45  W OpenCodeDictation: failure=Failed raw=11 held=true deliveredChars=0 partialChars=0
10-17 00:30:49  RecognitionService#logStartListening ... locale: de-CH
                SodaSpeechRecognizer: Offline recognizer - start listening
                SodaSpeechRecognizer: SodaDetectionHandler.blockingReconnect
                … onStartOfSpeech → onStopListening → „Audio process finished, transcription completed"
                → handleFinalResult: 3 hyp   →  Ende, OHNE Fehlerzeile
```

Zwei Erkenntnisse daraus:

1. **`raw = 11` liegt außerhalb der dokumentierten AOSP-Codes (1–9)** — ein Samsung-eigener Code
   (`ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`). Der Erkenner stand noch auf `blockingReconnect`
   (Verbindung/Sprachpaket), als der Start kam.
2. **4 Sekunden später lief derselbe Aufruf fehlerfrei durch.** Das ist also ein **transienter
   Zustand**, kein Fehler — und meine Wiederholung griff nur bei `Busy`/`Client`, bei unbekannten
   Codes lief sie ins Leere und zeigte die Meldung.

**Fix:** `SpeechErrorPolicy.isRetryable` (getestet) entscheidet, was wiederholt wird — **jeder
transiente oder unbekannte Code** (`Busy`, `Network`, `Failed` inkl. OEM-Codes), **nicht**
`NoSpeech` (nichts zu wiederholen), `Permission` (Nutzer muss handeln) und `Unavailable` (kein
Erkenner vorhanden). Die Wiederholung läuft **verzögert (350 ms)** über den Main-Handler, damit die
Engine den Reconnect abschließen kann, und startet **nur**, wenn derselbe Versuch noch läuft **und
der Knopf noch gehalten wird** — die Push-to-Talk-Regel bleibt unangetastet. Jeder Fehler und jede
Wiederholung wird mit Rohcode protokolliert.

### Verified am Gerät (3.8.5, aus dem Log)

- Diktat **funktioniert** grundsätzlich: `onStartOfSpeech` → `onStopListening` → „Audio process
  finished, transcription completed" → `handleFinalResult: 3 hyp` — die Erkennung liefert Text.
- **Kein** `error 13` mehr (Sprachpaket `de_CH` wird zugewiesen, SODA initialisiert).
- **Keine** anderen App-Fehler, **0 FATAL**.
- Getippter Text im Composer („Test:") **blieb** während eines Diktat-Versuchs erhalten.
- **Der neue Wiederholungspfad selbst ist am Gerät nachgestellt** (3.8.6): schnelles Doppel-Halten
  provoziert genau den Zustand „Engine verbindet noch" und damit `raw = 11`:

```
00:35:51.595  failure=Failed raw=11 held=true
00:35:51.595  retrying once after Failed (raw 11) in 350ms
00:35:52.387  failure=NoSpeech raw=7 held=false
```

  → die Wiederholung greift, **keine** Meldung im UI.

### Added (tests)

- `SpeechErrorPolicyTest` (7, +1): transiente und unbekannte Codes sind wiederholbar, `NoSpeech` /
  `Permission` / `Unavailable` nicht.
- Betroffen geprüft (nur diese Klassen): **22 Tests, 0 Fehler**.

## [3.8.5] - 2026-10-17

### Fixed

**Der Abstand zwischen Mikrofon und Anhang war größer als rechts.** Gemeldet mit Screenshot. Die
Ursache war rechnerisch eindeutig — und die frühere Messung am Gerät bestätigt sie:

| Gruppe | Aufbau | Glyph-Lücke | gemessen |
| --- | --- | --- | --- |
| links (vorher) | Mic **48 dp** + 4 dp + „+" **48 dp** | 12 + 4 + 12 = **28 dp** | 27,7 dp |
| rechts | Verlauf **40 dp** + 4 dp + Senden **48 dp** | 8 + 4 + 12 = **24 dp** | 23,8 dp |

Die rechte Gruppe nutzt **40-dp**-Boxen für Verlauf/Queue, die linken sind 48 dp (Push-to-Talk-Ziel).
Beides in 48 dp zu lassen und nur den Abstand zu verkleinern trifft es exakt: Mikrofon und Anhang
sitzen jetzt als **eine Gruppe mit 0 dp** Abstand → Glyph-Lücke **24 dp**, identisch zu rechts.

Bewusst **nicht** die Mikrofon-Box verkleinert: 48 dp ist das Plattform-Minimum für Touch-Ziele und
war in der CI-Doku als Regel festgeschrieben (Diktieren wird oft mit dem Daumen gehalten).

### Added (tests)

- Keine neuen Unit-Tests: eine Layout-Änderung ist so nicht sinnvoll testbar. **Beleg ist die
  Rechnung oben plus die frühere Gerätemessung** (27,7 vs. 23,8 dp) — nach dem Installieren mit einem
  Blick zu prüfen.

## [3.8.4] - 2026-10-17

### Fixed

**Drei Ursachen — alle im Code gefunden, nicht am Gerät geraten** (das Gerät war während der Arbeit
nicht verbunden; die Belege sind Code-Analyse + Tests).

1. **„speech recognition failed" trotz erfolgreichem Diktat.** Beim Weiteraufnehmen (mein Fix aus
   3.8.3) wird der alte Erkenner *innerhalb* seines eigenen `onResults` zerstört. Das Zerstoeren
   erzeugt beim Erkenner `ERROR_CLIENT` — und weil `begin()` die Generation **nicht** hochzählte,
   zählte der neue Versuch diesen Fehler **als seinen eigenen**, `delivered` war noch `false`, also
   lief erst der Wiederholungsversuch und dann die Meldung. Jetzt zählt **jede Sub-Session** eine
   eigene Generation (`begin()`), und **sobald irgendetwas zugestellt wurde** (Chunk oder
   Teilergebnis), wird **nie** mehr eine Meldung gezeigt — sie wäre nachweislich falsch, weil der
   Nutzer seinen Text sieht.
2. **Pausen schnitten das Diktat ab.** Nach dem Entfernen der alten 850-ms-Schwellen galten die
   Plattform-Defaults (~1–1,5 s) — die beenden die Äußerung **bei einer normalen Sprechpause**. Der
   `RecognizerIntent`-Vertrag hat genau dafür die Schwellen: sie werden jetzt mit **60 000 ms**
   gesendet, also „beende das nie von selbst". Damit ist das geforderte Verhalten erfüllt: **Pausen
   sind egal, aufgenommen wird bis zum Loslassen**; die Aufnahme beendet nur `stopListening()` beim
   Loslassen. Die Chunk-Fortsetzung bleibt als Sicherheitsnetz für Engines, die den Hinweis
   ignorieren.
3. **Der Composer-Text wurde überschrieben statt ergänzt.** Angehängt wurde in der Oberfläche gegen
   den **eingefrorenen** `inputText` der Komposition. Bei mehreren Chunks (die es seit 3.8.3 gibt)
   startete jeder Anhang beim **alten** Text — der vorherige Chunk war damit verloren, was wie
   „Text wurde geleert" aussieht. Neu: `ChatViewModel.appendInput(text)` liest **immer den aktuellen**
   Text (`DictationPolicy.appendFinal`), also ist jeder Chunk additiv, egal wie viele während des
   Haltens kommen. `ComposerActions.onAppendInput` reicht das durch.

### Changed

- Alle Diktat-Diagnosen laufen jetzt über **`DictationLog`** (immer aktiv, Tag `OpenCodeDictation`).
  In `SpeechInput` standen noch drei `AppLog`-Aufrufe, die im Release **nichts** geschrieben hätten —
  genau der Fehler, der die Diagnose beim letzten Mal wertlos machte.

### Added (tests)

- `SpeechRequestPolicyTest` (5): die Stille-Schwellen müssen gesendet werden **und lang** sein
  (`SILENCE_NEVER_ENDS_MS` > 30 s), damit keine Pause das Diktat beendet.
- Betroffen geprüft (nur diese Klassen): **21 Tests, 0 Fehler**.

### Offen

- **Geräteprüfung fehlt** (kein `adb`-Gerät während der Änderung). Zu prüfen: langes Diktat **mit
  Pausen** bis zum Loslassen, kein Verlust vorherigen Composer-Textes, keine Fehlermeldung mehr.

## [3.8.3] - 2026-10-15

### Fixed

**Die letzten Worte waren abgeschnitten.** Ursache: Erkenner beenden eine Äußerung **selbst** an
ihrer eigenen Stille-Schwelle — *während* der Knopf noch gehalten wird. Bisher nahm die App dieses
Ergebnis entgegen und hörte auf: alles, was danach gesprochen wurde, war verloren. Jetzt wird ein
solches Ergebnis **eingefügt und die Aufnahme läuft weiter, solange der Finger auf dem Knopf
bleibt** (`onChunk`). Push-to-Talk bleibt unangetastet — es wird nur weiter aufgenommen, während
gehalten wird. Der Test pinnt die Rettungsregel.

**„speech recognition failed" trotz erkannter Worte.** Scheitert der Erkenner am Ende, hat er
meist **den größten Teil schon gehört**. Bisher wurde das weggeworfen und eine Meldung gezeigt —
das Schlechteste aus beidem. Neu (`SpeechErrorPolicy.shouldRescuePartial`): Ist ein Teilergebnis da
und wurde noch nichts eingefügt, wird **das Teilergebnis gerettet** und **keine** Meldung gezeigt.
Eine Meldung kommt nur noch, wenn wirklich **nichts** gehört wurde.

**Die Diagnose erreichte im Release nie das Log.** `AppLog` ist absichtlich stumm, wenn der Build
nicht debuggable ist (`FLAG_DEBUGGABLE`) — richtig für Entwickler-Rauschen, aber es machte die
letzte Fehlermeldung **undiagnostizierbar**: das Log war leer. Neu: `DictationLog` schreibt
**immer** (Tag `OpenCodeDictation`) — Fehler mit **Rohcode**, Zustand (gehalten?), Anzahl
eingefügter Zeichen und Länge des Teilergebnisses. **Kein Nutzertext** wird protokolliert (Diktat
ist privat, für die Diagnose genügen Zahlen).

### Diagnose am Gerät (aus dem Logcat des Nutzers)

```
SodaSpeechRecognizer: Failed to get language pack of required locale: error 13
```
Der On-Device-Erkenner (SODA/S3) hatte **kein Sprachpaket** für die eingestellte Sprache. Alle drei
Vorkommen gehörten zu **`locale: de-DE`** — also zu **meiner** Sprachumstellung aus der letzten
Runde; mit dem Geräte-Locale (`de-CH`) trat der Fehler nicht auf. Die Einstellung steht deshalb
wieder auf **„System (device setting)"**, und die Doku sagt jetzt ehrlich: Eine Sprache nur wählen,
wenn das Gerät dafür ein **On-Device-Paket** hat — sonst scheitert die Erkennung, und weder
Abschneiden noch Fehlermeldung sind dann App-Fehler.

### Added (tests)

- `SpeechErrorPolicyTest` (7, +1): Teilergebnis wird gerettet statt zu melden — aber nur, wenn etwas
  gehört wurde und noch nichts eingefügt ist.
- Betroffen geprüft (nur diese Klassen): **21 Tests, 0 Fehler**.

## [3.8.2] - 2026-10-15

### Fixed

**Das Diktat schnitt den Schluss ab.** Gemeldet als „zeichnet nicht alles auf, wird gegen den
Schluss einfach abgeschnitten". Die Ursache stand im Code: Die Stille-Schwellen aus der Zeit **vor**
Push-to-Talk waren immer noch gesetzt —

```
EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS     = 850 ms
EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 1300 ms
EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS              = 400 ms
```

Damit sagte die App dem Erkenner: *„850 ms Stille = der Satz ist fertig"*. Bei einem Diktat mitten
im Satz beendet er dann selbständig — und der Rest geht verloren. Bei Push-to-Talk darf der
**Erkenner das Ende gar nicht entscheiden**; das tut der Finger des Nutzers, und `stopListening()`
schließt danach zügig ab. Die drei Extras werden **nicht mehr gesendet** (Plattform-Defaults
bleiben, weil sie pro Engine abgestimmt sind und sich von hier aus nicht erraten lassen). Der
Test pinnt genau das: keine der drei Schwellen darf im Intent landen.

### Changed

**Fehler werden protokolliert und einmal still wiederholt, statt sofort gemeldet.**
Die Meldung „speech recognition failed" kam weiterhin, obwohl nichts sichtbar schiefging — und im
Logcat war dazu **nichts** zu finden (der Erkenner protokolliert nur unter eigenen Tags, die App
gar nicht). Beides behoben:

- **Jeder** Fehler wird jetzt mit seinem **Rohcode** protokolliert
  (`dictation: Busy (raw code 8, held=true)`) — die nächste Meldung ist damit diagnostizierbar
  statt geraten.
- Ein **einmaliger, stiller Wiederholungsversuch** innerhalb desselben Versuchs: Ist der Erkenner
  beschäftigt (`ERROR_RECOGNIZER_BUSY`) oder meldet „out of order" (`ERROR_CLIENT` — genau das
  erzeugt das Loslassen/Abbrechen), wird der Erkenner neu erzeugt und sofort erneut gestartet,
  solange der Knopf noch gehalten wird. Erst wenn auch das scheitert, erscheint eine Meldung.
- Ergebnis und Startfehler werden ebenfalls protokolliert (Zeichenzahl des Ergebnisses, zweimaliger
  Startfehler).

### Changed

**Mikrofon sitzt ganz links.** Der Knopf wurde mit dem Attachment-Knopf getauscht: das Mikrofon ist
jetzt das äußerste linke Element im Composer, „+" folgt direkt danach.

### Added (tests)

- `SpeechRequestPolicyTest` (5): die Stille-Schwellen dürfen **nicht** gesendet werden (sonst schneidet
  die Engine das Diktat ab), Teilergebnisse/Max-Results bleiben wie gehabt.
- Betroffen geprüft (nur diese Klassen): **56 Tests, 0 Fehler**.

## [3.8.1] - 2026-10-15

### Fixed

**Das Mikrofon-Symbol war viel zu groß.** Gemeldet mit Screenshot; die Auswertung bestätigte es
messbar: das Glyph nahm **55–60 %** der Composer-Zeilenhöhe ein, die Nachbarn („+", Verlauf) nur
**35 %** — rund **1,7×** so groß.

Ursache: Ich hatte die **Tippfläche** (48 dp) auf das **Icon** selbst gelegt. Damit wurde auch das
Glyph mit 48 dp gezeichnet, während ein Icon normalerweise mit 24 dp in einer 48-dp-Tippfläche
sitzt. Jetzt ist es genau wie die anderen Knöpfe: `Box` mit **48 dp Tippfläche** und das Icon in
seiner **Standardgröße** darin (`contentAlignment = Center`). Zusätzlich `role = Button` für
TalkBack, damit die Taste auch als Taste angesagt wird.

Rein visuelle Änderung — dafür gibt es keinen sinnvollen Unit-Test; sie ist mit dem Screenshot
belegt und nach dem Installieren mit einem Blick zu prüfen.

## [3.8.0] - 2026-10-15

**Diktat, zweite Ausbaustufe: saubere Sätze, verlässliche Wörter, nichts geht verloren.**

### Added

- **Gesprochene Satzzeichen.** „… Punkt" / „… period" → `.`, „… Fragezeichen" / „… question mark" →
  `?`, `!`, `:`, `;` — und Zeilenbefehle („neue Zeile", „new line", „neuer Absatz") werden ein
  echter Umbruch. **Bewusst konservativ:** ein Satzzeichen zählt **nur am Ende** der Phrase, denn
  „der Punkt ist wichtig" meint *den Punkt*. Zeilenbefehle sind eindeutig und wirken überall.
  Doppelte Umbrüche werden zusammengezogen (`DictationPolicy.applySpokenPunctuation`, getestet).
- **Fallback auf das letzte Teilergebnis.** „Gesprochen, losgelassen, nichts passiert" ist der
  ärgerlichste Diktat-Fall — und auf Geräten kommt genau das vor: der Erkenner liefert ein **leeres**
  Endergebnis, obwohl er beim Halten saubere Zwischenergebnisse hatte. Jetzt wird das letzte
  Teilergebnis benutzt, statt die Worte wegzuwerfen.
- **Sprachwahl** (Settings → „Dictation language": System + 10 gängige Sprachen). Ein Erkenner, der
  die Sprache raten muss, kostet ganze Wörter — besonders wenn man deutsche Sätze mit englischen
  Werkzeugnamen diktiert. Ein expliziter Eintrag schlägt die Geräteeinstellung, sonst bleibt es beim
  Gerät (aber **explizit** gesendet).
- **Politur über den ganzen Text:** nicht mehr nur der erste Buchstabe, sondern **jeder Satz- und
  Zeilenanfang** wird groß („erste zeile. zweite zeile" → „Erste zeile. Zweite zeile"). Rein
  orthografisch, kein Wort wird verändert.
- **Ehrlicher Hinweis nach der Berechtigung.** Wird das Mikrofon erstmals erlaubt, **startet keine
  Aufnahme von selbst** (das würde die Push-to-Talk-Regel brechen): es steht da, dass man den Knopf
  erneut halten soll. Vorher passierte nach dem Erlauben schlicht nichts.

### Added (tests)

- `DictationPolicyTest` (9, +5): Satzzeichen am Ende, **kein** Umbau mitten im Satz („der Punkt ist
  wichtig", „ein Komma setzen"), Zeilenbefehle (auch doppelt → ein Umbruch), die Pipeline
  (Punctuation → Politur), Mehrsatz-Politur und der Teilergebnis-Fallback.
- Betroffen geprüft (nur diese Klassen): **51 Tests, 0 Fehler**.

## [3.7.1] - 2026-10-15

### Changed

**Diktat ist jetzt Push-to-Talk.** Aufnahme läuft **ausschließlich, solange der Knopf gehalten
wird**; beim Loslassen wird der Text in den Composer geschrieben. Es gibt **keinen** Weg mehr, der
von selbst aufnimmt:

- Der Knopf startet nur über den **Press-Handler**, und das Loslassen (auch ein abgebrochener
  Gestus — Finger weggezogen, App in den Hintergrund) beendet die Aufnahme. Ein vergessenes
  Mikrofon ist damit ausgeschlossen.
- Der **fortlaufende Modus ist entfernt** — samt Schalter, Text und Einstellung. Er war die einzige
  Stelle, die ohne Halten weiterlief.
- Die **Pegelanzeige** ist entfernt.
- Die **„Dictated: …"-Meldung ist entfernt**: der Text landet direkt und sauber im Composer, ohne
  Zwischenfenster.

### Fixed

**„Speech recognition failed" kam zufällig, obwohl das Diktat lief.** Ursache: Das Loslassen (und
ein abgelöstes Attempt) erzeugt beim Erkenner **erwartetes** Rauschen (`ERROR_CLIENT`/„no match"),
und ein vorheriger Versuch konnte noch nachträglich antworten. Das wurde gemeldet, obwohl der Nutzer
seine Aufnahme selbst beendet hatte.

Jetzt filtert eine **getestete Regel** (`SpeechErrorPolicy.shouldReport`):
- Versuch ist nicht mehr der aktuelle (**stale**) → still,
- der Nutzer hat beendet (**stoppedByUser**, z. B. Loslassen) → still,
- es wurde schon Text geliefert (**delivered**) → still,
- „nichts gehört" → still (wie bisher).

Gemeldet wird nur ein **echter** Fehler, während der Knopf noch gehalten wird (Netz, Berechtigung,
„kein Erkenner erzeugbar", „beschäftigt"). Dafür zählt jede Aufnahme eine **Generation**; das
Endergebnis nach dem Loslassen wird weiterhin angenommen (sonst käme nie Text an), spätes Rauschen
aus alten Versuchen nicht mehr.

### Added (tests)

- `SpeechErrorPolicyTest` (6, +2): erwartetes Rauschen nach dem Loslassen / nach geliefertem Text /
  aus einem alten Versuch wird **nicht** gemeldet, „nichts gehört" bleibt still, und ein echter
  Fehler während des Haltens wird weiterhin gemeldet.
- `DictationPolicyTest` (4): nur noch Anhängen und Politur (die Undo-/Vorschau-Helfer waren mit dem
  Wegfall der Zwischenmeldung ungenutzt — tote Helfer sind entfernt).
- Betroffen geprüft (nur diese Klassen): **45 Tests, 0 Fehler**.

## [3.7.0] - 2026-10-15

### Fixed

**„No speech recognition available on this device" — obwohl das Gerät Diktat kann.** Gemeldet als
„manchmal funktioniert es, manchmal kommt die Meldung". Es war **mein** Fehler, an drei Stellen:

1. **Der Alltagsfall wurde als Hardware-Urteil gemeldet.** „Nichts gesagt" kommt als
   `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` — genau das passiert bei einer Pause, in einem stillen
   Raum oder bei einem Fehltipper. Mein Code meldete dafür `null` … und der Composer mappte `null`
   auf **„No speech recognition available"**. Das erklärt das „manchmal": das Diktat hatte einfach
   nichts gehört, und die App behauptete, das Telefon könne es nicht.
2. **Ein flakiges `isRecognitionAvailable()` entschied über den Start** (und über die Sichtbarkeit
   des Knopfes). Auf manchen Builds — Samsung besonders — antwortet es **false**, während sich ein
   Erkennungsdienst problemlos erzeugen lässt.
3. **DerErkenner wurde wiederverwendet.** Nach einem Fehler oder Abbruch kann eine Instanz „klemmen"
   und keinen weiteren Start annehmen — auch das machte es unzuverlässig.

**Fix:**
- Neue, **getestete Klassifikation** `SpeechErrorPolicy` (`NoSpeech`, `Unavailable`, `Busy`,
  `Network`, `Permission`, `Failed`). `NoSpeech` ist ausdrücklich **nicht meldbar** — der Knopf hört
  einfach auf. `Unavailable` ist jetzt ausschließlich „es ließ sich wirklich kein Erkenner
  erzeugen".
- **Kein Vorverurteilen mehr:** `isRecognitionAvailable()` ist nur noch Wortwahl-Hinweis, kein Tor.
  Der Knopf erscheint, wenn der Schalter an ist (ein verschwindendes Mikrofon ist schlimmer als
  eines, das sich einmal erklärt).
- **Pro Versuch ein frischer Erkenner** (`releaseEngine()` + Erzeugen, ein Wiederholungsversuch) —
  das behebt das „klemmende" Verhalten.
- **Passende Meldungen:** „beschäftigt, tippe nochmal" statt Geräteurteil: `Busy` → eigener Text,
  `Network`/`Permission` eigene Texte, sonst die Meldung des Dienstes.

### Added / Changed — Diktat-Qualität (massgebend ausgebaut)

Diktieren war ein Knopf mit Plattform-Standards. Jetzt entscheidet eine **reine, getestete
Request-Politik** (`SpeechRequestPolicy`), was der Erkenner bekommt — und der Nutzer kann die
Abwägung selbst treffen:

| Hebel | Wirkung |
| --- | --- |
| **Kürzere Stille-Schwellen** (850 ms „fertig", 1300 ms „wahrscheinlich fertig", 400 ms Mindestlänge — Plattform-Default ist ~2 s, je Build anders) | **spürbar schnelleres Reagieren**: die Erkennung schließt ab, sobald man kurz Luft holt, statt zwei Sekunden zu warten |
| **Sprache explizit** (`EXTRA_LANGUAGE` aus dem Geräte-Locale) | ein Erkenner, der die Sprache raten muss, kostet ganze Wörter |
| **Punctuation/Casing-Qualität** (`EXTRA_ENABLE_FORMATTING = FORMATTING_OPTIMIZE_QUALITY`, ab API 33) | sauberere Sätze, Groß-/Kleinschreibung |
| **Vokabular-Bias** (`EXTRA_BIASING_STRINGS`, ab API 33): Domänenwörter des Clients (`opencode`, `Gradle`, `Kotlin`, `commit`, `Refactor`, …) **plus die Wörter des offenen Session-Titels** | genau die Wörter werden zuverlässiger, die man in einem Coding-Client täglich diktiert — der Titel nennt die aktuelle Arbeit |
| **Fortlaufendes Diktat** (Schalter): nach jedem erkannten Satz bleibt das Mikrofon offen; eine Pause beendet es **nicht**, nur ein echter Fehler | einen ganzen Prompt in einem Zug sprechen, ohne neu zu tippen |
| **Pegelanzeige** (aus `onRmsChanged`) | sichtbares Feedback, dass das Mikrofon hört — und ob man zu leise ist |
| **Text-Politur** (`DictationPolicy.polish`): erste Buchstabe groß, Mehrfach-Leerzeichen zusammengezogen | „wrote the tests" wird „Wrote the tests", ohne Codebegriffe anzufassen |
| **Undo** nach jedem Diktat (`postWithAction`) | die falsch erkannte Phrase ist mit **einem Tipp** zurückgenommen — und zwar **nur** diese Phrase, nie selbst Getipptes (`undoAppend`) |

**Neue Schalter** (jeder ist eine echte Abwägung, nicht Kosmetik):
**Settings → „Accurate dictation"** (Punctuation-Bias, Standard an) · **„Dictate on-device only"**
(kein Netz-Roundtrip, schneller + privat, gelegentlich weniger genau — Standard aus) ·
**„Keep listening between sentences"** (Standard an) — zusätzlich zum bestehenden
„Speech input in the composer".

### Added (tests)

- `SpeechErrorPolicyTest` (4 Fälle): „nichts gehört" bleibt still (und ist nicht meldbar),
  `Busy` lädt zum Wiederholen ein, Netzwerk/Berechtigung werden benannt, alles andere behält den
  Grund des Dienstes, und `Unavailable` bleibt dem echten Fall vorbehalten.
- `SpeechRequestPolicyTest` (5 Fälle): kürzere Stille-Schwellen, API-Gating (Formatting/Bias erst
  ab 33), Offline/Sprache nur wenn gewünscht, Vokabular bleibt ein Hint (leer/Duplikate fallen
  raus), Titel liefert die Wörter der aktuellen Arbeit.
- `DictationPolicyTest` (6, +2): Politur (Großbuchstabe, Mehrfach-Leerzeichen, kein Unsinn bei
  `/pfaden`) und Undo (genau die angehängte Phrase, selbst Getipptes bleibt).
- Betroffen geprüft (nur diese Klassen): **51 Tests, 0 Fehler**.

## [3.6.0] - 2026-10-15

### Added

**Speech-to-text im Composer.** Ein Mikrofon-Knopf links neben dem Eingabefeld diktiert direkt
hinein — **an Ort und Stelle**, nicht über den Systemdialog: die Worte landen in dem Feld, das man
ohnehin ansieht.

- **In-Place-Erkennung** über `SpeechRecognizer` (der Dienst des Geräts macht die Arbeit; die App
  schickt selbst kein Audio irgendwohin). Das Mikrofon ist **nur zwischen Start und Stopp** aktiv.
- **Teilergebnisse werden nur VORSCHAUT**, nie ins Feld geschrieben: eine verhörte Zwischenstufe
  kann die eigene Eingabe nicht überschreiben. Erst das **Endergebnis** wird angehängt.
- **Zusammenfügen nach einer getesteten Regel** (`DictationPolicy`): genau ein Leerzeichen zwischen
  den Teilen, nie zwei; ein bewusst gesetzter Zeilenumbruch bleibt ein Umbruch; ein leeres Ergebnis
  (nur Leerzeichen) ändert **nichts** — eine stille Erkennung darf den Text nicht fressen.
- **Zustand sichtbar:** während des Zuhörens steht über dem Feld „Listening… <Vorschau>", der Knopf
  färbt sich rot und wird zum Stopp.
- **Berechtigung**: `RECORD_AUDIO` wird erst beim ersten Tippen erfragt (Manifest + `<queries>` für
  den Erkennungsdienst, damit er ab API 30 sichtbar ist). Verweigert = verständliche Meldung, kein
  stiller Fehlschlag. Fehler des Dienstes werden übersetzt („Netzwerkproblem", „Mikrofon-Berechtigung
  fehlt", „der Erkennungsdienst ist beschäftigt"); die Alltagsfälle „nichts gesagt"
  (`ERROR_NO_MATCH`/`SPEECH_TIMEOUT`) melden bewusst **nichts** — der Knopf hört einfach auf.
- **Schalter**: **Settings → „Speech input in the composer"** (Standard an). Aus = kein Mikrofon-
  Knopf **und** kein Start; das Gerät ohne Erkennungsdienst zeigt den Knopf ebenfalls nicht
  (`SpeechInput.available()`).
- Aufgeräumt wird im `onDispose` des Composers (`destroy()`), damit der Erkennungsdienst nicht
  weiterläuft, wenn der Chat verlassen wird.

### Added (tests)

- `DictationPolicyTest` (4 Fälle): leeres Feld, Anhängen mit genau einem Leerzeichen, ein
  Whitespace-Ergebnis ändert nichts, Zeilenumbruch bleibt Umbruch, Vorschau == was gesendet würde.
- Betroffen geprüft (nur diese Klassen): **40 Tests, 0 Fehler** (`DictationPolicyTest`,
  `SessionMetaPolicyTest`, `CentrePolicyTest`, `CentralActionsTest`, `ExplainPolicyTest`).

### Ehrlich offen

- Die Erkennungsqualität kommt vom Dienst des Geräts (offline/online je nach Gerät) — die App
  beeinflusst sie nicht und schickt selbst nichts.
- Wird während des Zuhörens gesendet, läuft die Erkennung weiter und das Endergebnis füllt das
  (dann leere) Feld. Bewusst nicht abgeschnitten: ein unterbrochener Diktat-Vorgang soll nicht
  ohne Ergebnis verpuffen.

## [3.5.3] - 2026-10-15

### Fixed

**Eine neue Session behielt den Namen „new session", obwohl der Server sie längst benannt hatte.**

Live gegen den laufenden Server reproduziert: Eine Session **ohne** Titel angelegt, erster Prompt
gesendet — nach **~3 Sekunden** stand serverseitig der generierte Titel
(`„Berechtigungsprüfung im Login korrigieren"`). Der Server ist also in Ordnung.

Der Fehler saß im Poller der App: Er las die Session alle 5 s neu, refreshte die Metadaten
(Titel!) aber **nur wenn sich `time.updated` geändert hatte**. Genau das tut das Benennen einer
Session **nicht** — der Server setzt den Titel, ohne den Zeitstempel anzufassen. Also lief der
Refresh nie, `uiState.session.title` blieb der Platzhalter, und der Titel kam erst nach einem
kompletten Neuladen an. Der Mechanismus war übrigens schon richtig gebaut
(`refreshSessionModel` kopiert den Titel, samt Kommentar „the server names a session from its
first turn") — er wurde nur nie ausgelöst.

**Fix (getestete Regel statt Bedingung im Poller):** `SessionMetaPolicy.needsMetaRefresh` — ein
**abweichender, nicht-leerer** Server-Titel ist sein eigener Auslöser. Bewusst konservativ:
Ein leerer Titel (Server generiert noch, oder ein Build, der Sessions nie benennt) **löscht nie**
den angezeigten Namen, und ohne Änderung wird nichts gelesen.

**Plus:** Der 5-s-Poller pausiert bei ausgeschaltetem Bildschirm — kehrt man zurück, wird die
Meta **einmal sofort** nachgezogen (`setScreenVisible`, nur beim Übergang von unsichtbar →
sichtbar), damit ein im Hintergrund vergebener Titel nicht einen weiteren Tick wartet.

### Added (tests)

- `SessionMetaPolicyTest` (3 Fälle): ankommender Titel ist ein eigener Auslöser, nichts passiert
  ohne Änderung, ein leerer/whitespace Server-Titel ändert nichts, neue Inhalte refreshen
  unabhängig vom Titel.
- Betroffen geprüft (nur diese Klassen): **36 Tests, 0 Fehler** (`SessionMetaPolicyTest`,
  `CentrePolicyTest`, `CentralActionsTest`, `ExplainPolicyTest`).

## [3.5.2] - 2026-10-14

**Die Zentrale wird alltagstauglich: sie zeigt nicht mehr nur, sie lässt einen sofort weitermachen.**

Rückmeldung war: „zu wenig nützlich im Daily Use". Der Kern des Problems: die Zentrale war eine
Beobachtungsstation — zum Arbeiten musste man die Session erst öffnen. Jetzt ist der
**Weiterarbeiten-Einstieg** die erste Sektion.

### Added — „Continue" (neue erste Sektion)

- Die **zuletzt angefassten Sessions** (die neuesten, die nicht laufen — die laufenden stehen
  direkt darunter), je Zeile: Titel, **wann zuletzt** („3 min", „2 h", „4 d"), **was passiert ist**
  (`3 files (+42/−7)` aus `session.summary`, ohne Zusatz-Anfrage), Kosten und Kontextlast.
- **Ein-Tipp-Prompts:** das ⚡-Symbol klappt eine Reihe Chips auf — **„Continue where you left off
  and report what you changed."** plus die **eigenen Slash-Befehle des Servers** aus
  `GET /api/command` (also das, was dieser Server wirklich anbietet, nichts Erfundenes), gedeckelt
  auf vier Chips. Tippen sendet sofort.
- **Sicherheitsregel dabei:** läuft ein Turn, geht der Prompt als `delivery:"queue"` raus und
  **unterbricht nie** laufende Arbeit; im Ruhezustand wird er direkt gesendet. Jeder Versand steht
  im Audit-Protokoll (`Sent`).
- **„All sessions & search →"** führt zur vollen Liste auf Home, statt eine zweite Suchliste zu
  bauen.

### Changed — Zeilen sagen „wann"

`CentrePolicy.ago` (rein, getestet) liefert „now / 3 min / 2 h / 4 d"; ohne Zeitstempel **keine**
Angabe statt einer falschen. `summaryLabel` beschreibt die Änderungen einer Session in der Form der
Referenz-Clients.

### Added — Schalter

**Settings → „Quick prompts from the Centre"** (Standard an). Aus heißt: die Chips erscheinen nicht,
und die zentrale Aktionsschicht **verweigert** den Versand zusätzlich (`Skipped`) — ein Shortcut
darf nicht an einem Schalter vorbeikommen. (Der Prompt geht an ein Modell und kostet Tokens, deshalb
überhaupt schaltbar.)

### Added — „Explain this" für Befehle, Tool-Calls und geschriebenen Code

Der Explain-Knopf saß bisher nur am Nachrichtentext. Jetzt hat **jede Tool-Zeile** ein
💡 **„Explain this (I want to learn)"**:

- **Geschriebener Code:** der Diff des Edit/Write, erklärt vom `docs`-Agenten — was er tut und
  worauf man achten muss.
- **Shell-Befehle:** der Befehl **samt Ausgabe**, erklärt vom `ask`-Agenten mit einer
  Lern-Aufforderung: was die einzelnen Teile und Flags tun, warum es hier nötig ist, was
  schiefgehen kann und wie eine sicherere/üblichere Variante aussieht.
- **Sonstige Tool-Calls:** Werkzeug, Pfad, Parameter und Ergebnis — damit man lernt, solche
  Aufrufe zu lesen.
- **Kein Knopf ohne Inhalt:** eine laufende Zeile ohne Eingabe/Ausgabe hat nichts zu erklären und
  bietet die Aktion gar nicht erst an (vom Test gefunden).

Die Auswahl (Kind, Ausschnitt, Beschriftung) liegt in `ExplainPolicy.target` (rein, getestet),
die Ausführung im vorhandenen Explain-Pfad (eigene Session, `ask`/`docs`, Schalter
„Explain mode" wie bisher).

### Added (tests)

- `CentralActionsTest` (11, +2): Quick-Prompt wird bei laufendem Turn **ge**queued und im
  Ruhezustand direkt gesendet; ein leerer Prompt wird gar nicht gesendet; der Schalter verhindert
  die Anfrage selbst.
- `CentrePolicyTest` (16, +2): Zeitangaben (jetzt/3 min/2 h/4 d, ohne Zeitstempel nichts) und
  Änderungs-Zusammenfassung (Ein-/Mehrzahl, ohne Änderungen nichts); Chip-Vorschläge = „Continue"
  plus Server-Befehle, gedeckelt, ohne Befehle trotzdem der Daily-Shortcut.
- `ExplainPolicyTest` (6, +3): Befehl/Tool-Call gehen an den `ask`-Agenten mit Lern-Wortlaut,
  Diff → `CODE`, Befehl → `COMMAND` (mit Ausgabe), Rest → `TOOL_CALL`, und eine leere Zeile hat
  nichts zu erklären.
- Betroffen geprüft (nur diese Klassen): **47 Tests, 0 Fehler**.

## [3.5.1] - 2026-10-14

### Fixed

**Der „Open"-Knopf unter „What needs attention" tat nichts.** Ursache gefunden (nicht geraten):
Der Knopf gehörte zur Aussage *„N projects with uncommitted changes"*, deren Aktion
`InsightAction.OpenChanges` war — und `execute()` beantwortete sie mit `Unit`. Die Oberfläche
beschriftete sie zusätzlich mit dem generischen **„Open"**, sodass ein wirkungsloser Knopf wie ein
kaputter aussah. Zwei Fixes, damit das nicht wieder passiert:

1. **Die Aussage ist jetzt handlungsfähig:** `DirtyProject` trägt die **neueste Session des
   Projekts** (dort lebt der Changes-Tab), die Aktion öffnet sie, und die Beschriftung heißt
   ehrlich **„Open changes"**. Ohne Session (Projekt ohne Session) bleibt es eine reine Aussage.
2. **Kein Knopf ohne Wirkung:** `Insight.canRun` (rein, getestet) prüft, ob die Aktion eine
   Session hat; die Oberfläche rendert den Knopf nur dann. Genau das war der Fehler — sichtbar,
   aber wirkungslos.

### Added — die Zentrale handelt direkt

- **Kontextmenü je Session-Zeile** (Long-Press **und** ein „⋮"-Knopf, weil Long-Press unsichtbar
  ist): **Open · Stop · Steer** (nur bei Warteschlange) **· Compact · Copy session ID · Copy
  directory**. Läuft/retry entscheidet, welche Einträge erscheinen.
- **Berechtigungen direkt aus „Needs you" beantworten:** **Allow once · Always · Deny** — ohne die
  Session zu öffnen. Das ist der eigentliche Zweck der Liste: ein blockierter Turn wird mit einem
  Tipp freigegeben. `CentreNeed` trägt dafür die Request-ID mit.
- **Beide Wege laufen durch die zentrale Aktionsschicht:** `CentralActions.replyPermission`
  (Protokollwerte `once`/`always`/`reject`, Audit-Eintrag `Approved`/`Denied`) und
  `CentralActions.compact`. Damit haben Zentrale, Chat und Benachrichtigung dieselben Guards,
  Audit-Einträge und Fehlertexte.
- **Delete und Archivieren im Menü:** *Delete* löscht die Session **auf dem Server** (permanent —
  deshalb mit Rückfrage: „… will be permanently deleted from the server. This cannot be undone.")
  und wird als `Deleted` protokolliert; *Archive (local)* ist die umkehrbare Variante ohne
  Server-Schreibzugriff. Beide laufen durch `CentralActions`.
- Neue VM-Aktionen für das Menü: `compact(row)`, `steerFirstQueued(row)`, `answerPermission(...)`,
  `delete(row)`, `archive(row)`.

### Fixed (2)

**„… 45 more sessions" war eine Sackgasse.** Die Zeile nannte verborgene Sessions, aber es gab
keinen Weg, sie zu sehen — die Liste der ruhenden Sessions war fest auf fünf gekappt. Jetzt ist
der Hinweis **anklickbar**: er klappt die vollständige Liste an Ort und Stelle auf
(„Show less" klappt zurück), mit derselben Wortwahl wie die Datei-Faltung im Chat.

### Added (tests)

- `CentralActionsTest` (8, +1): Freigabe erreicht den Server mit dem richtigen Protokollwert
  (`once`/`always`/`reject`), eine abgelehnte Antwort wird als `Failed` gemeldet.
- `CentreInsightsTest` (12, +2): eine schmutzige Projekt-Aussage ist nur handlungsfähig, wenn eine
  Session sie öffnen kann (`canRun`), und `canRun` verweigert jede Aktion ohne Session.
- Betroffen geprüft (nur diese Klassen): **35 Tests, 0 Fehler**.

## [3.5.0] - 2026-10-14

**Feinschliff-Release: die Zentrale wird sparsamer, ehrlicher und barrierefreier — plus die
„dirty project"-Aussage, für die es jetzt einen billigen Endpunkt gibt.**

### Added — schmutzige Projekte (die offene Lücke aus 3.0 ist geschlossen)

Beim Schema-Nachlesen der lokalen OpenAPI-Kopie gefunden: **`GET /api/vcs/status` liefert
`[{file, additions, deletions, status}]` — ohne Patches.** Genau das, was gefehlt hat (die
Alternative `/vcs/diff` schickt Megabyte-Patches):

- Pro gescanntem Verzeichnis **ein** Aufruf, zusammen mit dem Branch in einem Durchlauf.
- Neue Aussage: *„N projects with uncommitted changes"* mit Detail
  `projekt · branch · 2 files (+42/−7)` (Form wie in den Referenz-Clients) und Aktion **Open
  changes**. Saubere Projekte liefern bewusst keine Zeile (`dirtyProjectLabel` gibt `null`).
- Die neue API-Methode `getVcsStatus` ist bewusst getrennt von `getVcsDiff` dokumentiert, damit
  niemand versehentlich die teure Route in einen Hintergrund-Schnappschuss einbaut.

### Changed — Request-Budget der Zentrale

Die Zentrale holte pro Aktualisieren bis zu ~35 Anfragen. Jetzt:

- **Scan-Budget 3 Projekte** statt 5 (jedes Verzeichnis kostet Berechtigungen, Formulare, Branch,
  Status).
- **Einmal-Erkennung verwaister Sessions:** als „gone" erkannte Session-IDs werden gemerkt; vorher
  kostete jeder verwaiste Eintrag bei **jedem** Aktualisieren eine Anfrage.
- **7-Tage-Baseline 5 Minuten zwischengespeichert:** die Zahl bewegt sich langsam, ein zweiter
  Schnappschuss kurz danach bezahlte sie erneut.
- Branch und Status stehen in **einem** Durchlauf pro Verzeichnis (vorher zwei Schleifen).

### Changed — eine Quelle für Geld

`util/Money` ist jetzt der **einzige** Formatierer für Kosten; `GlanceStore` und die Zentrale
delegieren dorthin (vorher formatierten zwei Stellen dieselbe Zahl). Beim Testen fiel dabei auf,
dass die Prozentrechnung **abgeschnitten** statt gerundet hat: 44,999… % las sich als „+44%", also
systematisch einen Punkt zu wenig bei jeder Steigerung. Jetzt gerundet (`+45%`).

### Added — Barrierefreiheit

Der Schweregrad einer Aussage ist die Information, die ein Screenreader **nicht** sehen kann
(Farbe trägt sie sonst allein). Jede Aussagen-Zeile hat deshalb eine `stateDescription`
(„Urgent" / „Warning" / „Notice" / „Information").

### Added — Schalter für die Compact-Aktion

Die Verdichtung („Compact") ändert, was der Agent sich merkt — sie bleibt damit schaltbar:
**Settings → „Offer compaction in the Centre"** (Standard an). Aus heißt: die Aussage
„Kontext fast voll" erscheint weiter, aber ohne Aktionsknopf; die zentrale Aktionsschicht
verweigert den Aufruf zusätzlich (`ActionResult.Skipped`), damit kein Shortcut daran vorbeikommt.

### Added (tests)

- `MoneyTest` (2 Fälle): Betragsformat und Trend inkl. Rundung sowie „keine Aussage ohne
  Baseline".
- `CentrePolicyTest` (14, +1): Beschriftung schmutziger Projekte (Ein-/Mehrzahl, Zählwerte,
  sauberes Projekt = `null`).
- `CentralActionsTest` (8, +1): der Schalter **verhindert die Anfrage** bei ausgeschalteter
  Compact-Aktion, nicht nur die Meldung.
- Betroffen geprüft (nur diese Klassen): **33 Tests, 0 Fehler**.

### Ehrlich offen

- Die Aussage „Retry-Schleife" nennt den Server-Grund; der nächste Versuch wird nicht
  heruntergezählt (kein Ticker, die Zentrale aktualisiert nicht im Sekundentakt).
- Worktrees (`/api/worktree`) bleiben ungenutzt: eine eigene UX pro Aufgabe, kein Nebenprodukt
  eines Snapshots.

## [3.0.0] - 2026-10-14

**Die Zentrale wird zur Central Intelligence: Sie zeigt nicht mehr nur Zustände, sie leitet
Aussagen daraus ab — und alle Schreibzugriffe laufen durch eine zentrale Aktionsschicht.**

### Added — Insight-Engine (ableitende Logik)

`CentreInsights` verwandelt Server-Werte in Sätze, auf die man reagieren kann. Die Regeln sind
bewusst konservativ (ein falscher „hängt"-Alarm auf einem hart denkenden Turn zerstört das
Vertrauen in das ganze Panel), und jede Regel braucht positive Evidenz — Unbekanntes ergibt
**keine** Aussage statt einer Vermutung:

| Regel | Schwelle | Aktion |
| --- | --- | --- |
| Kein Fortschritt | laufend & >10 min ohne Aktivität (unbekannt = nie) | Stop |
| Retry-Schleife | `retry` & Versuch ≥ 2, mit Server-Grund | Öffnen |
| Queue-Rückstau | wartende Prompts hinter einem laufenden Turn | Steer |
| Kontext fast voll | ≥ 85 % von Fenster (Tokens aus der Session-Liste, Fenster aus dem Katalog) | Compact |
| Kostenausreißer | heute ≥ 2× Vortages-Schnitt **und** ≥ 1 $ | — |
| Ungesendete Prompts | lokaler Postausgang nicht leer | — |
| Wartende Arbeit | „Braucht dich" nicht leer | — |
| Verwaiste Anfragen | Anfragen gelöschter Sessions | — |
| MCP nicht verbunden | verbunden < gesamt | — |
| Sonst | — | „All clear" |

Sortierung: Schweregrad, dann **handelbare vor reinen Informationen**, dann stabil nach ID.

### Added — zentrale Aktionsschicht

`CentralActions` (in `data/`) ist der **einzige** Ort, der Zustand ändert: `stop`, `stopAll`,
`steer`, `compact`, `handOverDecision`. Damit haben Zentrale, Benachrichtigung, Widget und Chat
**dieselben Guards, dieselben Audit-Einträge und dieselben Fehlertexte** — vorher baute jede
Stelle ihre eigene Anfrage, eine vergaß den Audit-Eintrag, eine den Schalter. Alles gibt
`ActionResult` zurück (`Ok` / `Skipped(reason)` / `Failed(message)`), transportfrei als
suspend-Lambdas, damit die Policy ohne Retrofit testbar ist. Neue Audit-Art: `Compacted`.
`insight.action` → `CentreViewModel.execute(insight)`: die Zuordnung „Aussage → Aktion" liegt im
ViewModel, nicht in der Oberfläche, damit jede Fläche sie gleich ausführen kann.

### Added — mehr Vernetzung (Endpunkte, die vorher brachlagen)

Ein Schnappschuss wird jetzt **parallel** geholt (jeder Teil darf ausfallen, ohne den Rest zu
leeren), alles begrenzt — **kein Polling**:

- `GET /api/session` + `/api/session/active` → Sessions, Status, **Tokens und Kosten pro Session**
- **Server-Queues** je laufender Session (`GET /api/session/{id}/inbox`, max. 5) → Rückstau sichtbar
- `GET /api/mcp` → verbundene/gesamt · `/api/skill` + `/api/plugin` → Zähler
- `GET /api/vcs` (max. 3 Verzeichnisse) → Branch je Projekt
- `GET /api/experimental/session/stats` **zweimal** (heute + 7 Tage) → Tageswert **mit Vergleich**
  zum Vortages-Schnitt
- `GET /api/session/{id}/context`-Fenster über den Anbieter-Katalog (`contextLimitFor`) statt einer
  Anfrage je Session

### Changed — das Lagebild

- Kopfzeile: „N running · M waiting for you · $X today" (plus Trend in der Tages-Sektion).
- **Aussagen-Sektion** oben, farbig nach Schweregrad, mit dem Aktionsknopf der Aussage.
- Laufende Zeilen zeigen **Kontextlast** (`ctx 87% · 180k`, ab 85 % in Fehlerfarbe), **Queue-Tiefe**
  und den Retry-Grund; Kosten und Projekt wie bisher.
- Neue **Server & Projekte**-Karte: MCP verbunden/gesamt, Skills, Plugins, Branches.

### Added — geteilte ID-Erzeugung

`util/OpenCodeIds` erzeugt `msg_…`/`prt_…` (Thread-sicher, Zähler gegen Kollisionen in derselben
Millisekunde). Das ist die Voraussetzung für optimistische Oberflächen von **jeder** Fläche aus:
die Compaction aus der Zentrale trägt eine Client-ID, die der Server zurückgibt.

### Added (tests)

- `CentreInsightsTest` (10 Fälle): jede Regel in **beiden** Richtungen (feuert / bleibt still),
  Schwellen (9 vs. 20 Minuten, Versuch 1 vs. 2, 80 % vs. 90 % Kontext, Ausreißer mit/ohne
  Untergrenze), Sortierung nach Schweregrad und Handlungsfähigkeit.
- `CentralActionsTest` (7 Fälle): Transport erreicht, Fehler wird zu `Failed` statt Exception,
  Not-Aus stoppt alle und meldet die Zahl, Steer genau eines Prompts, Compaction, Guard für
  abgeschaltete Funktion **verhindert die Anfrage**, Ablehnung/Transportfehler werden gemeldet.
- Betroffen geprüft (nur diese Klassen): **30 Tests, 0 Fehler**.

### Ehrlich offen

- „Projekt mit uncommitteten Änderungen" ist in der Engine vorgesehen, aber **nicht verdrahtet**:
  `/vcs/diff` liefert Megabyte-Patches, `/vcs/status` auf diesem Server nur Branch-Infos — es gibt
  keinen billigen Weg, „dirty" zu erkennen. Lieber keine Aussage als eine geratene.

## [2.0.6] - 2026-10-14

### Fixed

**Absturz beim Öffnen eines Chats** (`java.lang.NullPointerException: Attempt to invoke virtual
method 'java.lang.Object t43.getValue()' on a null object reference`, 2× direkt hintereinander).

Aus dem Geräte-Crush-Buffer gelesen und mit `mapping.txt` **retraced** — der Absturz zeigte auf
`ChatViewModel.kt:4788`, aufgerufen aus dem in `init` gestarteten Collector (`ChatViewModel.kt:309`):

- Der Journal-Collector stand im **`init`-Block**, greift aber auf `_uiState` zu — und `_uiState`
  wird erst **120 Zeilen später** deklariert. `viewModelScope` läuft auf
  `Dispatchers.Main.immediate`, also läuft der Coroutine-Körper **synchron während der
  Konstruktion**; die erste Emission eines `StateFlow` kommt dabei **vor** jeder Suspension.
  Ergebnis: `_uiState` war noch `null` → NPE bei **jedem** Öffnen des Chats (der Crash-Log zeigt
  zwei Versuche direkt nacheinander, also reproduzierbar).
- Fix: der Feed startet wie der Glance-Feed aus **`loadSession()`** (`startJournalFeed()`,
  idempotent) — die Lehre aus dem früheren Glance-Absturz, die ich beim Journal nicht angewandt
  hatte.
- **Audit der Fehlerklasse:** alle `init`-Blöcke im `ChatViewModel` gegen die Felddeklarationen
  geprüft; der Outbox-Feed ist reaktionsgetrieben (keine Emission während der Konstruktion), der
  Glance-/Journal-Feed läuft jetzt beide nach der Konstruktion. Kein weiterer Fall.

### Verifikation

- Gerät: frisch gestartet, Chat geöffnet, **0 FATAL** im Crash-Buffer (vorher: 2 Crashes pro
  Start). Journal-Zahl im Chat-Menü zeigt die echte Anzahl ohne Antippen.

## [2.0.5] - 2026-10-14

### Fixed

**„Braucht dich": Ein Tippen öffnete eine Session mit 404.**

Live belegt, nicht vermutet: Die fünf wartenden Formulare verwiesen alle auf
`ses_eca7e402bffeVhHvhJUBoWeCNK`, und diese Session **existiert nicht mehr** — `GET
/api/session/<id>` antwortet mit **jedem** Verzeichnis 404 (auch global). Im Server-Store sind
also **verwaiste Formulare** liegen geblieben; die App bot sie an, und ein Tippen öffnete eine
Session, die es nicht gibt. Aufräumen ist serverseitig nicht möglich
(`DELETE /api/session/<id>/form/<fid>` antwortet für die tote Session ebenfalls 404 — getestet).

Zwei Fixes, damit das nicht wieder passiert:

1. **„Braucht dich" bietet nichts an, was nicht erreichbar ist.** Beim Aktualisieren werden die
   referenzierten Sessions geprüft (max. 10 Anfragen je Aktualisierung); die, die mit 404/410
   antworten, fallen aus der Liste (`CentrePolicy.withoutMissing`, reine Regel, getestet). Ein
   **fehlgeschlagener** Test (Netz) lässt den Eintrag stehen — es wird nie etwas versteckt, das
   erreichbar sein könnte. Ergebnis im Live-Fall: der Abschnitt ist leer statt fünf Zeilen mit
   toten Verweisen.
2. **Ein 404 im Chat wird erklärt statt angezeigt.** Bisher stand dort `HTTP 404 …` (der rohe
   Statuscode). Jetzt erkennt der Ladevorgang „gone" (404/410) und zeigt einen Satz, den man
   versteht: *„This session no longer exists on the server — it was probably deleted or cleaned
   up. Close it and pick another session."* Der zusätzliche Snackbar entfällt in diesem Fall
   (er hätte den Code nur wiederholt); die Wiederholen-Aktion bleibt. Betrifft auch Deep Links
   und alte Benachrichtigungen.

### Added (tests)

- `CentrePolicyTest` (13 Fälle, +1): Einträge mit toter Session fallen raus, lebende und
  wartende bleiben, ein leeres „missing"-Set ändert nichts.
- Betroffen geprüft (nur diese Klassen): `CentrePolicyTest` 13, `JournalPolicyTest` 9,
  `QueuePolicyTest` 4 → **26 Tests, 0 Fehler**.

## [2.0.4] - 2026-10-14

### Fixed (Decisions)

- **„Decisions" zeigte 0, obwohl Einträge vorhanden waren — erst ein Tippen aktualisierte.**
  Ursache: `uiState.journal` war ein **Schnappschuss**, der nur beim Öffnen des Dialogs (und
  nach add/delete/inject) neu berechnet wurde. Beim Start stand dort die leere Liste, die
  Menü-Zahl war also 0. Jetzt **beobachtet das ViewModel den Journal-Store** (`collect`): die
  Zahl ist live, egal woher die Änderung kommt. Beim Session-Wechsel wird zusätzlich neu
  gescopt (das Journal ist projektgebunden).
- **Tile/Glance sagten „No decisions open", während das Journal Einträge hatte.** Die
  Ambient-Zahl kannte nur die Anfragen des Modells (Berechtigungen/Fragen) — **captured
  Entscheidungen fehlten völlig**. Es gibt jetzt **eine** Quelle
  (`ambientDecisionCount` = offene Anfragen + noch nicht übergebene Entscheidungen), die auch
  beim Capture sofort auf Widget, Notification-Zeile und Tile durchschlägt.

### Fixed (Message queue)

- **Eine „Queued"-Karte konnte für einen Prompt erscheinen, der längst normal gesendet wurde.**
  Ist kein Turn im Umlauf, liefert der Server einen `delivery:"queue"`-Prompt **sofort** aus
  (live bewiesen) — es entstand also eine Queue-Zeile für eine normale Nachricht, die wieder
  verschwinden musste. Die optimistische Karte wird jetzt **nur noch bei laufendem Turn**
  angelegt; sonst geht der Prompt als normale Nachricht durch.
- (aus 2.0.3, hier zusammengeführt) zugestellte Prompts fallen über das Transcript aus der
  Queue, und die Inbox wird am Turn-Ende neu gelesen.

### Added (tests)

- `JournalPolicyTest` (9 Fälle, +1): „nur nicht übergebene Entscheidungen zählen als offen" —
  übergebene zählen nicht mit.
- `QueuePolicyTest` (4 Fälle) unverändert grün.
- Betroffen geprüft (nur diese Klassen): **13 Tests, 0 Fehler**; App-Compile grün.

## [2.0.3] - 2026-10-14

### Fixed

**Prompts aus der Warteschlange blieben in der Queue stehen, obwohl sie längst im Chat waren.**

Beleg zuerst, live gegen den Server: ein Prompt mit `delivery:"queue"` **verlässt die Inbox in
dem Moment, in dem er zugestellt wird**, und wird eine reguläre Nachricht **mit derselben ID** —
die Inbox war danach leer (`msg_qt1` als Nachricht vorhanden, Inbox `[]`). Der Server ist also
sauber; der Fehler saß in der App:

- Die **optimistische Queue-Karte** (die Zeile, die der Composer sofort anzeigt, bevor die
  Antwort da ist) wurde nur von einem späteren Inbox-Reload oder einem
  `session.inbox.changed`-Event entfernt. Blieb das aus, hing die Zeile dauerhaft in der Queue —
  und der Prompt stand **doppelt** im Chat (einmal als Nachricht, einmal als Queue-Zeile).
  Jetzt gilt: **was schon als Nachricht im Transcript steht, ist nicht mehr in der Queue**
  (`QueuePolicy.pending`, reine Regel, getestet). Das ist selbstheilend und braucht kein Event —
  das Transcript ist die Wahrheit.
- Zusätzlich wird die Inbox **am Turn-Ende** neu gelesen (`session.idle`): das ist genau der
  Moment, in dem ein wartender Prompt zugestellt wird. Damit verschwinden auch Einträge, die
  nie im Transcript auftauchen (z. B. per „Steer" vorgeschoben).

Gefiltert wird, was der Nutzer sieht: Queue-Liste, Zähler im Umlauf-Indikator und die Zahl am
Composer. Einträge **ohne ID** bleiben bewusst stehen — sie lassen sich nicht zuordnen, und sie
auszublenden würde einen erwarteten Prompt verschwinden lassen.

### Added (tests)

- `QueuePolicyTest` (4 Fälle): zugestellter Prompt fällt aus der Queue, ganze Queue leert sich,
  nichts wird gefiltert solange nichts zugestellt ist, IDs ohne Wert bleiben, nur `user`-Typen
  zählen als Queue-Prompts.
- Betroffen geprüft (nur diese Klassen): `QueuePolicyTest` → **4 Tests, 0 Fehler**; Compile der
  App grün.

## [2.0.2] - 2026-10-14

### Added

- **Entscheidungen werden automatisch an den Agenten übergeben.** Beim Speichern wird die
  Entscheidung sofort in die geöffnete Session injiziert (`POST /api/session/{id}/synthetic`) —
  kein zusätzlicher Schritt, und sie ist für den nächsten Prompt bindend. Der Übergang startet
  einen Turn (Tokens), deshalb: **Settings → „Hand decisions over automatically"** (Standard an,
  abschaltbar) zusätzlich zum bestehenden Freigabe-Schalter. Der manuelle Übergabe-Knopf bleibt.
  Ohne geöffnete Session bleibt die Entscheidung **anhängig** — nichts wird später heimlich
  nachgeschoben.
- **Statuslabel pro Entscheidung im Journal.** Bisher war nicht erkennbar, welche Entscheidung
  schon beim Agenten ist: jetzt steht in jeder Zeile
  **`Injected · …kwMwYk`** (Ziel-Session, kurz — nachvollziehbar),
  **`Injected · … 3 sessions`** (mehrere) oder **`Not injected`** (nur auf dem Handy).
  Ein fehlgeschlagener automatischer Übergang behält „Not injected" und sagt den Grund; der
  Übergang steht zusätzlich im Audit-Protokoll (`Injected`, Detail `automatic`).

### Added (tests)

- `JournalPolicyTest` (8 Fälle, +1): Status „übergeben vs. anhängig", Anzahl der Sessions,
  kurzes Ziel-Kürzel bei einer Session und **kein** willkürliches Kürzel bei mehreren.
- Betroffen geprüft (nur diese Klassen): `JournalPolicyTest` + `CentrePolicyTest` → **20 Tests,
  0 Fehler**.

## [2.0.1] - 2026-10-14

### Fixed

**„Braucht dich" in der Zentrale funktionierte nicht** — gemeldet mit Screenshot. Live gegen
den laufenden Server nachgestellt, drei getrennte Defekte:

1. **Feldtitel statt Frage.** Die Zuordnung ließ `WireForm.title` fallen und die Zentrale nahm
   den Titel des ersten *Feldes*: im Screenshot standen deshalb „Num", „Bool", „Str", „Multi",
   „Multi" — das sind **Feldtypen**, nicht das, was gefragt wird. `SessionQuestion` trägt jetzt
   den Formular-Titel (`GET /api/form` liefert `title: "num" … "multi"` neben
   `fields[].type: "number" … "multiselect"`), und die Zeile nutzt ihn.
2. **Eine Zeile pro Eintrag statt pro Session.** Die Live-Antwort waren **fünf Formulare aus
   einer einzigen Session** (`ses_eca7e402…`) → fünf fast identische Zeilen ohne Kontext. Jetzt
   wird **pro wartender Session** eine Zeile gebildet: bei genau einer Frage steht die Frage
   als Titel und die Session als Detail, bei mehreren ist die Session der Titel und die Zahl
   das Detail („5 open forms"). Ein Berechtigungs-Eintrag nennt weiter die Aktion.
3. **Nur ein Verzeichnis abgefragt.** `GET /api/form` und `GET /api/permission/request` sind
   **pro Verzeichnis** begrenzt (live bewiesen: ein Projekt meldete fünf Formulare, ein anderes
   `[]`). Die Zentrale fragte nur das Verzeichnis der ersten Session ab — wartende Arbeit aus
   jedem anderen Projekt war unsichtbar. Jetzt werden die Verzeichnisse der letzten Sessions
   (max. 5, dedupliziert) **plus** die globale Location abgefragt und die Treffer über die ID
   zusammengeführt.

### Added

- **Aktualisieren** in der Kopfzeile der Zentrale: die Abschnitte lassen sich auffrischen, ohne
  den Bildschirm zu verlassen (vorher nur beim Betreten geladen).
- Ein Wechsel im Postausgang lädt „Braucht dich" neu, statt einen Teil zu patchen — damit kann
  die Liste nicht mehr vom Server abweichen.

### Added (tests)

- `CentrePolicyTest` auf 12 Fälle erweitert: der Live-Fall (fünf Formulare einer Session → **eine**
  Zeile), einzelne Frage (Frage als Titel), unbekannte Session (lesbares Label aus der ID),
  Verzeichnis-Scan über mehrere Projekte, dedupliziert und begrenzt.
- Gesamt im betroffenen Satz: **516 Tests, 0 Fehler**.

## [2.0.0] - 2026-10-14

**Der Sprung auf 2.0 ist kein Etikett: Die App bekommt eine eigene Ebene — die Zentrale —
plus Sicherheit (Not-Aus) und Nachvollziehbarkeit (Audit).** Bis 1.5.x war die App ein
vollständiger Client; ab 2.0 ist sie ein Client, der *antwortet*, wenn mehrere Agenten
gleichzeitig arbeiten — auch wenn sie im Browser oder im TUI gestartet wurden.

### Added — Zentrale (Cockpit)

Neuer Eintrag in der unteren Navigation, ein Bildschirm, drei Antworten:

- **Läuft jetzt:** alle Sessions, die der Server als `busy`/`retry` meldet — laufende zuerst,
  darin die neuesten. Jede Zeile öffnet die Session und hat **Stop**; die Überschrift bietet
  bei ≥1 laufender Session **Alle stoppen** (Rückfrage): unterbricht alle laufenden Turns
  nacheinander und nennt danach, wie viele wirklich gestoppt wurden.
- **Braucht dich:** blockierte Arbeit über *alle* Sessions — offene Berechtigungen, offene
  Fragen (V2-Formulare) und Sendungen, die noch im Offline-Postausgang warten. Ein Tipp
  öffnet die wartende Session.
- **Heute:** die Server-Aggregate des lokalen Tages (Kosten, Sessions, Prompts, Steps) aus
  `GET /api/experimental/session/stats`.
- **Audit:** die letzten 12 Ereignisse, mit **Kopieren** als Markdown-Export.

Die Live-Anzeige kommt aus dem globalen Event-Strom (`session.status`, gefaltet mit
demselben Reducer wie die Session-Liste): ein im Web oder TUI gestarteter Turn erscheint
sofort und verschwindet sofort. Ein HTTP-Schnappschuss pro Besuch/Aktualisieren — **kein
Polling**, also auch kein zusätzlicher Verbraucher im Hintergrund.

### Added — Audit-Protokoll

Lokale, exportierbare Antwort auf „was habe ich wann mit welcher Session gemacht": Stoppen,
Alle-stoppen, Freigaben/Ablehnungen, Revert, Fork und an den Agenten übergebene
Entscheidungen. Neueste zuerst, Deckel 400 Einträge, `commit()` auf IO (übersteht einen
harten Stopp direkt nach der Aktion). **Settings → Audit log** schaltet auf: die Prüfung
sitzt im Store selbst, damit kein Aufrufort sie vergessen kann — aus heißt wirklich nichts
aufzeichnen.

### Fixed

- `CentrePolicy.runningIds` sortiert jetzt selbst: die Reihenfolge der Not-Aus-Liste hing
  sonst davon ab, ob der Aufrufer schon sortiert hatte (vom Test gefunden).
- Beim Audit-Eintrag einer Berechtigung entscheidet der Protokollwert (`reject` vs.
  `once`/`always`), nicht ein einzelner Wert — sonst wäre "immer erlauben" als Ablehnung
  protokolliert worden.

### Added (tests)

- `CentrePolicyTest` (9 Fälle): Reihenfolge (laufend zuerst, dann neueste), Not-Aus-Liste,
  Status-Abbildung inkl. Titel-Rückfall und Kosten, „Braucht dich" mit Berechtigung/Frage/
  Postausgang (und ohne Session = übersprungen), Audit-Deckel/Reihenfolge/Summary/Export/
  Zeitfenster, Kostenformat wie auf den Glance-Flächen.
- Gesamt im betroffenen Satz: **513 Tests, 0 Fehler**.

### Geändert in der Doku

- `docs/FEATURES.md`: neuer §22 „The Centre (cockpit)" und §23 „Audit log".

## [1.5.25] - 2026-10-14

Die offene Paritätsliste wurde gegen die **Settings** geprüft — ein Teil war bereits abgedeckt, der
Rest wurde auf das gedacht, was auf einem Handy Sinn ergibt, reduziert.

### Already covered (geprüft, nicht geändert)

- **„Hide/Show steps" pro Turn** — die App hat dafür *Timeline detail → Compact/Advanced*
  (presetzt „Shell-Aufrufe/Werkzeug-Teile/Thinking aufklappen" als Gruppe) plus die drei einzelnen
  Schalter und aufklappbare Zeilen. Das ist die App-Entsprechung; ein zusätzlicher Turn-Schalter
  wäre doppelt.
- **„Hide non-diff lines"** — gab es schon, aber nur im Changes-Tab und mit eigenem lokalem State,
  während die Chat-Diffs den Kontext immer zeigten. Zwei Bedienwege für eine Sache.

### Changed

- **Eine Einstellung statt zwei Bedienwegen: „Show context lines"** (Settings → Timeline, direkt
  neben „Wrap lines", Standard an). Sie steuert jetzt **Chat und Changes-Tab**; die Schaltfläche im
  Changes-Tab ist nur noch der Schnellzugriff darauf. Aus ist ein Diff nur noch die geänderten
  Zeilen samt Kopfzeilen — auf dem Handy der schnellere Weg durch einen großen Refactor.
- **Die geänderten Dateien einer Runde falten sich jetzt zusammen**: die ersten 8 Dateien, darunter
  **„+N more files"** (antippen → alle) bzw. **„Show less"** (Wortlaut der Referenz,
  `ui.sessionTurn.diffs.more` / `showLess`). Vorher schob eine Runde mit 30 Dateien alles darunter
  aus dem Bild.

### Fixed

- Beim Ausblenden des Kontexts verschwand die Kopfzeile `Index: <datei>` (der neue Test fand es) —
  Kopfzeilen (`Index:`, `diff --git`, `+++`, `---`, `@@`) bleiben immer.

### Deliberately not built (mit Begründung)

- **Split/Unified-Diff umschalten** (`ui.sessionReview.diffStyle.*`): Nebeneinander-Diffs auf einem
  Telefon sind unbrauchbar (zwei Spalten auf 360 px). Die App hat Unified.
- **„Open file"**: Die Dateien liegen auf dem **Server**, nicht auf dem Handy — ein Öffnen in einem
  Editor vor Ort ist damit sinnlos.
- **„Expand all"/„Collapse all"**, Datei-Sidebar, Diff-Byte-Budget: Desktop-Bedienung. Jede Zeile
  und jeder Turn ist einzeln antippbar, das Byte-Budget ist der Zeilen-Deckel.
- **Exit-Code** bleibt außen vor (kein Katalog-Schlüssel, kein Client liest `.exit`).

### Added (tests)

- Kontext-Ausblendung (Kopfzeilen + ±-Zeilen bleiben, Nummern stimmen) und das Falten der
  Dateiliste (8 sichtbar, 4 verborgen, „Show all", kleine Listen unberührt).
- Gesamt im betroffenen Satz: **504 Tests, 0 Fehler**.

## [1.5.24] - 2026-10-14

Grundlage: der opencode-Bundle enthält den **kompletten String-Katalog der Referenz-Clients**
(`ui.*`-Schlüssel, 203 Stück). Die englischen Werte wurden ausgelesen und mit der App verglichen —
nicht geraten.

### Fixed

- **Ein fehlgeschlagener Werkzeug-Aufruf zeigte keinen Grund.** `state.error` wurde nirgends
  gerendert: die Zeile wurde nur rot, blieb eingeklappt, und die Fehlermeldung war nur nach einem
  Aufklappen zu sehen — wo sie ebenfalls fehlte. Jetzt eine Fehler-Karte in der Referenz-Wortlaut
  (`ui.toolErrorCard.*`): **„Failed"** + Meldung des Servers + **„Copy error"**; fehlgeschlagene
  Zeilen starten immer offen (unabhängig von „Expand shell/edit tool parts"). `MessageAbortedError`
  bleibt „Interrupted" wie im TUI.
- **Große Diffs wurden still gekappt.** Jetzt die Warnung der Referenz
  (`ui.sessionReview.largeDiff.*`): **„Diff too large to render"** + **„Limit: N changed lines.
  Current: M changed lines."** + **„Render anyway"**, das den Deckel bewusst aufhebt. Gilt erst ab
  400 *geänderten* Zeilen (der Deckel misst ±-Zeilen wie die Referenz, nicht Kontextzeilen).

### Changed

- **Werkzeug-Namen wie in den Referenz-Clients.** Aus `ui.tool.*` gelesen: `grep` → **Grep**
  (vorher „Search"), `glob` → **Glob** (vorher „List"), `task`/`delegate_task` → **Task** (vorher
  „Subagent"), `webfetch` → **Webfetch**, `websearch` → **Web Search** (beide vorher „Web").
  Read/Write/Edit/Patch/Shell/Skill/Todos unverändert (dort stimmten sie).
- **„N Changed files"** statt „N changed files" — die Referenz schreibt es mit großem C
  (`ui.sessionTurn.diffs.changed.*`); gilt für Chat-Karte und Changes-Tab.

### Researched (bewusst nicht gebaut)

- **Exit-Code eines Shell-Aufrufs:** Der Server liefert `metadata.exit`, aber es gibt **keinen**
  passenden String-Schlüssel im Katalog und **keine** Client-Stelle, die `.exit` liest — weder TUI
  noch Web zeigen einen Exit-Code an. Nichts nachzubauen (im Bundle nachgewiesen).
- **Weitere Referenz-Features, die die App nicht hat** (dokumentiert, nicht gebaut):
  „Hide steps"/„Show steps" pro Turn (`ui.sessionTurn.steps.*`), Diff-Ansicht umschalten
  Split/Unified, „Hide non-diff lines"/„Show all lines", „Expand all"/„Collapse all",
  „Open file" (`ui.sessionReview*`). Die App hat Unified-Diff + Aufklapp-Modus.

### Added (tests)

- Neue Fälle: Fehlertext + Aufklapp-Zustand fehlgeschlagener Zeilen (inkl. „Interrupted"),
  Zählen der ±-Zeilen und die Grenzprüfung des Deckels; Werkzeug-Namen gegen den Katalog
  (`ui.tool.*`) gepinnt. Gesamt im betroffenen Satz: **502 Tests, 0 Fehler**.

## [1.5.23] - 2026-10-14

### Fixed

- **Werkzeug-Badge sagt wie im TUI „Edit"/„Read"/„Search" statt „Called `edit`".** Die Chat-Zeile
  nutzte eine eigene Beschriftung, die es nur dort gab; Badge, Live-Zeile, Benachrichtigung und
  Changes-Karte zeigten jetzt verschiedene Namen für dasselbe Werkzeug. Unbekannte Werkzeuge
  behalten ihre Roh-ID.
- **Die ±-Statistik hinter edit/write-Zeilen und die Changes-Karte waren auf diesem Server leer.**
  Sie lasen `metadata.diff`/`metadata.filepath`; OpenCode 2.0.26 liefert die Daten aber pro Datei in
  `metadata.files[]` (`file`, `additions`, `deletions`, `patch`) und benutzt im Input `path`
  statt `filePath`. Jetzt: alle geänderten Dateien einer Operation (Mehrfach-`edit` listet jede
  Datei), Zählwerte vom Server, sonst aus dem Patch gezählt; `write` liefert Input-Pfad plus alle
  hinzugefügten Zeilen; fehlt der Pfad, steht er in der Patch-Kopfzeile (`Index:`/`+++ b/`).
- **Thinking-Block ohne `[REDACTED]`-Marker.** Der Server ersetzt umgedeckte Stellen so; die TUI
  entfernt die Marker (`replace(/\[REDACTED\]/g,"")`), die App nicht.

### Added

- **„(no output)"** für einen beendeten Shell-Aufruf ohne Ausgabe — die Formulierung des Servers
  (im Bundle nachgelesen) statt einer leeren Box, die wie ein Fehler aussah. Während der Aufruf
  läuft, bleibt es still.
- **Kürzungs-Hinweis mit Speicherort:** „...output truncated…\n\nFull output saved to: `<pfad>`"
  aus `metadata.truncated` + `metadata.outputPath`, exakt die Server-Schreibweise — und ohne
  Doppelung, wenn der Server die Notiz schon in die Ausgabe geschrieben hat.

### Verified (TUI-Parität)

- **„Interrupted"** bei abgebrochenen Nachrichten (TUI ordnet `MessageAbortedError` genauso zu).
- **Keine Schritt-Trenner** zwischen Turns: Der Server sendet `step-start`/`step-finish`, die TUI
  übergeht sie ohne Ausgabe — die App zeigt ebenfalls nichts an (sie nutzt sie nur intern zum
  Zurücksetzen des Live-Puffers).

### Added (tests)

- `ToolChangedFilesTest` (10 Fälle): Mehrfach-`edit` mit den echten Server-Feldern, addieren der
  Zählwerte, Zählen ohne Server-Felder, `write`, ältere Bauform (`metadata.diff`), Pfad aus der
  Patch-Kopfzeile, Werkzeuge ohne Dateiänderung, Badge-Namen, „(no output)", Kürzungs-Hinweis mit
  und ohne Server-Doppelung, `[REDACTED]`.
- Gesamt im betroffenen Satz: **500 Tests, 0 Fehler**.

## [1.5.22] - 2026-10-14

### Changed

- **TUI-Parität als Regel: Wortlaute kommen jetzt aus der TUI/dem Server, nicht aus der App.**
  Die Kürzungs-Notiz für zu lange Tool-Ausgaben lautete app-eigen
  („… (N more characters — copy for the full output)"). Aus dem opencode-Binary ausgelesen ist
  die TUI-/Server-Sprache `… output truncated …` bzw. `omitted N characters`; die Notiz heißt jetzt
  **„… output truncated … (omitted N characters)"**. Die Kürzungs-Notiz bei Diffs
  („… N more lines (truncated)") entsprach dem schon und bleibt.
  - Neuer Test pinnt den Wortlaut (`ToolTextTest`), damit er nicht wieder abdriftet.

### Verified (TUI-Abgleich, Stand jetzt)

Bereits TUI-gleich und so belassen: Diff-Block (zweispaltige Zeilennummern, ±-Tönung,
Kontextzeilen, Hunk-Kopfzeilen — 1.5.21 auf dem Gerät verifiziert) · Tool-Ergebniszeile aus
**Server**-Text („Edited … (1 replacement)") · Titel-Schreibweise der Werkzeuge („Shell", „Read",
„Edit") · Kompakierungs-Block (Wortlaut aus der Web-i18n des Servers) · Queue-Zeile
(„N queued") · Skill-/Synthetic-Block.

### Known gaps (nächste Schritte, in dieser Reihenfolge)

1. Werkzeug-Zeilenkopf: TUI zeigt `Edit  <pfad>` mit Status-Suffix („Replacements: N") — App zeigt
   Titel/Basename, die Server-Zeile steht darunter.
2. Shell-Ausgabe: TUI kürzt mit `... output truncated ...` **und** nennt den Speicherort
   („Full output saved to: …"), wenn der Server die Ausgabe auf Platte gelegt hat.
3. „Thinking"-Block, Schritt-/Turn-Trenner und „Interrupted"-Kennzeichnung am Abbruch.
4. Summary-/Changes-Karte („N changed files").

## [1.5.21] - 2026-10-14

### Fixed

- **Code-Änderungen werden im Chat wieder vollständig gezeigt (wie in der TUI) — mit
  Zeilennummern und Änderungen.** Ursache: Der Renderer las den Diff ausschließlich aus
  `state.metadata.diff`; OpenCode 2.0.26 liefert den Patch aber in
  **`state.metadata.files[].patch`** (derselbe Text, den die TUI druckt, inkl. Hunks und
  Kopfzeilen). Dadurch blieb jede edit-Zeile ohne Diff — man sah nur die Ein-Zeilen-Ausgabe
  („Edited … (1 replacement)"). Live am laufenden Server nachgewiesen.
  Jetzt gilt (in dieser Reihenfolge): `metadata.diff` (andere Builds) → **alle**
  `metadata.files[].patch` → `input.patch` (patch-Tool) → bei **`write`** der geschriebene
  Dateiinhalt als nummerierter, vollständig „hinzugefügter" Hunk. Der vorhandene
  TUI-Renderer (zweispaltige Zeilennummern-Leiste, grün/rot getönte ± Zeilen, Kontextzeilen,
  horizontales Scrollen bzw. Umbruch laut Einstellung, Deckel 400 Zeilen mit Hinweis) zeigt
  damit wieder den ganzen Code.
- **Phantom-Zeile am Ende jedes Diffs.** Der Parser zählte die abschließende Leerzeile (bzw.
  den Trenner zwischen mehreren Patches) als Kontextzeile: eine überzählige Zeilennummer und
  eine zu breite Nummern-Spalte. Leere Zeilen werden jetzt übersprungen — vom Test gefunden.

### Added (tests)

- `ToolDiffTextTest` (5 Fälle): Patch aus `metadata.files[].patch` (mit dem **echten**
  Server-Payload), Zeilennummern aus dem Hunk (`@@ -43,10 +43,10 @@` → 44/44), Vorrang von
  `metadata.diff`, `write` → nummerierter Hunk (1…N), read/shell → kein Diff.
  Gesamt im betroffenen Satz: **489 Tests, 0 Fehler**.

### Fixed

- **Entscheidungen wurden nicht persistent gespeichert (gemeldet).** Drei Ursachen, alle
  behoben — zwei davon hätten den Eintrag verloren, eine ihn nur _versteckt_:
    1. **Stiller Abbruch ohne Projekt-Verzeichnis:** `addJournalEntry` stieg bei
       `session.directory == null` mit `return` aus — **ohne Speichern und ohne Hinweis**.
       Genau das passiert bei Sessions aus dem Offline-Cache (oder ganz neuen), und der Nutzer
       sieht nur „nichts gespeichert". Jetzt greift ein Fallback-Schlüssel
       (`directory` → `path` → global), die Entscheidung wird **immer** gespeichert und mit
       „Decision captured (N in the journal)" **quittiert**.
    2. **Schreiben konnte lautlos ins Leere gehen:** `persist()` tat nichts, solange
       `appContext` noch nicht gesetzt war (Store-Init läuft asynchron). Jetzt gibt es
       `ensureContext(context)` (der ViewModel hat ihn), ein **lauter Fehler-Log** statt
       Stille, und geschrieben wird per `commit()` auf einem IO-Scope — ein schneller
       Force-Stop direkt nach dem Speichern kann den Eintrag damit nicht mehr verlieren.
       Dieselbe Absicherung bekamen `PromptHistoryStore` und `OutboxStore` (identische
       Fehlerklasse).
    3. **Der Filter konnte Einträge verstecken:** `visible()` verglich exakt auf das
       Projekt-Verzeichnis. Ohne Verzeichnis (offline) war die Liste scheinbar leer, obwohl
       Einträge auf der Platte lagen — für den Nutzer nicht unterscheidbar von „nicht
       gespeichert". Jetzt gilt: kein Projektkontext → **alles** anzeigen; mit Projekt →
       eigene **plus** globale Einträge. Nichts, was der Nutzer erfasst hat, kann fehlen.
    - Zusätzlich: der Store lädt nur **einmal** von der Platte (`loadedFromDisk`), damit ein
      spät laufendes `AppStartup` eine bereits erfasste Liste nicht überschreibt.

### Added (tests)

- `JournalPolicyTest`: Projekt-Schlüssel-Fallback (`directory` → `path` → global) und die
  neue Sichtbarkeitsregel (Projekt zeigt eigene + globale; ohne Kontext alles).
  Gesamt im betroffenen Satz: **484 Tests, 0 Fehler**.

### Fixed

- **Absturz beim Öffnen einer Session (NullPointerException) — gefunden über adb, behoben,
  auf dem Gerät verifiziert.** Die vom Nutzer gemeldete Ursache steckte in meiner eigenen
  Änderung aus 1.5.18: Der neue **Glance-Feed** wurde im `init` des `ChatViewModel` gestartet
  und las dort `conversation` — ein `by lazy`-Feld, dessen Delegate zu diesem Zeitpunkt noch
  **null** ist, weil `viewModelScope` mit `Dispatchers.Main.immediate` den Coroutine-Rumpf
  sofort ausführt (wir sind schon auf dem Main-Thread). Ergebnis: NPE bei **jedem** Öffnen
  eines Chats.

    ```
    java.lang.NullPointerException: Attempt to invoke virtual method
        'java.lang.Object n73.getValue()' on a null object reference
      at com.opencode.android.ui.ChatViewModel.getConversation(ChatViewModel.kt:534)   ← retraced
      at com.opencode.android.ui.ChatViewModel.<init>(ChatViewModel.kt:306)
    ```

    **Vorgehen (neu etabliert):** Crash-Buffer per `adb logcat -b crash -d` gelesen, die
    R8-minifizierte Spur mit `cmdline-tools/latest/bin/retrace mapping.txt` **entpackt** — so
    wird aus `mx.s`/`ev.q` wieder `ChatViewModel.getConversation` ← `ChatViewModel.<init>`.
    Zusätzlich `dumpsys dropbox --print` für die Crash-Historie: genau drei Einträge
    (00:05:59, 00:06:04, 00:15:49) mit diesem NPE.
    **Fix:** Der Feed startet jetzt in `loadSession()` (nach der Konstruktion, einmalig per
    Job-Guard) statt im `init`; im `init` berührt nichts mehr spät initialisierte Felder.

    **Geräteverifikation:** `adb install -r` (gleiche Signatur, Daten unangetastet) →
    Deep-Link in eine Session (der abstürzende Pfad) → Prozess lebt, **Crash-Buffer leer,
    0 FATAL**; danach Kaltstart auf Home und ein Hintergrund-/Vordergrund-Zyklus ebenfalls
    ohne Fatal.

### Changed

- **Debugging-Werkzeuge im Repo dokumentiert:** Retrace über das R8-Mapping und
  `dumpsys dropbox` als Standardweg bei Geräte-Crashes (statt Raten aus dem Quelltext).

### Added (tests)

- Betroffene Teilmengen: **482 Tests, 0 Fehler** (der Fix ist eine Initialisierungsreihenfolge;
  die Geräteprüfung oben ist der eigentliche Nachweis).

### Fixed

Zweiter Debug-Durchgang — vier echte Fehler, alle mit Beleg:

- **„Auto-accept permissions" war ein toter Schalter.** Er stand in den Einstellungen (mit
  dem Versprechen „Permission requests will be automatically approved"), wurde aber
  **nirgends** angewandt — Grep über den ganzen Quellbaum: 0 Verwendungen außerhalb des
  Settings-Screens. Jetzt antwortet er offene Freigaben mit `once` (keine dauerhafte Regel
  als Nebenwirkung), **sowohl im Vordergrund** (Session-Liste) **als auch im Hintergrund**
  (Event-Watcher — genau der Fall, für den die Einstellung gedacht ist: ein laufender Turn
  soll nicht unbeaufsichtigt an einer Freigabe hängen bleiben). Antworten werden quittiert
  („Auto-accepted N permission request(s)") bzw. protokolliert.
- **`permission.asked`-Events trugen keine Request-Id.** Der Normalisierer behielt nur die
  Session-Id — dadurch konnte das **ambient „Waiting for you · 1"** auf Widget/Notification/
  Kachel **nie erscheinen** (der Zähler blieb zwangsläufig bei 0) und die Hintergrund-
  Auto-Freigabe hatte keine Id zum Antworten. Beide Event-Varianten (`asked`/`replied`)
  liefern jetzt `requestId` (auch `permission.replied` hat jetzt eine Zuordnung).
- **Die laufende Notification konnte ohne Dienst zurückbleiben.** `refreshConnectionNotification`
  postete die permanente Notification auch bei ausgeschaltetem „Keep connection alive" — das
  Ergebnis war ein nicht wegwischbarer Eintrag ohne Besitzer. Jetzt nur, wenn
  `ConnectionService.isRunning`.
- **Die Quick-Settings-Kachel zeigte ihre Unterzeile nie.** Sie wurde ausschließlich in
  `onClick` geschrieben — also genau in dem Moment, in dem die Kachel ins App-Overlay
  zusammenklappt. Neu: Aktualisierung in `onStartListening`, wenn das Panel geöffnet wird.
- **Explain-Karte beim Session-Wechsel:** eine laufende Seitenfrage blieb in der _neuen_
  Chat-Ansicht stehen (sie gehörte zur alten). Sie wird jetzt mit der Konversation verworfen.

### Added (tests)

- `SseEventDecoderTest`: pinnt `requestId` auf `permission.asked` **und** `permission.replied`
  — genau die Zusicherung, an der der Ambient-Zähler und die Auto-Freigabe hängen.
  Gesamt im betroffenen Satz: **486 Tests, 0 Fehler**.

### Fixed

Sechs echte Fehler im Debug-/Härtungsdurchgang über die zuletzt gebauten Features:

- **„Send now" (Outbox) tat nichts, wenn der Auto-Versand ausgeschaltet war.** Der
  Composer reichte `viewModel::flushOutbox` als Methodenreferenz durch — Kotlin setzte
  dabei den Default `manual = false` ein, also hing der _ausdrückliche_ Knopf an dem
  Schalter, den er umgehen soll. Jetzt ruft er explizit `flushOutbox(manual = true)`.
- **Schalter wirkten erst nach einem Neustart.** „Keep connection alive", „Background
  monitoring" und „Live status surfaces" wurden nur beim Start gelesen: Ausschalten ließ
  einen _laufenden_ Watcher weiterarbeiten bzw. den Foreground-Service stehen. Jetzt
  werden sie am Schalter angewandt — Service starten/stoppen (samt permanenter
  Notification), Watcher sofort beenden, Glance-Fakten zurücksetzen.
- **Ausgeschaltete Live-Oberflächen zeigten weiter den letzten Zustand.** Ein Widget mit
  eingefrorenem „Step 7 · 42s" ist schlechter als ein statisches: `GlanceStore.reset()`
  leert Faktensatz und persistierten Schnappschuss, wenn die Funktion abgeschaltet wird.
- **Wettlauf- und Erklär-Sessions konnten ewig pollen.** Ein Member, dessen Turn nie endet
  (Provider-Hänger, Server-Schluckauf), hielt den Controller für die Lebensdauer der App
  am Laufen — Batterie plus eine Karte, die ewig „läuft" aussieht. Beide haben jetzt eine
  Frist (Race 20 min, Explain 3 min) und melden danach sauber „failed"; ein Test pinnt es.
- **Outbox-Flush teilte sich den Job-Slot mit dem normalen Senden.** Ein Session-Wechsel
  während des Flushs brach ihn ab, und die Abschluss-Aktualisierung konnte die Queue der
  _neu_ geöffneten Session mit den Resten der alten bemalen. Jetzt eigener Job-Slot und
  Session-Fence beim Abschluss.
- **Glance-Schreiblast:** Der Live-Zustand rief die Faktensammlung mehrere Male pro Sekunde
  auf und schrieb jedes Mal (nur der Zeitstempel änderte sich) → unnötige StateFlow-Emissionen.
  Ein reiner Zeitstempel-Wechsel wird jetzt übersprungen.

Zusätzlich: Der Übergabe-Knopf im Entscheidungs-Journal ist sichtbar deaktiviert und
beschriftet („Hand-over disabled in settings"), wenn die Einstellung aus ist — statt erst
beim Antippen abzulehnen.

### Changed

- **Prozess:** Code-Änderungen laufen wieder ausschließlich über das `edit`-Werkzeug
  (exakte Übereinstimmung, eine Fundstelle) bzw. `write` für neue Dateien; Shell nur noch
  zum Lesen/Untersuchen. Die Python-Serien-Skripte hatten in 1.5.9 bereits einen echten
  Fehler verursacht (verlorener Thinking-Schalter) und danach einen stillen
  „nichts-passiert"-Lauf.

### Added (tests)

- `RaceControllerTest`: dritter Fall — ein hängender Wettlauf wird nach der Frist
  aufgegeben, statt ewig zu pollen (Mitglieder werden als failed markiert). Gesamt im
  betroffenen Satz: **485 Tests, 0 Fehler**.

### Added

- **Always-on-Glance — Statuslicht statt App.** Widget, die laufende Notification des
  Keep-alive-Dienstes (lockscreen-tauglich, nur Zähler) und die Quick-Settings-Kachel
  zeigen jetzt den **Live-Zustand**: „Waiting for you · 1", „Stuck · 2 failures",
  „Step 7 · Shell · 42s", dazu „Today · $3.06" aus dem bisher ungenutzten
  `GET /api/experimental/session/stats?from=<heute>`. Alle drei leiten aus **einer**
  Faktensammlung (`GlanceStore`) ab, damit sie sich nie widersprechen; Aktualisierungen
  sind **ereignisgetrieben und gebucket** (Sekunden werden in 10-s-Schritten verglichen) —
  lebendige Zeile ohne Sekunden-Wake-ups.
- **Erklär-Modus — Seitenfrage in eigener Session.** Über das Nachrichtenmenü
  („Explain this message") läuft die Frage mit dem passenden Agenten (`docs` für Code,
  `ask` sonst) in einer **eigenen Session**; der Prompt trägt nur den Ausschnitt (Cap
  6 000 Zeichen) und verbietet Tools/Änderungen ausdrücklich. Die Antwort erscheint als
  Karte im Verlauf („Use in prompt" / „Open session" / schließen) — der Arbeitsverlauf
  bleibt unberührt.
- **Entscheidungs-Journal — Beschlüsse hoheitlich mitgeben.** Beschlüsse werden pro Projekt
  lokal festgehalten (kostenlos) und auf Wunsch **an den Agenten übergeben**: das injiziert
  eine `synthetic`-Nachricht (`POST /api/session/{id}/synthetic`) und erscheint als eigener
  Block im Verlauf. Live verifiziert: der Agent behandelt sie als **bindenden Kontext** für
  spätere Prompts — und weil die Nachricht zugestellt wird, **startet sie einen Turn**
  (Tokens; die UI sagt das vorher). Menüeintrag „Decisions (N)" mit Erfassen, Übergeben,
  Löschen und Markdown-Export.
- **Alle dauerhaften/automatischen Funktionen sind jetzt schaltbar** (Settings → General →
  **Live & background**): Live-Status-Oberflächen, Hintergrund-Überwachung (aus = nichts
  läuft im Hintergrund), Verbindung am Leben halten (Foreground-Service), Erklär-Modus,
  „Beschlüsse an den Agenten übergeben", Model Race, Outbox automatisch senden. Ist eine
  Funktion aus, meldet sie „Disabled in settings: …" statt heimlich zu arbeiten.

### Added (tests)

- `GlanceStatusTest` (Präzedenz Waiting > Stuck > Running > Idle, Fortschrittszeile,
  Kostenformat, Dedupe in 10-s-Schritten), `ExplainPolicyTest` (Agentenwahl, Prompt-Vertrag
  „no tools", Snippet-Cap) und `JournalPolicyTest` (Projekt-Scope, Cap, Einmal-Markierung
  der Übergabe, Suche, Markdown-Export). Gesamt im betroffenen Satz: **480 Tests, 0 Fehler**.

## [1.5.15] - 2026-10-13

### Fixed

- **Die Prompt-History startete leer — jetzt holt sie den Verlauf nach.** Sie zeichnet
  naturgemäß nur auf, was **diese App** sendet, also fehlten Prompts aus der Web-UI/TUI und
  alles, was vor dem Feature gesendet wurde. Jetzt:
    - Beim Öffnen einer **leeren** History werden die User-Nachrichten der aktuellen Session
      einmalig importiert (bereits geladene Nachrichten, kein Extra-Request).
    - **„Import from all sessions"** liest die letzten Sessions des Projekts
      (`GET /api/session/{id}/message`), begrenzt auf **20 Sessions × 60 Nachrichten** —
      kein Request-Sturm auf einer Handyverbindung.
    - Beide Schemas werden gelesen (`role`/`info.role` classic, `type` V2), Attachments-only-
      Turns übersprungen, aufeinanderfolgende Wiederholungen zusammengefasst.
    - Importierte Einträge behalten die **Server-Zeitstempel** und werden **nach Zeit**
      gemischt — ein älterer Web-Prompt rutscht nie über einen neueren aus der App (vom
      Unit-Test aufgedeckt: der erste Entwurf hat jeden Import oben eingefügt).

### Added (tests)

- `PromptHistoryImportTest` (6 Fälle: beide Schemas, Attachment-only, Wiederholungen,
  Zeitstempel-Reihenfolge, bestehende App-Einträge bleiben, fehlender Zeitstempel).

## [1.5.14] - 2026-10-13

### Added

- **Prompt-History (TUI-Parität).** Die TUI merkt sich gesendete Prompts und holt sie mit
  den Pfeiltasten zurück; auf einem Touchgerät gibt es dafür jetzt einen
  **History-Button im Composer**, der erscheint, solange das Feld **leer** ist (also genau
  dann, wenn Zurückholen sinnvoll ist — und nie mit dem Queue-Button konkurriert, der beim
  Tippen auftaucht):
    - Liste **neueste zuerst**, ein Eintrag pro verschiedenem Prompt, umschaltbar zwischen
      **dieser Session** und **allen Sessions**, mit Suche (Groß-/Kleinschreibung egal).
    - Auswahl **fügt in den Composer ein** (TUI-Verhalten: zurückholen, nicht senden) — der
      Prompt kann also bearbeitet, eingereiht oder als Aufgabe eines **Model Race** genutzt
      werden.
    - Aufgezeichnet wird, wenn der Server einen Prompt akzeptiert: normaler Send, Queue-Send
      und Outbox-Zustellung. **Cap 100**, persistent, und ein erneut gesendeter neuester
      Prompt **rutscht nach oben statt sich zu duplizieren**.
    - Regeln und Speicherformat sind pur und getestet (`PromptHistoryPolicy`,
      `PromptHistoryCodec`): Blank wird ignoriert, gleicher Text in einer anderen Session ist
      ein eigener Eintrag, die Anzeige ist dedupliziert und gefiltert.

### Added (tests)

- `PromptHistoryPolicyTest` (7 Fälle: Aufnehmen, Dedupe des neuesten, session-übergreifend,
  Blank, Cap, Sichtbarkeit/Scope, Suche) und `PromptHistoryCodecTest` (Round-trip;
  kaputtes JSON → leere History).

## [1.5.13] - 2026-10-13

### Added

- **Model Race — ein Prompt, mehrere Modelle, direkter Vergleich (neues Feature).**
  Recherche in vergleichbaren Mobile-Clients (Vibenary/OpenChamber/opencode-mobile)
  zeigte Voice, Terminal, Git- und Worktree-Funktionen — aber **keine** Möglichkeit,
  Modelle auf der _eigenen_ Aufgabe zu vergleichen. Genau das baut der Race ein:
    - Einstieg über **⋮ → „Race models…"**: der Prompt aus dem Composer wird zur Aufgabe,
      man wählt **2–3 Modelle** (Mehrfachauswahl, mit Kostenhinweis).
    - Jedes Modell läuft in einer **eigenen Session im selben Projekt** und wird per
      `POST /api/session` **mit seinem Modell erzeugt** — kein Fenster, in dem ein Member
      mit dem falschen Modell startet. Danach je ein `POST …/prompt`, Ergebnisse über
      `GET …/{id}` (Kosten/Tokens/Status) und `GET …/{id}/message` (Antwort).
    - Im Verlauf erscheint die **Vergleichskarte**: Antwort, Laufzeit, Kosten und Tokens
      pro Modell, markiert als **cheapest**/**fastest**, plus **Stop all** und
      **Dismiss**; ein Tipp öffnet die Session des Gewinners, um dort weiterzuarbeiten.
    - Regeln pur und getestet (`RacePlan`): Prompt nötig, mindestens zwei **verschiedene**
      Modelle, unbekannte Modelle werden abgelehnt (keine Phantom-Session), **Deckel bei
      drei** Membern — jeder Member ist ein voller Turn auf dem eigenen Key.
    - Live verifiziert: derselbe Prompt an `deepseek-flash` (0,0053 $) und
      `deepseek-v4-pro` (0,0234 $), beide in ~3 s beantwortet.

- **Offline-Outbox — Prompts überleben eine tote Verbindung (zweites neues Feature).**
  Auf dem Handy ist die Verbindung oft kurz weg (VPN-Handshake, Funkloch, Tunnel,
  Server-Neustart). Bisher wurde der Text in den Composer zurückgelegt und ein Fehler
  gezeigt — jetzt wird der Prompt **behalten und automatisch zugestellt**:
    - Nur **Konnektivitätsfehler** landen in der Outbox (UnknownHost, Connection
      refused/reset, Timeout, `HTTP 5xx`/429, Socket-Ausnahmen). Eine echte Ablehnung
      (`HTTP 4xx`, Guard-Konflikt, fehlendes Modell) wird weiterhin gemeldet und **nie**
      eingereiht — ein abgelehnter Prompt würde sonst ewig wiederholt.
    - Zeile über dem Composer: „N waiting — sent automatically once the server is
      reachable" mit **Send now** und **Discard all**.
    - Zustellung erfolgt automatisch sobald Android wieder ein Netz meldet
      (`NetworkMonitor`), beim Öffnen der Session und manuell — **in Reihenfolge**, mit
      Stopp beim ersten Fehler (kein Überholen abhängiger Folge-Prompts), Backoff
      2 s → 30 s, max. 5 Versuche, danach geparkt mit sichtbarem letzten Fehler.
    - **Pro Session** (max. 10 Einträge, älteste fallen) und **persistiert** — ein
      App-Neustart verliert nichts. Anhänge werden nicht eingereiht (URIs können später
      weg sein); ein solcher Send schlägt wie bisher fehl.

### Fixed

- **Race-Controller las Antworten nur über `role`.** V2-Nachrichten tragen
  `type` (classic: `role`/`info.role`) — der neue `RaceControllerTest` gegen einen
  Mock-Server hat das aufgedeckt, bevor es auf einem Gerät auffiel: jede geraste
  Antwort wäre leer geblieben.

### Added (tests)

- `RacePlanTest` (7 Fälle: Prompt-/Modell-Validierung, Duplikate, unbekanntes Modell,
  Labels, Deckel, cheapest/fastest aus beendeten Membern) und `RaceControllerTest`
  (Fan-out gegen MockWebServer: eine Session pro Member mit Modell im Create,
  ein Prompt pro Member, Antwort/Kosten/Tokens-Auswertung, Interrupt für **jeden**
  Member beim Stop). **Zweites Feature:** `OutboxPolicyTest` (7 Fälle: nur
  Konnektivität ist retryable, `HTTP 4xx` nicht, 429 schon, Backoff-Verdopplung mit
  Deckel, Attempt-Gate, Cap pro Session) und `OutboxCodecTest` (Round-trip inkl.
  Auswahl-Feldern; kaputtes JSON → leere Outbox statt Absturz).

## [1.5.12] - 2026-10-13

### Added

- **Neuer Befehl `/skills` — 1:1 wie in der TUI.** Technisch aus dem TUI-Binary
  ausgelesen (der Befehl ist dort ein clientseitiger Slash-Eintrag
  `{action:"skill-menu", display:"/skills", description:"browse available skills"}`,
  der den Composer leert und die Skill-Liste öffnet) und dann live gegen den Server
  verifiziert:
    - Auswahl injiziert den Skill per **`POST /api/experimental/session/{id}/skill`**
      mit `{"id":"<skill>"}` → **204**. Wichtig: der im TUI-SDK definierte Pfad
      `/api/session/{id}/skill` antwortet auf 2.0.26 mit **404** — nur die
      Experimental-Route injiziert.
    - Der Server sendet `session.skill.activated`, legt eine **`skill`-Nachricht** an
      und **startet einen Turn** mit dem Skill.
    - Die App zeigt die Skill-Nachricht als eigenen **Skill-Block** (Name + injizierter
      Text, eingeklappt mit „Show skill text"), lädt den Verlauf nach dem Event nach
      und meldet „Skill „X" injected".
    - Der Picker listet Skills aus `GET /api/skill` (Name, Id, Beschreibung
      durchsuchbar — dieselben Felder, die die TUI-Palette indexiert) mit klarem
      Leerzustand („No skills installed"). Der Eintrag wird nur angeboten, wenn die App
      ihn auch ausführt; ein Server-Befehl namens `skills` hätte Vorrang (TUI-Regel).

### Added (tests)

- `ExtensionsContractTest`: Skill-Injektion pinnt die Experimental-Route, den Body
  `{"id":…}` und den 404-Fall (`false` statt Absturz). Bestehendes
  `BuiltinCommandsTest` erzwingt weiterhin, dass Liste und Handler nicht auseinanderlaufen.

## [1.5.11] - 2026-10-13

### Added

- **Settings → Extensions: MCPs, Plugins und Skills 1:1 wie im Web.** Die Web-Settings
  per Playwright analysiert (`/settings` mit Preferences/Appearance/Notifications/
  Shortcuts/Projects/Worktrees/Providers/Models/**Extensions**/Server/About) und die
  Extensions-Untertabs nachgebaut:
    - **MCPs** — pro Server eine Zeile mit Status (Connected/Disabled/Failed/Needs
      authentication/Starting…), Anzahl der Ressourcen und **Schalter**. Der Schalter
      ruft genau einen Endpunkt auf (aus dem Web-Bundle gelesen und live verifiziert):
      `POST /api/experimental/mcp/{name}/connect` bzw. `…/disconnect` (204), Liste über
      `GET /api/mcp`. Optimistisch geschaltet und danach nachgelesen, damit eine
      abgelehnte Änderung nicht wie Erfolg aussieht; Server mit Auth-Bedarf haben ihre
      eigene Aktion.
    - **Plugins** — `GET /api/plugin` (Id, Quelle, Status, Feature-Flags), bewusst
      read-only wie im Web („Manage in opencode.json").
    - **Skills** — `GET /api/skill` (Name, Beschreibung, durchsuchbar). Der alte dünne
      Skills-Dialog im Server-Tab ist entfallen — Skills leben jetzt an einer Stelle.
    - Ressourcen (`GET /api/mcp/resource`) stehen als Detail in der jeweiligen MCP-Zeile,
      plus eine Zählerzeile („MCPs N/M connected · P plugins · S skills"), die „nichts
      installiert" von „Laden fehlgeschlagen" unterscheidet.
- **Globaler Notifications-Schalter** („Enable notifications"): ein Master-Schalter
  über allen Benachrichtigungen und Tönen. Er wirkt zentral in `Notifier` (ein neuer
  Benachrichtigungspfad kann ihn nicht vergessen) und stoppt auch das Sammeln des
  Hintergrund-Watchers; der Untertext sagt „Notifications and sounds are off", wenn er aus ist.
- **Preferences-Lücken geschlossen** (Web: Settings → Preferences):
  **Follow-up behavior** (queue | steer), **Timeline detail** (compact | advanced als
  Preset über die drei Aufklapp-Schalter) und **Wrap lines** (Diffs und Code brechen um
  statt seitlich zu scrollen). „Steer" ist live verifiziert: der Server nimmt den Prompt
  während eines laufenden Turns an, **ohne** zu unterbrechen — die App unterbricht dafür
  nicht mehr und lässt die Live-Anzeige stehen.

### Added (tests)

- `ExtensionsContractTest` — Toggle eines MCP-Servers (connect/disconnect, genau ein
  Aufruf, exakter Pfad), MCP-Liste mit Status, Plugin- und Skill-Parsing inkl.
  Feature-Flags, MCP-Ressourcen pro Server.

## [1.5.10] - 2026-10-13

### Fixed

- **Der Schalter für Thinking fehlte in den Einstellungen.** Er war in 1.5.9 durch
  einen Fehler im Bearbeitungsschritt nie in `SettingsScreen.kt` geschrieben worden
  (zwei Änderungen an derselben Datei, die zweite überschrieb die erste) — der Chat
  las die Einstellung, sie war aber nirgends einstellbar. Jetzt steht „Expand
  thinking blocks" in **Settings → General** direkt unter den Tool-Schaltern; der
  Untertext nennt auch die Abhängigkeit („Needs 'Show reasoning summaries' for
  thinking to appear at all").
- **„Hide Session Guard in chat" ließ sich nicht einschalten.** Der Schalter las
  `AppSettingsStore.state.value` direkt statt den gesammelten Flow: getippt wurde
  zwar geschrieben, aber nichts neu zusammengesetzt — der Schalter blieb optisch aus.
  Er bekommt jetzt den gesammelten Zustand (wie alle anderen Schalter) und ist
  **immer bedienbar**. Die Guard-Beschränkung bleibt inhaltlich: Guard-Chrome gibt es
  im Chat nur, wenn wirklich ein Guard läuft — ohne Guard blendet der Schalter also
  nichts aus (der Untertext sagt das dann).
- **Dieselbe Fehlerklasse daneben behoben:** der Live-Reasoning-Block las
  `showReasoningSummaries` als `.value`, sodass ein Umschalten in den Einstellungen
  einen bereits aufgebauten Chat nicht erreichte — es sah aus, als tue die
  Einstellung nichts. Er sammelt den Zustand jetzt reaktiv.

## [1.5.9] - 2026-10-13

### Added

- **Neue Einstellung „Expand thinking blocks"** (Settings → General, direkt bei den
  Tool-Schaltern): steuert, ob „Thinking…" standardmässig **ausgeklappt** (an) oder
  **eingeklappt** (aus, Default) startet — für den laufenden Stream und für die
  Historie. Bisher war der Live-Thinking-Block immer aufgeklappt und nicht
  einklappbar; jetzt hat er dieselbe Kopfzeile mit „Thinking ▲/▼" wie der
  historische Block (inkl. Streaming-Caret in der Kopfzeile, solange Tokens
  laufen), und ein Tipp schaltet für diesen Turn um.
- **Neue Einstellung „Hide Session Guard in chat"** (Settings → Session Guard):
  blendet die Guard-Zeile und ihren Dialog im Chat komplett aus, **ohne** die
  Funktion abzuschalten (der Proxy pinnt weiter). Der Schalter ist **nur aktiv,
  wenn ein Session Guard erkannt wurde** (die Health-Probe antwortet) und bleibt
  bedienbar, solange er an ist, damit ein verschwundener Guard keine Einstellung
  blockiert. Beide Einstellungen sind im JSON-Export/-Import enthalten.

### Changed

- **Session-Namen im Home bleiben einzeilig und sliden durch.** Die zweizeilige
  Umbrechung aus 1.5.7 machte die Liste unruhig (Titelwechsel verschoben die Zeilen);
  jetzt ist der Titel strikt einzeilig (`maxLines = 1`, kein Umbruch) und läuft bei
  Überlänge **animiert durch** (`basicMarquee`, unendlich mit kurzer Start- und
  Wiederholungspause, 24 dp Abstand zwischen den Durchläufen). Kurze Titel bleiben
  stehen — animiert wird nur, was wirklich überläuft.

### Changed

- **Message-Queue weiter ausgebaut.** Sie war funktional, aber ohne Rückmeldung:
    - Die eingereihte Nachricht erscheint **sofort** als Queue-Karte (die Id der
      Anfrage, damit der folgende Inbox-Read zusammenführt statt zu doppeln); schlägt
      das Senden fehl, verschwindet die Karte wieder und der Text steht im Composer.
    - **Zähler an zwei Stellen**: ein **Badge am Queue-Button** und eine Zeile
      **„N queued“** im Composer (Wortlaut des Webs: „{{count}} queued“), plus ein
      `N queued`-Kopf über den Queue-Karten im Verlauf — die Queue ist damit nie
      unsichtbare Chrome.
    - Die Deep-Link-Scrollrechnung berücksichtigt den neuen Kopf (Springen zur
      Nachricht bleibt exakt).
- **End-Summary nennt die Queue**: „… · 2 queued“, wenn nach dem Turn noch Prompts
  warten (`notif_finished_queued`) — die Benachrichtigung ist damit ein Hinweis mit
  nächstem Schritt statt einer Sackgasse.
- **Live-„Thinking…" meldet seinen Zustand** an Screenreader (`stateDescription`
  wie beim historischen Block).

### Documentation

- **Detailed feature documentation (`docs/FEATURES.md`).** 22 sections covering every
  user-visible capability — chat/streaming, the composer, the message queue, slash
  commands, attachments/share/files, session info, Changes, permissions & questions,
  models/agents/variants, providers/credentials, MCP & skills, notifications
  (channels, background step progress, finished summary, deep links), widget/tile,
  appearance, a full settings reference, the optional session guard, privacy, the
  web/TUI parity table (what was verified against a running server) and a
  feature → endpoint map — plus honest limits (no push service, `dataSync` budget,
  locally stored archive, queue without attachments, paged messages).
- **README** — the Features section now leads with the concrete behaviours users ask
  about (detailed live status, tappable links, fine-grained selection, the queue and
  its Steer/Remove actions, compaction output) and links to the detailed document.
- **`docs/OPENCODE-API.md`** — new section with the verified prompt/queue/compaction
  and form contract (`delivery: steer|queue`, the inbox endpoints and semantics,
  `POST /compact` with a client-generated id, typed form answers), the inbox events,
  the full compaction-message shape, and the OpenAPI version re-checked against
  2.0.26.
- **`docs/ARCHITECTURE.md`** — cross-links the feature documentation.
- **`CHANGELOG.md`** — two entries about the "/" picker had lost their version
  heading and sat orphaned above the newest release; they are back inside
  [1.1.1] where they shipped.

## [1.5.8] - 2026-10-13

### Added

- **Message-Queue im Composer — 1:1 wie das Web (Ctrl+Enter).** Per Playwright gegen den laufenden
  Server analysiert: Ctrl+Enter sendet `POST /api/session/{id}/prompt` mit **`"delivery":"queue"`**
  (sonst identischer Body) und legt die Nachricht in die Session-**Inbox**, statt den laufenden Turn
  zu unterbrechen. Das ist jetzt in der App:
    - **Zweiter Button links neben Senden/Stopp**, nur sichtbar solange im Composer Text steht
      (animiert ein-/ausblendend) — Tipp darauf reiht die Nachricht ein, ohne den laufenden Turn
      abzubrechen. Fehler geben den Text in den Composer zurück.
    - **Queue-Anzeige im Verlauf:** die Inbox-Einträge werden als Karten mit dem Web-Wortlaut gezeigt —
      Badge **„Queued“** (bzw. **„Steer“**, wenn schon freigegeben), Nachrichtentext und die beiden
      Aktionen, die der Server anbietet: **„Steer“** (sofort ausliefern, `PATCH …/inbox/{id}`
      `{delivery:"steer"}`) und **„Remove“** (`DELETE …/inbox/{id}`).
    - **Auslieferung** macht der Server: sobald der laufende Turn endet, wandert der älteste Eintrag
      als echte Nachricht in den Verlauf (live verifiziert: Enqueue → Inbox → Steer → Remove →
      Auslieferung, Inbox danach leer). Bei idle Session wird sofort ausgeliefert.
    - **Nachladen** der Queue bei `session.inbox.delivered/cancelled/delivery.changed`
      (neuer `StreamEffect.RefreshInbox`), beim Session-Load, nach jedem Queue-Vorgang und am
      Turn-Ende.
    - Anhänge sind auf dem Queue-Pfad nicht möglich (der Inbox-Eintrag müsste die Base64-Teile
      mitführen); statt die Dateien still zu verlieren, weist die App darauf hin, direkt zu senden.

### Added (tests)

- `InboxQueueTest` (Inbox lesen mit Verzeichnis-Header, DELETE-Pfad, PATCH-Body `{"delivery":"steer"}`,
  Fehler → `false` statt Exception), `PromptRequestBuilderTest` (+ Queue-/Direkt-Send-Fälle),
  `SseEventDecoderTest` (Inbox-Übergänge → `session.inbox.changed`), `StreamReducerTest`
  (`RefreshInbox`) — 424 Tests grün in den betroffenen Teilmengen.

## [1.5.7] - 2026-10-13

### Added

- **Hintergrund-Benachrichtigungen mit Deep-Link in die Session.** Läuft eine Session, während die App
  nicht im Fokus ist, kommt jetzt _ab und zu_ eine Zwischenschritt-Meldung und am Ende die
  Zusammenfassung — ein Tipp darauf öffnet **genau diese Session an genau dieser Stelle**:
    - **Zwischenschritte (gedrosselt):** nach einem abgeschlossenen Schritt, höchstens alle 45 s und
      höchstens 4× pro Turn, mit der Arbeit dieses Schritts in der Zeile („Step 2 · Shell · ls -la,
      Read · ChatViewModel.kt“). Schritte ohne Werkzeug werden übersprungen, wenn schon etwas gemeldet
      wurde — sonst gäbe es nichts Neues zu sagen.
    - **End-Summary:** wenn der Turn endet, mit der Antwort selbst (erste Zeile, gekürzt) und dem
      Fehlertext, falls der Turn mit einem Fehler endete. Die Summary ersetzt die letzte
      Zwischenmeldung derselben Session, statt sich daneben zu stapeln; pro Turn genau eine Meldung
      (mehrere Producer melden dasselbe Turn-Ende).
    - **Nur im Hintergrund:** die Erkennung „App im Fokus?“ läuft über den Activity-Lebenszyklus
      (Zähler der gestarteten Activities). Im Vordergrund ist alles still — dort sieht man die Antwort
      ohnehin. Sobald die App in den Hintergrund geht, läuft der Sammler an, im Vordergrund wieder aus
      (kein Dauer-Stream ohne Nutzen).
    - **Tap → Session + Nachricht:** die Notification trägt Session- **und** Message-Id; der Chat
      scrollt einmal zu genau dieser Nachricht (Indexberechnung für das reverse-Layout, rein und
      getestet; ist die Nachricht nicht geladen, landet man trotzdem in der richtigen Session).
    - **Eigener Kanal „Agent step progress“**, damit Zwischenmeldungen stumm geschaltet werden können,
      ohne die fertige Antwort zu verlieren. Die End-Summary nutzt weiter den Agent-Kanal (mit Ton
      nach Einstellung), Zwischenschritte sind absichtlich still.
    - Der Tool-Name kommt jetzt aus einer einzigen Quelle (`ToolText`): Chat-Zeilen und
      Benachrichtigungen zeigen dieselbe Schreibweise („Shell“, „Read“, „Edit“, …).

### Notes

- Ohne Weckdienst (kein Push) können Benachrichtigungen nur zugestellt werden, solange der Prozess
  lebt; dafür sorgt der bestehende `ConnectionService` (Foreground-Service, während die App offen
  ist). Wird die App vom System beendet, endet auch der Strom — beim nächsten Öffnen wird
  aufgeschlossen, nicht rückwirkend benachrichtigt.

### Added (tests)

- `SessionAlertPolicyTest` (Drosselung, Deckel pro Turn, Werkzeugliste, Turn-Reset),
  `AppVisibilityTest` (Foreground-Übergänge inkl. Mehrfach-Activity), `DeepLinkScrollTest`
  (Scroll-Index im reverse-Layout), `ToolTextTest` (Titel-Schreibweise, Kürzung), plus
  `SseFilterTest`: der globale Verbraucher bekommt Schritt-/Tool-Frames, aber **nicht** den
  Token-Strom.

## [1.5.6] - 2026-10-13

### Fixed

- **Live-Status zeigt jetzt wirklich, was läuft.** Der Statustext blieb bei „Waiting for the model…“,
  obwohl Werkzeuge liefen. Ursache (mit einer echten Server-Spur belegt und als Test eingefroren):
  Der Server zerlegt einen Tool-Aufruf in mehrere Events; das spätere Event überschrieb das frühere,
  wodurch der **Werkzeugname verloren ging** (die Zeile las „Running · echo hallo“) — und der
  Schrittzähler wurde nie hochgezählt, sodass zwischen den Schritten immer das generische Label
  stand. Jetzt: Werkzeugname bleibt erhalten (Titel-Case wie im Web), jeder Schritt wird gezählt
  („… · step 2“), und die Warte-Phase nennt das Modell („Waiting for V4 Flash…“) statt eines
  nichtssagenden Textes. Tool-Details werden mit sichtbarer Auslassung gekürzt statt still
  abgeschnitten.
- **`/compact` bildet das Web 1:1 ab.** Per Playwright gegen den laufenden Server geprüft: das Web
  sendet `POST /api/session/{id}/compact` mit Body `{"id":"msg_…"}` — einer **client-generierten**
  Id, ohne Modell. Die App sendete einen leeren Body (der Server erfand die Id) und warf die
  Antwort weg; außerdem war der Verzeichnis-Header URL-kodiert, sodass der Server nach einem
  Ordner namens `%2Fhome%2F…` suchte. Jetzt: gleiche Anfrage wie das Web, und der Befehl hat
  **sichtbaren Output**: „Session compaction queued“ direkt nach dem Absenden, danach der
  Compaction-Block wie im Web — Trennlinie „Session compaction started“, die generierte
  Zusammenfassung als Markdown, beim Laufen Spinner + „Compacting“ und am Ende
  „Session compacted · 34,997 in · 140 out“ (bzw. „by provider“, „failed“, „cancelled“,
  „interrupted“). Die Nutzungszeile rechnet Cache-Reads als Input und Reasoning als Output, wie das Web.

### Changed (10 kosmetische Verbesserungen)

1. Modell-Wechsel im Chat nutzt dieselbe Trennlinien-Optik wie der Compaction-Block (vorher eine
   nackte zentrierte Zeile).
2. Werkzeugtitel in allen Tool-Zeilen und im Detail-Dialog in Titel-Schreibweise wie im Web
   („Shell“, „Read“, „Edit“, …) statt roher Kennung.
3. Werkzeugname im Live-Status ebenfalls Titel-Schreibweise.
4. Anhang-Miniatur nutzt die Shape-Skala statt eines magischen 4-dp-Radius.
5. Berechtigungs-Banner: Abstände aus der Spacing-Skala, Meta-Icon auf einheitlichen 16 dp.
6. Tool-Karten: alle Mikro-Abstände aus der Spacing-Skala statt 2-dp-Literale.
7. Projekt-/Session-Titel brechen auf zwei Zeilen um, statt nach einer Zeile abzuschneiden.
8. Änderungs-Tab: eine gepolsterte Trennlinie statt zwei Spacer um eine nackte Linie — gleicher
   Rhythmus wie alle anderen Abschnitte.
9. Agent-Name im Live-Status in Titel-Schreibweise („Build“) wie der Agent-Chip im Web.
10. „Compacting“-Zeile nutzt dieselbe Typo-Skala wie die Live-Status-Zeile.

### Added

- Tests: `LiveStatusTraceTest` (echte Wire-Spur eines kompletten Turns durch Normalizer + Filter +
  Reducer, inkl. Regression-Pins für Werkzeugname und Schrittzähler), `CompactionMessageTest`
  (Ergebnis-Wortlaut und Nutzungszeile), sowie ein neu geschriebener `CompactContractTest`
  (Body `{"id":…}`, roher Verzeichnis-Header, Antwort als Compaction-Nachricht mit Status/Summary/Tokens).

## [1.5.5] - 2026-10-13

### Fixed

- **Links im Chat sind jetzt überall antippbar und öffnen im Browser.** Bisher wurde nur die
  Markdown-Antwort des Assistenten verlinkt; **im eigenen Prompt** (und in Tool-Ausgaben) war eine
  URL reiner Text und ließ sich nicht öffnen. Jetzt werden URLs in allen Chat-Texten erkannt und
  verlinkt: eigene Nachrichten, Assistenten-Antworten (Markdown-Links UND nackte URLs), live
  streamende Antwort, Tool-Ausgaben und auch sehr lange Nachrichten (dort greift der Markdown-Parser
  nicht mehr, die Link-Erkennung schon).
- **Öffnen ist explizit und sicher:** Ein Tap startet `ACTION_VIEW` mit `CATEGORY_BROWSABLE`
  (also Browser bzw. die App, die die Domain beansprucht); kann kein Handler geöffnet werden, kommt
  ein Hinweis statt eines Absturzes. Die Erkennung ist dieselbe wie im Markdown-Renderer
  (gemeinsames URL-Muster), damit ein `www.`-Link in Prompt und Antwort identisch funktioniert —
  inklusive „Satzpunkt bleibt außerhalb des Links".
- Linkfarbe im Klartext nutzt das Theme-Primary (die Markdown-Anker behalten ihr bewusst
  kontrastreiches Blau).

### Added

- Tests (`LinkTextTest`): nackte http(s)-URLs, `www.`-Präfix → https, Text vor/nach dem Link,
  mehrere URLs, abschließender Satzpunkt, und „normaler Text bleibt linkfrei".

## [1.5.4] - 2026-10-13

### Added

- **Detaillierter Live-Status im Chat.** Die Statuszeile kannte bisher nur „waiting for the
  model…" und „generating…"; ein langer Turn las sich dadurch immer gleich, auch während Werkzeuge
  liefen und Dateien geändert wurden. Jetzt zeigt die Zeile, WAS gerade läuft:
  `Running bash · npm test (3 tools)`, `Writing the answer · 1,240 chars`, `Thinking…`,
  `Compacting the session…`, `Waiting for the model…` bzw. `Reconnecting…` — und darunter weiterhin
  Agent · Modell. Die Reihenfolge ist fest priorisiert (tote Verbindung > Compaction > laufendes
  Werkzeug > Reasoning > Warten > Schreiben) und als reine Funktion (`liveStatus`) mit Tests
  abgesichert.
- **Feine Textauswahl im Chat.** Alles war nur als ganzer Block kopierbar (per „Copy"). Jetzt lassen
  sich einzelne **Wörter und Satzteile** markieren und kopieren: Assistenten-Antworten (Markdown),
  eigene Nachrichten, die live streamende Antwort und das Reasoning, Tool-Ausgaben sowie einzelne
  Diff-Zeilen sind selektierbar (`SelectionContainer`).

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
- **Die Adresse wird immer angezeigt.** Vorher erschien sie _nur_, wenn ein Anzeigename gesetzt war;
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

[Unreleased]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.16...HEAD
[1.5.16]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.15...v1.5.16
[1.5.15]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.14...v1.5.15
[1.5.14]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.13...v1.5.14
[1.5.13]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.12...v1.5.13
[1.5.12]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.11...v1.5.12
[1.5.11]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.10...v1.5.11
[1.5.10]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.9...v1.5.10
[1.5.9]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.8...v1.5.9
[1.5.8]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.7...v1.5.8
[1.5.7]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.6...v1.5.7
[1.5.6]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.5...v1.5.6
[1.5.5]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.4...v1.5.5
[1.5.4]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.5.3...v1.5.4
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
