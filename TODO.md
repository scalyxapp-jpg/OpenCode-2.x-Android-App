# TODO — Medien im Chat (Thumbnails + Grossansicht)

- [x] Datenmodell, API (`readFileBytes`), Extraktion (`MediaRefs`), Cache (`MediaCache`)
- [x] UI: Thumbnail-Leiste + Vollbild-Viewer (Bild-Zoom, Video, Audio)
- [x] `MessageBubble` + Settings-Schalter + Strings + Docs
- [x] Tests: 16 × `MediaRefsTest`, 5 × `FsPathEncodingTest`
- [x] Geräteprüfung 3.10.0/3.10.1: Kacheln, Großansicht, Audio-Player, Video, Schalter
- [x] Gerätetest-Fixes: `%2F`-Kodierung für `fs/read`, Doppel-Kachel, Diagnose-Log
- [~] Release 3.10.1 veröffentlichen (GitHub + Tailscale) und Gerät auf CI-Artefakt bringen
