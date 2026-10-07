# Context

Domain glossary for the OpenCode Android client.

## Glossary

- **SessionConversation** — the module that runs one OpenCode session: takes
  user commands and session events, owns send/stream/reconcile workflows, and
  exposes conversation state. Compose and Android concerns stay outside it.
- **ModelSelection** — the concept covering the currently selected
  provider/model/variant/agent for a session, including parsing, qualification,
  persistence, and server sync.
- **ChatRepository** — the data access for a chat session: offline message
  cache plus session/messages/VCS reads with retry, merge, and OOM handling.
- **EffectiveSelection** — the pure decision of which provider/model/variant/
  agent a send actually uses, resolving server state against pending local
  selections (`ui/session/EffectiveSelection.kt`).
- **PromptRequestBuilder** — the pure builder for the `prompt_async` wire body:
  omit blank agent, blank/`default` variant, blank model id, and the text part
  for an attachment-only turn (`ui/session/PromptRequestBuilder.kt`).
- **SendEcho** — the pure send-path text shaping: the visible
  `[Attached: name]` display text and the optimistic local user row, plus the
  wire prompt text with uploaded paths (`ui/session/SendEcho.kt`).
- **PromptSender** — the guarded `prompt_async` state machine: revision lookup,
  once-only 409 retry without a stale revision, readable error, response close
  (`ui/session/PromptSender.kt`).
- **SessionTransport** — the transport seam for the session-level HTTP a
  conversation workflow owns (guarded prompt, abort/interrupt, guard
  revisions). `BackendSessionTransport` is the production adapter over
  `BackendSession` + `SelectionGuard`; `SessionConversation` takes the
  interface, so workflows are testable with a fake (`data/SessionTransport.kt`).
- **Conversation flags ownership** — `ConversationState` is the authoritative
  projection of the event stream (generating, pending-persist, compacting,
  status error, SSE connected, selected model). `ChatUiState` mirrors those
  fields through `ConversationPort` synchronously, so a ViewModel read right
  after a write is never stale. The mirror stays until the send workflow moves
  wholesale and `ChatUiState` becomes a pure projection.
- **Session running state (turn vs step)** — `isGenerating` is TURN-level: it is
  set by a send or by the server reporting `busy`/`retry` and is cleared ONLY by
  `session.idle`, status `idle`, `session.error`, an explicit abort, or the
  status-endpoint resync/watchdog. It is the single source of truth for the
  composer's send/stop button, the live status row, the widget and the
  polling/caching gates. `isStreaming` is STEP-level: it says the current step is
  actively producing content and only drives the streaming caret; it drops at
  every `step-finish` while the turn keeps running. `StreamEffect.Finalize`
  finalizes one step (stops the caret, arms the persist timeout) and
  `StreamEffect.EndTurn` ends the turn (clears `isGenerating`) — step boundaries
  must never end the turn, which was the "stop button loses its state between
  steps" bug.
- **Session Guard** — an optional, separately self-hosted proxy that pins a
  session's provider/model/variant/agent and rewrites prompts to that selection.
  It is not part of this repository; the app detects it and works without it.
- **Guard Drift** — the state where the upstream session selection differs from
  the guard selection.
- **SessionStreamer** — the module that consumes a session event flow and owns
  live streaming state, coalescing, and reconciliation triggers.
- **StreamReducer** — the pure part of SessionStreamer: event sequence to state
  transition, with no transport or Android dependency.
- **EventSource** — the seam for session events; the SSE client is one adapter,
  a synthetic event list is another.
- **ReconnectPolicy** — the pure backoff, stability, and watchdog rules for a
  dropped event stream.
- **BackendSession** — the singleton that owns the active backend base URL,
  credentials, and Retrofit instance, and swaps them atomically. Created by
  Hilt (`AppModule`) and read in Compose through `LocalBackendSession`; the
  legacy `ApiClient` facade and `shared()` accessor are gone.
- **ProviderDirectory** — the singleton that owns the provider/model catalog
  cache and its load lifecycle, fed by an injected fetcher. Created by Hilt and
  read in Compose through `LocalProviderDirectory`; the legacy `ProviderCatalog`
  facade and `shared()` accessor are gone.
- **ChatActions** — the stable bundle of session-level UI callbacks passed from
  the screen to SessionTab, replacing the flat 28-parameter list.
- **ComposerActions** — the stable bundle of composer callbacks passed to
  Composer, replacing the flat 30-parameter list.
- **OpenCodeV2Api** — the Retrofit definition of the OpenCode 2.x `/api`
  surface: every path namespaced with `api/`, wire DTOs, and directory scoping
  through the `x-opencode-directory` **header** (the V1 `?directory=` query
  param is ignored by the server). `data/OpenCodeV2Api.kt`.
- **OpenCodeApiAdapter** — the single V1→V2 shape bridge. Implements the
  app-facing `OpenCodeApi` (a plain interface, no Retrofit annotations) over
  `OpenCodeV2Api`, mapping wire shapes back onto the stable domain models so the
  UI, ViewModels and repositories did not change. All endpoint, envelope,
  message-shape, form/permission and provider-credential differences live here.
  `data/OpenCodeApiAdapter.kt`.
- **V2EventNormalizer** — rewrites an OpenCode 2.x `/api/event` frame
  (`{id,type,data,durable}`, payload under `data`) onto the app's existing event
  vocabulary (`session.next.*`, `message.part.*`, `session.status`, …) and fills
  the `EventData`/`EventProperties` the `StreamReducer` already understands.
  `ui/session/V2EventNormalizer.kt`.
- **V2 wire contract** — the observed 2.x endpoint map, envelopes, message
  shape, SSE event catalogue and provider/model split, captured from the live
  server. `docs/OPENCODE-API.md`.
