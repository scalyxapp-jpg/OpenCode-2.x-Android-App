# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[Unreleased]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases/tag/v1.0.0
