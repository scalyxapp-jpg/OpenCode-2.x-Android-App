# TODO — Medien im Chat (Thumbnails + Grossansicht)

- [x] Datenmodell: `Part` (mime/url/filename), `Message.files`, `MessageFile`
- [x] API: `readFileBytes(path, directory)` (binär, bounded) + Adapter
- [x] `ui/media/MediaRefs.kt`: Extraktion (Tool-Inhalte, Markdown, Pfade, data-URIs)
- [x] `ui/media/MediaCache.kt`: Disk-Cache + Fetch (inline/remote/server) + Thumbs + Budget
- [x] `ui/media/MediaUi.kt`: Thumbnail-Leiste + Vollbild-Viewer (Bild-Zoom, Video, Audio)
- [x] `MessageBubble`: Medien je Nachricht (User-Anhänge + Assistenz/Tools)
- [x] Settings: „Media previews in chat", `ChatScreen` hoistet ihn
- [x] Strings, Tests (`MediaRefsTest`, 15 Fälle), Build 3.10.0
- [~] Release veröffentlichen (GitHub + Tailscale) und Geräteprüfung
