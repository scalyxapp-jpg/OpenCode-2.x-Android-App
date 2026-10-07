# AGENTS.md — guide for AI coding agents and contributors

This file gives automated coding agents (and humans) the context to work in this
repository safely.

## What this project is

An **unofficial Android client** for the OpenCode server. Kotlin + Jetpack
Compose, single Gradle module (`:app`). It talks to a user-hosted OpenCode
server over HTTP/SSE and renders sessions, streaming answers, tool calls, diffs,
permissions and questions.

## Tech stack

- **Language:** Kotlin 2.0.20, JVM target 17
- **UI:** Jetpack Compose (Material 3), Navigation Compose, Glance (widget)
- **DI:** Dagger Hilt
- **Network:** OkHttp (+ SSE), Retrofit, kotlinx.serialization
- **Build:** Gradle 8.11.1, AGP 8.9.1, KSP
- **Min / target SDK:** 25 / 36

## Structure

| Path                                                 | Responsibility                                                                                |
| ---------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `app/src/main/java/com/opencode/android/ui/session/` | Pure stream core: `StreamReducer` (pure), `SessionStreamer`, `SessionConversation`, policies. |
| `app/src/main/java/com/opencode/android/ui/`         | Compose screens, `ChatViewModel`/`HomeViewModel`, widgets, theming, action bundles.           |
| `app/src/main/java/com/opencode/android/data/`       | `ChatRepository`, `OpenCodeApi` + v2 adapter, `BackendSession`, caches, stores.               |
| `app/src/main/java/com/opencode/android/domain/`     | Wire and domain models.                                                                       |
| `app/src/main/java/com/opencode/android/util/`       | Small pure helpers (retry/reconnect policies, paging, logging).                               |
| `app/src/test/`                                      | JVM unit tests (fast, no device).                                                             |
| `app/src/androidTest/`                               | On-device tests (optional, skipped without a server).                                         |
| `docs/`                                              | Architecture and protocol reference.                                                          |
| `CONTEXT.md`                                         | Domain glossary — the authoritative vocabulary.                                               |

## Commands

```bash
./gradlew :app:testDebugUnitTest        # unit tests (must stay green)
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # release APK (needs keystore.properties)
./gradlew :app:lintDebug                # Android Lint
```

## Non-negotiable rules

1. **Never commit secrets or machine-specific data.** No `keystore.properties`,
   `*.jks`, `local.properties`, `.env`, real server URLs, tokens, private IPs or
   personal paths — not in code, tests, fixtures or docs. Use placeholders and
   environment/instrumentation arguments.
2. **Keep the state model unambiguous.** `isGenerating` is TURN-scoped; step
   boundaries (`step-finish`, `session.next.step.ended`) must never end the
   turn. Only `session.idle`, status `idle`, `session.error`, an explicit abort,
   or the status resync/watchdog end a turn. See CONTEXT.md →
   "Session running state (turn vs step)".
3. **Prefer pure, testable code.** Streaming/state logic belongs in
   `ui/session/` pure functions with unit tests, not in a ViewModel.
4. **Green build required.** Run `:app:testDebugUnitTest` and
   `:app:assembleDebug` before claiming a change is done.
5. **Match the existing style** and keep diffs focused.

## Working with the wire protocol

`docs/OPENCODE-API.md` captures the server API contract the client relies on.
When an API change breaks decoding, pin it with a test rather than loosening the
decoder silently.
