# Context

Domain glossary for the OpenCode Android client and its session-guard proxy.

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
- **Session Guard** — the optional proxy that pins a session's
  provider/model/variant/agent and rewrites prompts to that selection.
- **Guard Drift** — the state where the upstream session selection differs from
  the guard selection.
- **GuardRequestRouter** — the proxy module that maps a request path to one
  route handler through a route table; each route owns one concern.
- **RequestContext** — the per-request value passed to guard routes: config,
  store, upstream client, headers, body, and parsed query.
- **SessionStreamer** — the module that consumes a session event flow and owns
  live streaming state, coalescing, and reconciliation triggers.
- **StreamReducer** — the pure part of SessionStreamer: event sequence to state
  transition, with no transport or Android dependency.
- **EventSource** — the seam for session events; the SSE client is one adapter,
  a synthetic event list is another.
- **ReconnectPolicy** — the pure backoff, stability, and watchdog rules for a
  dropped event stream.
- **BodyChunkReader** — the proxy module that decodes an HTTP request body
  (Content-Length or chunked) into byte chunks with one size/error policy.
- **BodyReadError** — typed body-decoding failure (`too_large`, `invalid_chunk`,
  `client_closed`, `empty`) mapped to an HTTP status by the router.
- **GuardCas** — the single module that checks an expected guard revision and
  produces the canonical conflict response; every mutating route uses it.
- **GuardConflict** — the typed result of a failed revision check: conflict
  error plus the current guard metadata.
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
