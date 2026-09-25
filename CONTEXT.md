# Context

Domain glossary for the OpenCode Android client and its session-guard proxy.

## Glossary

- **SessionConversation** — the module that runs one OpenCode session: takes
  user commands and session events, owns send/stream/reconcile workflows, and
  exposes conversation state. Compose and Android concerns stay outside it.
- **ModelSelection** — the concept covering the currently selected
  provider/model/variant/agent for a session, including parsing, qualification,
  persistence, and server sync.
- **ChatRepository** — the local cache adapter for messages; it is not the
  transport seam.
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
  credentials, and Retrofit instance, and swaps them atomically.
- **ProviderDirectory** — the singleton that owns the provider/model catalog
  cache and its load lifecycle, fed by an injected fetcher.
- **ChatActions** — the stable bundle of session-level UI callbacks passed from
  the screen to SessionTab, replacing the flat 28-parameter list.
- **ComposerActions** — the stable bundle of composer callbacks passed to
  Composer, replacing the flat 30-parameter list.
