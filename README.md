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

## Contents

- [Features](#features)
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

- **Live streaming chat** — answers arrive token by token over SSE, with a
  streaming caret, progressive Markdown (headings, lists, tables, task lists,
  fenced code with syntax highlighting) and a stable "running" indicator that
  follows the whole turn, not just one step.
- **Tool calls, inline** — shell, read, edit and diff tool calls render as
  expandable rows with their input/output; consecutive identical calls collapse
  into a `×N` row.
- **Permission prompts & questions** — approve/deny tool permissions and answer
  agent questions from a dock, or straight from a notification action.
- **Model, agent & variant selection** — per-session provider/model/variant/agent
  pickers, synced with the server so your choice carries over to web/TUI.
- **Provider management** — browse the server's provider catalog, connect or
  disconnect credentials, and manage model visibility.
- **Review / Changes tab** — branch badge and working-tree diff, loaded on demand.
- **Sessions & history** — project-grouped session list, offline message cache,
  older-message paging, and a searchable history.
- **Background continuity** — a foreground service keeps the session connected
  while the app is open; a home-screen widget and a Quick Settings tile give you
  status and a one-tap new session.
- **Share target** — send text or images from any app into a prompt.
- **Themes** — default, Matrix, Aqua, Metal and Hello Kitty looks, light/dark,
  custom fonts.

## Requirements

|             |                                                                           |
| ----------- | ------------------------------------------------------------------------- |
| **App**     | Android 7.0 (API 25) or newer                                             |
| **Server**  | A reachable OpenCode server exposing its HTTP API (e.g. `opencode serve`) |
| **Network** | The phone must reach the server — same LAN, VPN, or a tunnel              |
| **Build**   | JDK 17, Android SDK 36                                                    |

The app starts on a backend picker: enter your server's base URL (for example
`http://192.168.1.100:4096`). Nothing is hard-coded and no server address is
baked into the build.

## Getting started

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
