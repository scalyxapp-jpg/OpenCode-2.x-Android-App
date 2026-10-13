# Features

A detailed tour of what the OpenCode Android client can do, how the pieces behave
day to day, and where each feature maps onto the OpenCode server API.

> **Scope.** This is an **unofficial client** for a server **you** run. It never
> ships a server address, never bundles a proxy, and talks only to the backend you
> configure. Anything marked **1:1 with the web** was verified against a running
> OpenCode 2.0.26 server with browser automation (Playwright) and, where possible,
> pinned by a unit test.

## Contents

1. [Getting connected](#1-getting-connected)
2. [The home screen](#2-the-home-screen)
3. [Chat & live streaming](#3-chat--live-streaming)
4. [The composer](#4-the-composer)
5. [The message queue (Ctrl+Enter)](#5-the-message-queue-ctrlenter)
6. [Slash commands](#6-slash-commands)
7. [Attachments, share target and files](#7-attachments-share-target-and-files)
8. [Session info tab](#8-session-info-tab)
9. [Changes tab](#9-changes-tab)
10. [Permissions and questions](#10-permissions-and-questions)
11. [Models, agents and variants](#11-models-agents-and-variants)
12. [Providers and credentials](#12-providers-and-credentials)
13. [MCP servers, skills and resources](#13-mcp-servers-skills-and-resources)
14. [Notifications](#14-notifications)
15. [Widget, Quick Settings tile and deep links](#15-widget-quick-settings-tile-and-deep-links)
16. [Appearance and themes](#16-appearance-and-themes)
17. [Settings reference](#17-settings-reference)
18. [Session guard (optional proxy)](#18-session-guard-optional-proxy)
19. [Privacy, security and data](#19-privacy-security-and-data)
20. [Web/TUI parity notes](#20-webtui-parity-notes)
21. [Limits and honest caveats](#21-limits-and-honest-caveats)
22. [Feature → API map](#22-feature--api-map)

---

## 1. Getting connected

The app opens on a **backend picker** — no server address is compiled in.

- **Add a server** by entering its base URL (e.g. `http://192.168.1.100:4096`,
  `https://opencode.example.ts.net`, or a Tailscale/MagicDNS name).
- **Multiple servers** can be stored; switching re-reads the session list and
  clears the offline caches so one server's data can never appear under another.
- **Guard detection** — if the URL answers like the optional session-guard proxy,
  the app switches to guarded mode automatically (see §18).
- **Credentials** (HTTP basic auth) are stored encrypted with the Android
  Keystore and excluded from backup/device transfer.
- **Health & info** — `GET /api/info` is used for the version/status pill; the
  connection is re-checked on resume and after a reconnect.
- **Android 16+** — `ACCESS_LOCAL_NETWORK` is declared **and** requested at
  runtime, which Android 16 requires before an app may talk to a LAN address.

## 2. The home screen

The home screen is the project/session hub.

- **Projects** — sessions grouped by project directory. The list contains exactly
  the projects you kept: adding a directory (with suggestions from the server)
  puts it on the list, and **deleting never resurrects it** (the keep-list is
  persisted and the server's list is filtered against it).
- **Recent sessions** per project with title, agent, model, relative time
  (`3h ago`, absolute beyond a week) and cost, plus a **running** / **retrying**
  badge driven by the server's status events — including turns started in the
  web UI or TUI.
- **Filters** — _only running_ and _only guarded_ sessions, persisted across
  restarts.
- **Search** — full-text over the locally cached conversation text
  (`MessageCache.search`), matched session ids are highlighted in the list.
- **Session actions** — rename, delete, archive (archived locally and hidden from
  the list), open, and _new session_ in a specific project.
- **Command palette** — the title-bar palette (toggleable) for quick jumps.
- **Offline first** — the last session list and message tails are cached, so a
  cold start paints immediately and a brief network drop does not blank the app.

## 3. Chat & live streaming

The chat is a reverse-layout timeline (newest at the bottom) fed by SSE.

**Live streaming.** Answers arrive token by token over `/api/event`
(`session.text.delta`, `session.reasoning.delta`); deltas are coalesced so a burst
of tokens recomposes only the small live subtree, not the whole screen.

**Detailed live status row.** Instead of a generic "waiting…", the row above the
composer reports the concrete activity, with a precedence order
`disconnected → compacting → tool → thinking → writing → waiting`:

| State              | Example line                       |
| ------------------ | ---------------------------------- |
| Running a tool     | `Running Shell · ls -la (3 tools)` |
| Model is reasoning | `Thinking · step 2`                |
| Writing the answer | `Writing the answer · 1,240 chars` |
| No output yet      | `Waiting for V4 Flash… · step 2`   |
| Compaction         | `Compacting`                       |
| Stream down        | `Reconnecting…`                    |

Tool names are merged across the server's several frames per call, so a later
frame never erases the name, and the step counter names the running model call of
a multi-step turn.

**Markdown rendering.** Headings, lists, tables, task lists, block quotes and
fenced code with syntax highlighting. Very long messages skip the Markdown pass
but keep their links.

**Links.** Every chat text is linkified — assistant Markdown anchors _and_ bare
`http(s)`/`www.` URLs inside your own prompt, live streams and tool output.
A tap opens the link externally (`ACTION_VIEW` + `CATEGORY_BROWSABLE`); a missing
handler shows a hint instead of crashing.

**Text selection.** Assistant messages, your own messages, the live answer,
reasoning and tool output all sit in selection containers, so single words and
phrases can be selected — not just whole blocks. Per-message copy/revert/fork stay
reachable through the message menu (⋮).

**Tool calls inline.** Shell, read, write, edit, patch, search, list, subagent,
question and MCP calls render as expandable rows with input and output. Long
outputs are capped with a `… (N more characters — copy for the full output)`
note; consecutive identical calls collapse into a `×N` row. Two settings decide
whether shell rows and edit/write/patch rows start expanded.

**Diffs** render as a coloured line list inside the edit/write rows, and the
Changes tab keeps the full working-tree diff.

**Todos** — `todo.updated` events render the session's checklist.
**Compaction** — `/compact` results render exactly like the web: a divider notice
"Session compaction started", the generated summary as Markdown, a "Compacting"
line while it runs and "Session compacted · 34,997 in · 140 out" afterwards
(also `by provider`, `failed`, `cancelled`, `interrupted`).
**Reasoning** — reasoning summaries appear as a block you can expand/collapse; the
setting "Expand thinking blocks" decides whether "Thinking…" starts **expanded**
or **collapsed** (default: collapsed), both for the live stream — where the header
keeps a streaming caret while collapsed — and in history. A separate setting hides
reasoning summaries entirely.
**Reconnect banner** — a dropped stream surfaces as a state, and the app resyncs
status, messages and the todo list after reconnecting.

## 4. The composer

One calm row: attachments, input, pickers, then the action buttons.

- **Send / Stop** — one button that is _send_ when idle and _stop_ while a turn
  runs; it scales and recolours when armed, with haptics.
- **Queue** — a second button, left of send/stop, that only appears while the
  composer has text (see §5).
- **Agent / model / variant pickers** — per session, pushed to the server so your
  choice carries over to the web UI and TUI. Variants (`default`, `high`, `max`)
  map to the provider's reasoning effort.
- **`/` command picker** — lists the server's commands and the locally implemented
  built-ins; picking one inserts its template (see §6).
- **`@` context picker** — insert file paths as context.
- **IME aware** — the keyboard inset is consumed once, so the composer does not
  jump; the send key is wired to send.

## 5. The message queue (Ctrl+Enter)

The web composer queues a follow-up with **Ctrl+Enter** instead of interrupting
the running turn. The app mirrors that exactly (verified against the live server):

- **Queue button** — visible only while you have typed something, sitting left of
  send/stop. It posts `/api/session/{id}/prompt` with `"delivery":"queue"` and
  **does not interrupt** the running turn. If the request fails, your text comes
  back into the composer instead of being lost.
- **Queued cards** — queued prompts render in the transcript with the web's
  wording: a **Queued** badge (or **Steer** once released), the prompt text, and
  the two actions the server supports:
    - **Steer** — deliver it now (`PATCH …/inbox/{id}` `{"delivery":"steer"}`).
    - **Remove** — drop it (`DELETE …/inbox/{id}`).
- **Delivery** is server-side: when the running turn ends the oldest queued prompt
  becomes a normal message (while the session is idle it is delivered
  immediately). The app re-reads the queue on every inbox transition, on session
  load, after each queue action and at turn end, so the list empties by itself.
- **Attachments** cannot be queued (the inbox item would have to carry the
  base64 parts). The app says so instead of silently dropping the files — send
  those directly.

## 6. Slash commands

Two kinds of commands exist, and the app is explicit about which is which:

- **Server commands** (`GET /api/command`) — executed through
  `POST /api/session/{id}/command` `{name, text, delivery}`, so the server expands
  the command's template exactly as the TUI does. Anything the server expands is
  usable.
- **Locally implemented built-ins** — only what the client can really do:
  `/compact` (compaction with the web's request shape) and `/mcp` (MCP status /
  auth). A built-in is only listed when it actually works; server commands that
  would shadow a built-in are filtered out, so the list is honest rather than
  pretty.

Typing `/` opens the picker with the command's description; choosing one inserts
its template and leaves the cursor where the arguments belong.

## 7. Attachments, share target and files

- **Pick images and files** from the system picker; thumbnails show in the
  composer with per-item removal and an upload progress row.
- **Wire shape** — attachments become `file` parts with base64 data URLs (the web
  shape), so a plain OpenCode server receives them with no helper. If the optional
  guard proxy is active, files are uploaded to the host instead and the paths go
  into the prompt text.
- **Share target** — `ACTION_SEND` and `ACTION_SEND_MULTIPLE` are handled: share
  text or images from any app straight into a prompt; shared images are copied
  into the app cache (the sender's URI grant is not durable) with a size cap.
- **File tree & viewer** — the Changes tab shows the session directory as a tree
  (toggleable) and opens files in a viewer with syntax highlighting and error
  states for unreadable/binary/oversized files.

## 8. Session info tab

A statistics view for the open session: title, agent, active model, message
counts (user/assistant), tool-call count with a _read_ breakdown, total tokens,
input/output split, cache usage and cost. The context chip derives from the newest
assistant message, i.e. the current context-window occupancy — exactly like the
web's context panel.

## 9. Changes tab

- **Branch badge** for the session's directory.
- **Working-tree diff** loaded on demand (`GET /api/session/{id}/diff`), with a
  loading state and a readable error when the directory is not a repository.
- **File list + viewer** for the session directory.
- The heavy VCS work only runs when you open the tab, so opening a chat stays
  cheap.

## 10. Permissions and questions

- **Permission prompts** dock in the chat and support the server's replies
  (allow once / always / deny), plus an **auto-accept** setting.
- **Actionable notifications** — a blocked permission can be answered from the
  notification shade (Allow once / Always / Deny) without opening the app; the
  reply is posted by a broadcast receiver.
- **Agent questions** render as cards with the server's form fields (single
  choice, multi-select, free text, number, boolean). Answers are sent with the
  **typed** value the server validates (`string`, array of option _values_,
  number, boolean) and are re-usable if the first attempt fails: the card is
  restored, the real error is shown, and the reply is retried once with the
  server's pending form id. Form keys are fetched on demand.
- Question and permission notifications are de-duplicated, so a repeated list
  load never re-alerts the same prompt.

## 11. Models, agents and variants

- **Per-session selection** of provider/model/variant/agent, pushed to the server
  (`POST …/model`, `POST …/agent`) so the web UI and TUI follow along; the app
  auto-adopts server-side changes made elsewhere.
- **"Connected" is defined once**: a provider counts as connected when
  `/api/provider` lists it. The model catalog is the union of `/api/integration`
  and `/api/provider`, so "Manage models" shows the same reality as the picker.
- **Same-named models across providers** stay selectable — the model identity is
  `provider/model`, and only a genuine provider prefix is stripped.
- **Visibility** — per-model and per-provider show/hide, persisted locally
  (mirrors the web's local storage behaviour).
- **Reasoning variants** are sent as a top-level `variant` field, and "default" is
  omitted rather than sent, matching the web.

## 12. Providers and credentials

- Browse the provider catalog grouped into **connected** and **popular/available**
  providers.
- **Connect / disconnect credentials** from inside the app (the server stores
  them); the catalog refreshes afterwards.
- **Model list** — search and toggle visibility; the same list feeds the composer
  picker.

## 13. MCP servers, skills and resources

- **MCP list** with per-server enable/disable toggles and a search field; a
  per-server toggle (rather than one global switch) means you can always see
  *which* server is off.
- **Auth** — servers that need it show a "click to authenticate" hint and are
  authenticated from the app; the list refreshes afterwards so the new state is
  visible immediately.
- **Skills** — the server's skills (`GET /api/skill`) are listed in Settings, so you
  can see what the agent can pull in.
- The MCP section is emptied on session switch, so one session's server list never
  appears under another.

## 14. Notifications

Six channels, each with a purpose, so you can mute one thing without losing
another:

| Channel                     | Used for                                                 | Sound   |
| --------------------------- | -------------------------------------------------------- | ------- |
| `agent`                     | the finished answer ("end summary")                      | setting |
| `agent-progress`            | background step progress                                 | silent  |
| `permissions`               | permission requests                                      | setting |
| `permissions-urgent` (HIGH) | actionable permission prompts                            | setting |
| `errors`                    | turn/stream errors                                       | setting |
| `connection`                | the ongoing "connected" notice of the foreground service | silent  |

**While the app is in the background** (tracked from the activity lifecycle, so a
focused app stays quiet):

- **Step progress, occasionally** — after a finished step, at most every 45 s and
  at most 4× per turn, naming the work of that step
  (`Step 2 · Shell · ls -la, Read · ChatViewModel.kt`). Steps without any tool are
  skipped once something was already reported.
- **Finished summary** — when the turn ends, with the answer itself (first line,
  capped) or the error text. It replaces that session's last progress line instead
  of stacking next to it, and exactly one summary per turn is posted even though
  several producers report the same turn end.
- **Tap → session _and_ message.** Notifications carry the session id and the
  message id; the chat scrolls once to that message (older, not-loaded messages
  simply leave you at the right session).
- **Questions and permissions** notify with their own actions; an "agent" toggle,
  an "errors" toggle and the sound toggles control the rest.

## 15. Widget, Quick Settings tile and deep links

- **Home-screen widget** — shows the last session's status (working/idle/none) and
  offers a one-tap **new session**.
- **Quick Settings tile** — "New OpenCode session" from the notification shade.
- **Deep links** — notification taps, widget taps and shared content all route
  through `MainActivity` into the correct session (and message), never into a
  stale chat that was open before.

## 16. Appearance and themes

- **Themes** — Default, Matrix, Aqua, Metal and Hello Kitty, each with light and
  dark variants; the scheme can also follow the system.
- **Fonts** — separate **UI font** and **code font** picked from **predefined
  families** (system default, Roboto, Sans Light/Medium/Condensed/Black/Small Caps,
  Serif, Casual, Cursive, System Mono, Monospace, …) with a live preview. Free
  text was removed on purpose: a device that lacks a font silently falls back, so
  the picker only offers what exists.
- **Motion** — expressive springs for the composer action, spatial animations for
  list insertions (disabled while streaming so token flushes never re-animate the
  viewport), animated dots in the status row.
- **Ambience** — Matrix rain, Aqua fish and Metal sheen effects, plus the Hello
  Kitty look, tuned to stay cheap (they pause when not visible).

## 17. Settings reference

Four tabs:

**General** — theme, colour scheme, UI font, code font, show reasoning summaries,
**expand thinking blocks** (whether "Thinking…" starts expanded or collapsed),
expand shell tool parts, expand edit tool parts, file tree panel, command-palette
button, server-status button, show agent picker, auto-accept permissions,
notifications (agent / permissions / errors), sound effects (agent / permissions /
errors), Session Guard (status + metrics, **hide the guard in the chat**, token,
auto-adopt drift), skills list, settings **export/import** as JSON (for moving to a
new device), and **reset all settings**.

The two per-part switches and the thinking switch share one rule: they decide the
**default** state, and tapping the block's header always toggles it for that
message/turn. "Hide Session Guard in chat" is offered **only when a guard was
detected** (the health probe answered); turning it on removes the guard's status
row and its dialog from the chat while the guard keeps pinning the selection —
only the chrome disappears.

**Servers** — the stored backends, the active one, switching (clears caches), the
guard's live metrics, and the guard token field (shown in full width so it is not
squeezed into an address row).

**Providers** — connected providers, popular/available ones, connect/disconnect.

**Models** — the model catalog with visibility toggles.

## 18. Session guard (optional proxy)

If you run the optional session-guard proxy, the app detects it and:

- pins a session's provider/model/variant/agent, rewriting prompts to match;
- shows whether the guard is **active**, **not configured** or **offline**;
- exposes request/error/latency metrics and the number of guarded sessions;
- can **auto-adopt drift** (when another client changes the selection, the app
  follows instead of fighting it);
- handles the guard's one-shot `409` by retrying once without the stale revision,
  and explains a conflict instead of silently losing a prompt.

Without the proxy everything works the same, minus the pinning and the metrics.

## 19. Privacy, security and data

- **No analytics, no telemetry, no third-party SDKs.** The app talks only to the
  server you configure.
- **Client-side errors** may be reported as a short, sanitized single line to
  **your own** server's `/log` endpoint (or the guard's client-log route). Never
  message content, `.env` values or credentials.
- **Secrets** — credentials and the guard token live encrypted (Android Keystore)
  and are excluded from cloud backup and device transfer; cached conversation text
  and crash reports are excluded as well.
- **Network** — plain HTTP is permitted deliberately (OpenCode serves HTTP on your
  own network); system CAs only, no user-added CAs. See
  `app/src/main/res/xml/network_security_config.xml`.
- **Least surprise** — an Android 16 device is asked for `ACCESS_LOCAL_NETWORK`
  before LAN traffic; a denied notification permission is detected instead of
  silently swallowing alerts.

## 20. Web/TUI parity notes

Verified against a live server (Playwright + captured wire frames), not assumed:

| Area          | Parity                                                                                                              |
| ------------- | ------------------------------------------------------------------------------------------------------------------- |
| Message queue | `POST /prompt` + `"delivery":"queue"`, inbox cards, Steer/Remove, server-side delivery                              |
| Compaction    | `POST /compact` with the client-generated id, "Session compaction started"/"compacted" wording, summary, usage line |
| Live status   | the server's real event set (`session.step.*`, `session.tool.*`, `session.text/reasoning.delta`)                    |
| Session list  | provider/model/agent/time/cost line, running & retrying badges                                                      |
| Tool rows     | Title Case names ("Shell", "Read", "Edit"), `…`-capped output, collapsed repeats                                    |
| Permissions   | the server's reply values and the actionable-notification flow                                                      |
| Questions     | typed answers validated per field, option _values_ rather than labels                                               |
| Model picking | `provider/model` identity, variant field, connect/provider definition                                               |

Where the web has something the client deliberately does not implement (e.g.
reordering queued prompts, which the API has no field for), the app omits it
instead of faking it.

## 21. Limits and honest caveats

- **No push service.** Background notifications need a living process; the
  foreground `ConnectionService` keeps it alive while the app is open (Android 15+
  caps a `dataSync` service at roughly six hours per day). If the system kills the
  app, nothing is delivered retroactively — the next start resyncs.
- **Queued prompts cannot carry attachments** (see §5).
- **Archiving is local** — OpenCode 2.x has no server-side archive write path, so
  the app keeps the archived set on the device.
- **Session deletion is real** — deleted projects/sessions stay gone; the app
  filters the server's list against your keep-list to guarantee that.
- **Long sessions** are paged, not fully loaded: older messages come in pages with
  a cursor, and page sizes are capped to keep memory in check (a 200-message page
  of a heavy session was measured at 14 MB and caused an `OutOfMemoryError`).

## 22. Feature → API map

| Feature | Endpoint(s) |
| --- | --- |
| Session list / create / get | `GET/POST /api/session`, `GET /api/session/{id}` |
| Rename / fork / revert | `PATCH /api/session/{id}`, `POST …/{id}/fork`, `POST …/{id}/revert/stage`, `POST …/{id}/revert/commit` |
| Messages | `GET /api/session/{id}/message` (`{data[], cursor}`) |
| Live events | `GET /api/event` (SSE) |
| Send a prompt | `POST /api/session/{id}/prompt` (`delivery: steer` \| `queue`) |
| Message queue | `GET …/{id}/inbox`, `PATCH …/inbox/{id}`, `DELETE …/inbox/{id}` |
| Slash commands | `GET /api/command`, `POST /api/session/{id}/command` |
| Interrupt | `POST /api/session/{id}/interrupt` |
| Compaction | `POST /api/session/{id}/compact` |
| Permissions | `GET /api/permission/request`, `GET /api/session/{id}/permission`, `POST …/permission/{rid}/reply` |
| Questions / forms | `GET /api/session/{id}/form`, `GET …/form/{formID}`, `POST …/form/{fid}/reply` |
| Models / providers / agents | `GET /api/model`, `GET /api/provider`, `GET /api/integration`, `POST /api/session/{id}/model`, `GET /api/agent`, `POST /api/session/{id}/agent` |
| Credentials | `POST /api/integration/{id}/connect/key`, `…/connect/oauth`, `…/oauth/{attemptID}/complete`, `DELETE /api/credential/{id}` |
| MCP / skills | `GET/POST /api/mcp`, `POST /api/experimental/mcp/{name}/connect` \| `disconnect`, `GET /api/skill` |
| Files / diff / VCS | `GET /api/fs/list`, `GET /api/fs/read/{path}`, `GET /api/fs/find`, `GET /api/session/{id}/diff`, `GET /api/vcs/status`, `GET /api/vcs/diff` |
| Context / usage | `GET /api/session/{id}/context`; per-message `tokens`/`cost` from the message list |
| Projects / locations | `GET /api/project`, `GET /api/location`, `POST /api/location/reload` |
| Session export | `POST /api/experimental/session/{id}/export` |
| Server info / client errors | `GET /api/info`; client errors go best-effort to the guard's client-log route (a plain OpenCode server has no `/log`) |

---

[Back to the README](../README.md) · [Architecture](ARCHITECTURE.md) ·
[OpenCode API notes](OPENCODE-API.md) · [Releasing](RELEASING.md)
