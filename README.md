<div align="center">

# OpenCode Android

**An unofficial Android client for the [OpenCode](https://github.com/sst/opencode) server.**

Drive your OpenCode sessions from your phone: stream answers live, review tool
calls and diffs, answer permission prompts, and pick up work started in the
web UI or TUI.

[![CI](https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/actions/workflows/android.yml/badge.svg)](https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3ddc84.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/minSdk-25-orange.svg)](#requirements)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-7f52ff.svg)](https://kotlinlang.org)

</div>

---

> **Disclaimer**
> This is an **unofficial, community-built** client. It is **not** affiliated
> with, endorsed by, or supported by the OpenCode project. "OpenCode" is used
> only to describe what this client connects to. You need your own running
> OpenCode server — this app is a client, not a server.

## Download

Grab the signed APK straight from the latest release — no build required:

|                          |                                                                                                                                                        |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Latest APK**           | **[⬇ Download `OpenCode-Android.apk`](https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases/latest/download/OpenCode-Android.apk)**       |
| Version-pinned           | [`OpenCode-Android-1.0.0.apk`](https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases/download/v1.0.0/OpenCode-Android-1.0.0.apk) (v1.0.0) |
| All releases & checksums | [Releases page](https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases)                                                                    |

Direct URL — paste it into your phone's browser to download the newest build:

```
https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App/releases/latest/download/OpenCode-Android.apk
```

Every release is signed with the same key, so a newer version installs straight
over an older one. Each release note lists the file's **SHA-256** and the
signing certificate for verification.

After installing, open the app and enter your OpenCode server address — see
[Getting started](#getting-started).

## Contents

- [Download](#download)
- [Features](#features)
- [Feature documentation (detailed)](docs/FEATURES.md)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [Building a release](#building-a-release)
- [Testing](#testing)
- [Security & privacy](#security--privacy)
- [Architecture](#architecture)
- [Contributing](#contributing)
- [License](#license)

## Features

> Every feature is described in depth — behaviour, wire contract and limits — in
> **[docs/FEATURES.md](docs/FEATURES.md)**. This is the short version.

**Chat & streaming**

- **Live streaming chat** — answers arrive token by token over SSE, with a
  streaming caret and progressive Markdown (headings, lists, tables, task lists,
  fenced code with syntax highlighting).
- **A live status row that says what is happening** — `Running Shell · ls -la
  (3 tools)`, `Thinking · step 2`, `Writing the answer · 1,240 chars`,
  `Waiting for V4 Flash… · step 2`, `Compacting`, `Reconnecting…` — instead of a
  generic "waiting".
- **Tool calls inline** — shell, read, write, edit, patch, search, subagent,
  question and MCP calls as expandable rows with input/output; consecutive
  identical calls collapse into `×N`, long output is capped with a clear note.
- **Tappable links everywhere** — Markdown anchors *and* bare URLs in your own
  prompt, in answers and in tool output open in the browser.
- **Fine-grained text selection** — select single words and phrases in messages,
  the live answer, reasoning and tool output; copy/revert/fork stay in the message
  menu.
- **Compaction like the web** — `/compact` renders the divider notice, the
  generated summary, "Compacting" while it runs and the token usage line.

**Composer & sending**

- **Message queue (Ctrl+Enter)** — a second button next to send/stop, shown while
  you type, queues a follow-up instead of interrupting the running turn. Queued
  prompts appear with a **Queued** badge and the web's **Steer** / **Remove**
  actions; the server delivers them when the turn ends.
- **Slash commands** — every server command (`/api/command`) runs through the
  command endpoint exactly as in the TUI, plus the built-ins the client really
  implements (`/compact`, `/mcp`). Only working commands are listed.
- **Attachments** — images and files as base64 `file` parts (no helper needed), a
  share target for text/images from other apps, and a file tree + viewer.
- **Model, agent & variant selection** — per session, synced with the server so
  your choice carries to the web UI and TUI; same-named models stay selectable per
  provider, and model visibility is managed in one place.

**Sessions & work**

- **Sessions & history** — project-grouped list with running/retrying badges (for
  turns started anywhere), offline message cache, cursor paging, full-text search
  over cached text, rename/delete/archive, fork and revert.
- **Review / Changes tab** — branch badge, on-demand working-tree diff, file tree
  and file viewer.
- **Model race** — run the same task against 2–3 models in parallel sessions and
  compare answer, time and cost side by side, then continue in the winner.
- **Offline outbox** — a prompt typed while the server is unreachable is kept and
  delivered automatically when it comes back (per session, ordered, with backoff).
- **Prompt history** — recall what you sent before (this session or all), insert it into
  the composer and edit, queue or race it.
- **Live surfaces** — widget, ongoing notification and Quick Settings tile show what is
  running right now (step, failures, waiting decisions, cost today).
- **Explain mode & decision journal** — side questions run in their own session, and
  decisions captured anywhere can be handed to the agent as binding context.
- **Permissions & questions** — approve/deny tool permissions (or auto-accept),
  answer agent questions with the server's own field types, and answer a blocked
  permission straight from the notification.
- **Provider management** — browse the catalog, connect/disconnect credentials,
  manage model visibility; "connected" means exactly what the server reports.
- **MCP & skills** — per-server MCP toggles, OAuth auth, resources and the
  server's skills list.

**Staying informed**

- **Background notifications** — while the app is not in focus: occasional step
  progress (`Step 2 · Shell · ls -la`) and a finished summary **with the answer**.
  A tap opens that session **at that message**.
- **Background continuity** — a foreground service keeps the session connected
  while the app is open; a home-screen widget and a Quick Settings tile give you
  status and a one-tap new session.

**Look & feel**

- **Themes** — Default, Matrix, Aqua, Metal and Hello Kitty, light/dark/follow
  system, animated ambience.
- **Fonts** — separate UI and code font pickers from predefined families with live
  preview.
- **Settings** — sound effects, tool-row expansion, reasoning visibility, file
  tree, command palette, Session Guard, plus JSON export/import.

## Requirements

|             |                                                                           |
| ----------- | ------------------------------------------------------------------------- |
| **App**     | Android 7.0 (API 25) or newer                                             |
| **Server**  | A reachable OpenCode server exposing its HTTP API (e.g. `opencode serve`) |
| **Network** | The phone must reach the server — same LAN, VPN, or a tunnel              |
| **Build**   | JDK 17, Android SDK 37                                                    |

The app starts on a backend picker: enter your server's base URL (for example
`http://192.168.1.100:4096`). Nothing is hard-coded and no server address is
baked into the build.

## Getting started

Prefer a ready-made build? Use the [Download](#download) link above — the rest
of this section is for building from source.

```bash
# 1. Clone
git clone https://github.com/scalyxapp-jpg/OpenCode-2.x-Android-App.git
cd OpenCode-2.x-Android-App

# 2. Build the debug APK
./gradlew :app:assembleDebug

# 3. Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then launch the app and enter your OpenCode server URL.

### Run tests

```bash
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:connectedDebugAndroidTest  # on-device tests (needs a device)
```

## Configuration

Everything is configured in-app; there is no config file to ship.

- **Backend URL** — set on first launch and editable later in the backend picker.
  Stored in `SharedPreferences`; passwords (if you add basic-auth) are encrypted
  with the Android Keystore.
- **Settings** — theme, fonts, tool-row expansion, notifications and sounds,
  and reasoning-summary visibility.

### Optional: session-guard proxy

The app can talk to an _optional_ self-hosted "session-guard" proxy that pins a
session's provider/model/variant/agent and rewrites prompts to match. It is a
separate component you run yourself; the app detects it automatically and works
without it. No proxy host is bundled.

## Building a release

Release signing is read from `keystore.properties` in the repository root
(git-ignored). When the file is missing, `assembleRelease` falls back to the
debug key and prints a warning — fine for local testing, **never** for
distribution.

```bash
cp keystore.properties.example keystore.properties
# edit the values, then:
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Keep the keystore and its passwords safe: losing them means you can no longer
ship updates to an installed app. Maintainers: see
[docs/RELEASING.md](docs/RELEASING.md) for the full publishing checklist.

## Testing

- **Unit tests** (`app/src/test`) — pure logic: stream reducers, prompt/payload
  builders, retry/reconnect policies, markdown parsing, caching, paging. These
  run on the JVM and need no device.
- **Instrumented tests** (`app/src/androidTest`) — optional live smoke tests for
  a self-hosted guard proxy. They are **skipped** unless you pass the host:

    ```bash
    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.guardUrl=http://10.0.2.2:8932
    ```

## Security & privacy

- **No analytics, no third-party telemetry.** The app talks only to the server
  URL you configure.
- Client-side errors may be reported to **your own server's** `/log` endpoint as
  a short, single-line, sanitized summary, so problems are diagnosable without
  adb. This never includes message content, `.env` values or credentials.
- Server credentials are encrypted with the Android Keystore and are **excluded
  from cloud backup and device transfer**.
- Cached conversation text and crash reports are also excluded from backups.
- Plain HTTP is permitted because OpenCode's server speaks HTTP on your own
  network; the decision is explicit and auditable in
  `app/src/main/res/xml/network_security_config.xml` (system CAs only).

Found a vulnerability? See [SECURITY.md](SECURITY.md).

## Architecture

The app is a Kotlin/Compose client on top of a small, testable core: a pure
stream reducer, a conversation module, repositories, and Hilt-provided
singletons for the backend connection and provider catalog. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the module map, the state
ownership rules and the wire-protocol notes.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) and our
[Code of Conduct](CODE_OF_CONDUCT.md). Good first steps: run the unit tests,
pick an issue, and open a focused pull request.

## License

Licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for
third-party attributions.
