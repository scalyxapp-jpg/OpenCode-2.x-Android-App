# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
