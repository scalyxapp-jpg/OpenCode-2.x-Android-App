# Architecture

This document is the map of the codebase. For the precise vocabulary used
throughout the code (`StreamReducer`, `ConversationState`, `BackendSession`, …),
read [CONTEXT.md](../CONTEXT.md) — it is the authoritative domain glossary. For
the server API contract the client depends on, see
[OPENCODE-API.md](OPENCODE-API.md).

## At a glance

```
┌──────────────────────────────────────────────────────────────┐
│ Compose UI (ui/)                                             │
│  screens · widgets · theming · action bundles                │
└───────────────▲──────────────────────────────┬───────────────┘
                │ ChatUiState / LiveStreamState │ callbacks
┌───────────────┴──────────────────────────────▼───────────────┐
│ ViewModels (ChatViewModel, HomeViewModel)                     │
│  project state into the UI; own Android concerns              │
└───────────────▲──────────────────────────────┬───────────────┘
                │ ConversationState             │ ConversationPort
┌───────────────┴──────────────────────────────▼───────────────┐
│ Session core (ui/session/)                                    │
│  SessionConversation → SessionStreamer → StreamReducer (pure) │
│  StreamReducer: events → state; no transport, no Android      │
└───────────────▲──────────────────────────────┬───────────────┘
                │ Event (event stream)          │ SessionTransport
┌───────────────┴──────────────────────────────▼───────────────┐
│ Data layer (data/)                                            │
│  SseClient · ChatRepository · OpenCodeApi(+v2 adapter)        │
│  BackendSession · ProviderDirectory · caches & stores         │
└──────────────────────────────┬───────────────────────────────┘
                               │ HTTP / SSE
                        ┌──────▼──────┐
                        │ OpenCode    │
                        │ server      │
                        └─────────────┘
```

## Layers and their rules

### `ui/session/` — the pure stream core (start here)

The heart of the app, extracted so it can be unit-tested without a device or
server.

- **`StreamReducer`** — a _pure_ `reduce(state, event, …) → state` for the live
  stream. No transport, no coroutines, no Android. Everything observable about a
  turn flows through here, which is why nearly every streaming bug has a
  regression test in `StreamReducerTest`.
- **`StreamState` / `StreamProjection`** — the reducer's state and the subset of
  it projected into the UI. Two flags matter:
    - `isGenerating` is **turn-scoped** — a turn is in flight. Only `session.idle`,
      status `idle`, `session.error`, an explicit abort, or the status
      resync/watchdog clear it.
    - `isStreaming` is **step-scoped** — the current step is producing content; it
      drives the caret only and legitimately drops at every `step-finish`.
- **`StreamEffect`** — side effects the reducer requests but never performs.
  `Finalize` ends a _step_; `EndTurn` ends the _turn_. The streamer performs
  them.
- **`SessionStreamer`** — owns one session's live state: consumes an
  `EventSource`, applies the reducer, coalesces delta flushes, arms the persist
  timeout, and forwards effects.
- **`SessionConversation`** — the command surface (`Load`, `Send`, `Interrupt`,
  `Retry`). Owns the interrupt workflow and exposes `ConversationState`.
- **`ConversationPort` / `SessionTransport`** — seams. The ViewModel implements
  the port; the transport abstracts the guarded HTTP calls, so both sides are
  replaceable by fakes in tests.
- **Pure policies** (`util/`): `LiveFlushPolicy`, `RefreshCoalescer`,
  `WriteThrottle`, `ReconnectPolicy`, `RetryPolicy`, `StreamPolicies`.

### `ui/` — presentation

Compose screens and their ViewModels. `ChatViewModel` is the largest module: it
owns `ChatUiState` (session, messages, selections, flags) and mirrors the
conversation flags through `ConversationPort`. High-frequency token buffers live
in a **separate** `LiveStreamState` flow so a token flush recomposes only the
small live subtree, never the whole message list.

Screen-level callbacks are bundled into `ChatActions` / `ComposerActions` so
adding a callback does not explode the parameter lists.

### `data/` — transport and persistence

- **`OpenCodeApi`** — the app-facing interface (plain Kotlin; no Retrofit
  annotations). **`OpenCodeV2Api`** is the Retrofit v2 surface and
  **`OpenCodeApiAdapter`** is the single V1→V2 shape bridge, so the rest of the
  app never sees the wire difference.
- **`SseClient`** — the `/api/event` reader with a liveness watchdog, bounded
  buffers, reconnect backoff and synthetic connect/disconnect events.
- **`ChatRepository`** — session/message/VCS reads with retry, merge, caching and
  OOM guards.
- **`BackendSession`** — the singleton owning the base URL, credentials and
  Retrofit instance, swapped atomically. **`ProviderDirectory`** owns the
  provider/model catalog cache.
- **Stores** — `AppSettingsStore`, `BackendStore`, `DraftStore`,
  `ModelVisibilityStore`, `MessageCache`, `HomeStore`, `WidgetStateStore`.

### Ownership conventions

- `ConversationState` (in the session core) is the authoritative projection of
  the event stream. `ChatUiState` mirrors it synchronously through
  `ConversationPort`, so a ViewModel read right after a write is never stale.
- Only one writer per piece of state. If you find two code paths writing the same
  flag, that is the bug.

## The streaming pipeline (one turn)

1. **Send** — `ChatViewModel.performSend()` builds the `prompt_async` body
   (`PromptRequestBuilder`), optimistically echoes the user row (`SendEcho`) and
   posts it through `PromptSender` (once-only 409 retry).
2. **Stream** — `SseClient` delivers events; `SseFilter` drops traffic that does
   not belong to the active session; `V2EventNormalizer` rewrites v2 frames onto
   the app's event vocabulary; `StreamReducer` folds them into `StreamState`.
3. **Flush** — text/reasoning deltas accumulate in pending buffers and are
   flushed on an adaptive window (`LiveFlushPolicy`) into `LiveStreamState`.
4. **Reconcile** — periodic/event-driven `refreshMessages()` merges the persisted
   history, debounced by `RefreshCoalescer` so a tool storm cannot fire hundreds
   of requests.
5. **End of step** — `step-finish` → `Finalize`: stop the caret, keep the text on
   screen and arm a short persist timeout until the persisted message lands.
6. **End of turn** — `session.idle` / status `idle` / error → `EndTurn`: clear the
   running flag exactly once, reconcile, and notify.

If the stream drops, reconnecting emits `sse.connected`, which triggers a
reconcile and a status resync; a watchdog re-checks the server status while the
stream is down so a lost `session.idle` cannot leave the UI stuck on "running".

## Testing strategy

- **Pure core → unit tests.** The reducer, policies, builders and parsers are pure
  and heavily tested on the JVM.
- **Seams → fakes.** `EventSource`, `SessionTransport` and `ConversationPort` are
  interfaces, so streamer/conversation tests substitute flows and fakes.
- **Wire shape → captured fixtures.** `server_message_page.json` is a sanitized
  capture that pins the decoder against the real server payload.

## Adding a feature

1. Put the logic in the pure core if it concerns streaming/state, and unit-test it.
2. Thread it through `ConversationPort`/`ChatActions` rather than reaching across
   layers.
3. Keep new user-facing strings in `strings.xml` (localisable), not inline.
