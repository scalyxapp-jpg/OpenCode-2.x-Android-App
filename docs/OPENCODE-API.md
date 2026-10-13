# OpenCode 2.x server API — client compatibility contract

This document is the wire-protocol reference the Android client relies on. It is
written for contributors who need to change or debug the client↔server mapping.
The single V1→V2 shape bridge lives in
`app/src/main/java/com/opencode/android/data/OpenCodeApiAdapter.kt`.

Source of truth for this contract: the live server OpenAPI
(`GET /openapi.json`, re-verified against **2.0.26** on 2026-10-13), the shipped v2 SDK types
(`@opencode-ai/sdk/dist/v2/gen/types.gen.d.ts`), and payloads captured from the
running server. Every claim below was verified against the live server.

## Global rules

1. **Everything is namespaced under `/api`.** The V1 routes (`/session`,
   `/agent`, `/provider`, `/file`, `/vcs`, `/permission`, `/question`, `/mcp`,
   `/pty`, `/global/*`, `/project`, `/config/providers`, `/find/file`, `/path`,
   `/tui/*`, `/experimental/*`) are **gone** — the server answers them with the
   SPA HTML (HTTP 200, `content-type: text/html`), which is worse than a 404.
2. **Directory scoping is a header, not a query param.**
   Send `x-opencode-directory: <absolute path>`. `?directory=` is ignored and
   the server falls back to its own active location. Responses echo the
   resolved location in a top-level `location` field on many endpoints.
3. **List envelopes.** Most list endpoints return `{ "location"?: {...},
"data": [...] }`. Some return a cursor: `{ "data": [...], "cursor":
{ "previous": string|null, "next": string|null } }`.
4. **Errors** are typed objects: `{"_tag":"SessionNotFoundError", ...}` with the
   HTTP status. `{"message": ...}` is not guaranteed.
5. **SSE wire format** (`GET /api/event`): each `data:` line is
   `{"id":..., "created":..., "type":"...", "location"?:{...}, "data":{...},
"durable"?:{...}}`. The payload is under `data`, **not** `properties` and
   **not** wrapped in `payload`.

## Endpoint map (app V1 → 2.x)

| Capability           | 2.x endpoint                                                                                          | Notes                                                                                                                                                                                            |
| -------------------- | ----------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------- | -------- |
| Session list         | `GET /api/session`                                                                                    | `{data[], cursor}`. Params: `limit`, `order`, `search`, `parentID` (`null` = roots), `project`, `subpath`, `cursor`.                                                                             |
| Session create       | `POST /api/session`                                                                                   | body optional                                                                                                                                                                                    |
| Session get          | `GET /api/session/{id}`                                                                               | `{data: Session}`                                                                                                                                                                                |
| Session update       | `PATCH /api/session/{id}`                                                                             | `{title?, metadata?, permissions?}` (no `time.archived`)                                                                                                                                         |
| Session delete       | `DELETE /api/session/{id}`                                                                            |                                                                                                                                                                                                  |
| Active sessions      | `GET /api/session/active`                                                                             | `{data:{ "<id>": {"type":"running"                                                                                                                                                               | ...} }}` |
| Session fork         | `POST /api/session/{id}/fork`                                                                         |                                                                                                                                                                                                  |
| Switch agent         | `POST /api/session/{id}/agent`                                                                        | `{agent}`                                                                                                                                                                                        |
| Switch model         | `POST /api/session/{id}/model`                                                                        | `{model:{id, providerID, variant?}}`                                                                                                                                                             |
| Send prompt          | `POST /api/session/{id}/prompt`                                                                       | `{id?, text, files?, agents?, skills?, metadata?, delivery?, resume?}`. **No model/agent in body** — set via switch endpoints. Response `{data:{id, sessionID, time, type, payload, delivery}}`. |
| Interrupt            | `POST /api/session/{id}/interrupt`                                                                    | (replaces both interrupt + abort)                                                                                                                                                                |
| Run command          | `POST /api/session/{id}/command`                                                                      |                                                                                                                                                                                                  |
| Compact              | `POST /api/session/{id}/compact`                                                                      | `{id?, delivery?}`                                                                                                                                                                               |
| Revert stage         | `POST /api/session/{id}/revert/stage`                                                                 |                                                                                                                                                                                                  |
| Revert clear         | `DELETE /api/session/{id}/revert`                                                                     |                                                                                                                                                                                                  |
| Revert commit        | `POST /api/session/{id}/revert/commit`                                                                |                                                                                                                                                                                                  |
| Context              | `GET /api/session/{id}/context`                                                                       | `{data:[messages]}`                                                                                                                                                                              |
| Diff                 | `GET /api/session/{id}/diff`                                                                          | `{data:[...]}`                                                                                                                                                                                   |
| Messages             | `GET /api/session/{id}/message`                                                                       | `{data[], cursor}`. Params `limit`, `order`, `cursor`, `type`.                                                                                                                                   |
| Single message       | `GET /api/session/{id}/message/{mid}`                                                                 |                                                                                                                                                                                                  |
| Children             | `GET /api/session?parentID={id}`                                                                      | no dedicated endpoint                                                                                                                                                                            |
| Permission list      | `GET /api/permission/request`                                                                         | `{location, data:[PermissionV2Request]}`                                                                                                                                                         |
| Permission reply     | `POST /api/session/{sid}/permission/{rid}/reply`                                                      | `{decision, message?}` (decision = `once                                                                                                                                                         | always   | reject`) |
| Form list            | `GET /api/form`, `GET /api/session/{sid}/form`                                                        | `{location?, data:[Form]}`                                                                                                                                                                       |
| Form reply           | `POST /api/session/{sid}/form/{fid}/reply`                                                            | `{answer:{key:value}}`                                                                                                                                                                           |
| Agents               | `GET /api/agent`                                                                                      | `{location, data:[{id,name,description?,mode?,...}]}`                                                                                                                                            |
| Models               | `GET /api/model`                                                                                      | `{location, data:[ModelV2Info]}` (flat, all providers)                                                                                                                                           |
| Model default        | `GET /api/model/default`                                                                              | `{location, data:ModelV2Info}`                                                                                                                                                                   |
| Providers            | `GET /api/provider`                                                                                   | `{location, data:[ProviderV2Info]}` (no models!)                                                                                                                                                 |
| Config shell         | `GET /api/config/shell`                                                                               | bare array `[{path,name,acceptable}]`                                                                                                                                                            |
| Server info          | `GET /api/info`                                                                                       | `{version,pid,urls,paths,capabilities}` (replaces api/health + global/health)                                                                                                                    |
| Location             | `GET /api/location`, `POST /api/location/reload`                                                      | replaces project/current + global/dispose                                                                                                                                                        |
| Project list         | `GET /api/project`                                                                                    | bare array                                                                                                                                                                                       |
| Project update       | `PATCH /api/project/{id}`                                                                             |                                                                                                                                                                                                  |
| VCS                  | `GET /api/vcs`                                                                                        | `{location, data:{provider?, branch:{current?,default?}}}`                                                                                                                                       |
| VCS status           | `GET /api/vcs/status`                                                                                 | `{location, data:[VcsFileDiff]}`                                                                                                                                                                 |
| VCS diff             | `GET /api/vcs/diff`                                                                                   | `{location, data:[VcsFileDiff]}` (mode param?)                                                                                                                                                   |
| VCS branches         | `GET /api/vcs/branch`                                                                                 |                                                                                                                                                                                                  |
| FS list              | `GET /api/fs/list?path=…`                                                                             | `{location, data:[{path,type}]}`; relative paths, dirs have trailing `/`                                                                                                                         |
| FS find              | `GET /api/fs/find?query=…&type=…&limit=…`                                                             | `{location, data:[{path,type}]}`                                                                                                                                                                 |
| FS read              | `GET /api/fs/read/{path}`                                                                             | file content                                                                                                                                                                                     |
| MCP                  | `GET /api/mcp`                                                                                        | `{location, data:[{name, status:{status, error?}}]}`                                                                                                                                             |
| MCP connect          | `POST /api/experimental/mcp/{server}/connect`                                                         |                                                                                                                                                                                                  |
| MCP disconnect       | `POST /api/experimental/mcp/{server}/disconnect`                                                      |                                                                                                                                                                                                  |
| MCP config           | `PUT/DELETE /api/experimental/mcp/{server}`                                                           |                                                                                                                                                                                                  |
| Commands             | `GET /api/command`                                                                                    | `{location, data:[{name,description?,...}]}`                                                                                                                                                     |
| Skills               | `GET /api/skill`                                                                                      | `{location, data:[Skill.Info]}`                                                                                                                                                                  |
| Credentials          | `GET/POST /api/credential`, `PATCH/DELETE /api/credential/{id}`, `POST /api/credential/{id}/activate` | replaces `/auth/{id}`                                                                                                                                                                            |
| Integrations         | `GET /api/integration`, `GET /api/integration/{id}`                                                   |                                                                                                                                                                                                  |
| Integration key auth | `POST /api/integration/{id}/connect/key`                                                              | replaces `PUT /auth/{id}` for API keys                                                                                                                                                           |
| Integration OAuth    | `POST /api/integration/{id}/connect/oauth` → `/{attemptID}` status → `/{attemptID}/complete`          | replaces `provider/{id}/oauth/*`                                                                                                                                                                 |
| PTY                  | `GET/POST /api/pty`, `GET/PUT/DELETE /api/pty/{id}`                                                   | replaces `/pty*`                                                                                                                                                                                 |
| Shell run            | `POST /api/shell`, `GET /api/shell`, `GET /api/shell/{id}/output`                                     |                                                                                                                                                                                                  |
| Websearch            | `POST /api/websearch`, `GET /api/websearch/provider`                                                  |                                                                                                                                                                                                  |
| Worktree             | `GET/POST/DELETE /api/worktree`, `POST /api/worktree/refresh`                                         |                                                                                                                                                                                                  |
| Reference            | `GET /api/reference`                                                                                  |                                                                                                                                                                                                  |
| Plugin               | `GET /api/plugin`, `POST /api/plugin/check`, `POST /api/plugin/update`                                |                                                                                                                                                                                                  |
| Pairing              | `POST /api/pair`, `GET /auth/connect/{code}`                                                          |                                                                                                                                                                                                  |
| `question`           | **removed** → Forms                                                                                   |                                                                                                                                                                                                  |
| `todo` (GET)         | **removed** → `todo.updated` event only                                                               |                                                                                                                                                                                                  |
| `share`              | not exposed in `/api` (session has `share.url` when shared)                                           |                                                                                                                                                                                                  |
| `tui/*`              | **removed**                                                                                           |                                                                                                                                                                                                  |

## Session shape (`Session.Info`)

```
{ id, projectID, workspaceID?, parentID?, agent?, model?:{id,providerID,variant?},
  cost?, tokens?:{input,output,reasoning,cache:{read,write}},
  summary?:{additions,deletions,files}, time:{created,updated,idle?,viewed?,archived?,compacting?},
  title, location:{directory}, subpath?, metadata?, permissions?, revert?, outcome? }
```

Note: directory is `location.directory`, **not** `directory`; time has no
`archived` write path via PATCH.

## Messages (`GET /api/session/{id}/message`)

`{data:[SessionMessage], cursor:{previous,next}}` where `SessionMessage.type` is
one of `user | assistant | synthetic | system | shell | agent-switched |
model-switched | location-switched | skill | compaction`.

- **user**: `{id, time:{created}, text, files?:[], agents?:[], skills?:[], type:"user"}`
- **assistant**: `{id, time:{created,completed?,streamed?}, type:"assistant", agent,
model:{id,providerID,variant?}, content:[parts], snapshot?, finish?, rawFinish?,
cost?, tokens?, error?}`
    - parts:
        - `{type:"text", text}`
        - `{type:"reasoning", text, time?}`
        - `{type:"tool", id, name, provider?:{executed}, state:{status:"pending"|"running"|"completed"|"error",
  input, content:[{type:"text",text}|{type:"file",...}], structured?, result?, attachments?, error?}, time:{created,ran?,completed?}}`
- **shell**: `{id, type:"shell", command, output, callID, time}`
- **compaction**: `{id, type:"compaction", status:"running"|"completed"|"failed", reason:"auto"|"manual",
  model?, providerState?, providerContext?, summary, recent?, cost?, tokens?, error?}`
  — the summary streams through `session.compaction.delta` and ends up here; the web
  renders a divider notice ("Session compaction started"), the summary as Markdown,
  "Compacting" while running and "Session compacted · <in> in · <out> out" afterwards
  (usage = `tokens.input + cache.read + cache.write` in, `tokens.output + reasoning` out).
- Paging: `cursor` param replaces `before`; the cursor comes from
  `cursor.previous`/`cursor.next`. No `x-next-cursor` header.

## SSE event types (wire `type`, payload under `data`)

Inbox (queued prompts — see the section below): `session.inbox.enqueued`,
`session.inbox.delivered`, `session.inbox.cancelled`, `session.inbox.delivery.changed`.

Sessions: `session.created`, `session.updated`, `session.renamed`,
`session.deleted`, `session.moved`, `session.idle`, `session.error`,
`session.status`, `session.diff`, `session.compacted`, `session.todo`?,
`todo.updated`.
Messages/assistant stream: `message.updated`, `message.removed`,
`message.part.updated`, `message.part.delta`, `message.part.removed`,
`session.text.started|delta|ended`, `session.reasoning.started|delta|ended`,
`session.step.started|ended|streamed|failed`, `session.tool.input.started|delta|ended`,
`session.tool.called|progress|success|failed`, `session.retry.scheduled`,
`session.shell.started|ended`, `session.compaction.started|delta|ended`,
`session.revert.staged|cleared|committed`, `session.agent.selected`,
`session.model.selected`, `session.execution.started|succeeded|failed|interrupted`.
Requests: `permission.asked`, `permission.replied`, `permission.v2.asked`,
`question.asked`, `question.v2.asked`, `question.replied`, `question.rejected`.
Misc: `server.connected`, `project.updated`, `file.edited`, `lsp.updated`,
`pty.*`, `shell.created|exited|deleted`, `mcp.*`, `installation.updated`,
`global.disposed`, `reference.updated`, `workspace.*`, `worktree.*`,
`vcs.branch.updated`, `plugin.updated`, `command.executed`.

Assistant stream events carry `{sessionID, assistantMessageID, ...}`; text/reasoning
deltas carry `{textID|reasoningID, delta}`; tool events carry `{callID, tool|name,
input|content|structured|error, provider}`.

## Prompt, message queue (inbox) and compaction

Verified against the live server (request bodies captured with browser automation
and replayed with `curl`/Python; the app's unit tests pin the same shapes).

### Sending a prompt

```
POST /api/session/{id}/prompt
{
  "id": "msg_…",                  # client-generated, echoed on the message (dedupe key)
  "text": "…",
  "files": [], "agents": [], "skills": [],
  "metadata": { … },              # optional, web sends displayText/agent/model here
  "delivery": "steer" | "queue"   # optional; omit for the server default (steer)
}
-> 200 {"data": <inbox item>}
```

The response is the **inbox item** (`Session.Inbox.User`), not the message: it
carries `delivery`, which is how the client knows whether the prompt ran now or was
queued. A prompt sent while a turn is running **without** `delivery:"queue"` steers
(interrupts) the turn — the app's send button does exactly that, and its queue
button sends `"queue"` (web parity: Ctrl+Enter).

### The queue

```
GET    /api/session/{id}/inbox            -> {data:[Session.Inbox.Info]}
DELETE /api/session/{id}/inbox/{inboxID}  -> 204          # web: "Remove"
PATCH  /api/session/{id}/inbox/{inboxID}  -> 204          # body {"delivery":"steer"}
                                                          # web: "Steer" (deliver now)
```

`Session.Inbox.Info` is one of `user | synthetic | compaction | move`:

```
{ "id":"msg_…", "sessionID":"ses_…", "time":{"created":…},
  "type":"user", "payload":{"text":"…","files":[],"agents":[],"skills":[],"metadata":{}},
  "delivery":"queue" }
```

Behaviour worth knowing:

- Delivery is **server-side**: the oldest queued prompt becomes a real message when
  the running turn ends. While the session is idle, `delivery:"queue"` is delivered
  immediately (verified: the inbox is empty right after the call).
- A `PATCH` to `steer` keeps the item in the inbox for a moment but marks it for
  immediate delivery; it leaves the inbox once delivered.
- `DELETE` removes it without running it.
- There is **no reorder field** (the API has no order attribute), so the client must
  not pretend to reorder.
- Attachments in a queued prompt would have to travel as base64 file parts inside the
  inbox payload; the app refuses to queue attachments instead of dropping them.

### Compaction

```
POST /api/session/{id}/compact     body {"id":"msg_…"}      # client-generated id, nothing else
-> 200 {"data": <inbox item, type:"compaction", delivery:"steer">}
```

The client-generated id is echoed on the compaction **message**
(`type:"compaction"`, `status:"running"|"completed"|"failed"`, `summary`, `tokens`),
which is what the transcript renders. Sending an empty body makes the server mint an
id the client cannot follow, and a URL-encoded `x-opencode-directory` header makes the
server look for a location literally named `%2F…` — both were real bugs here.

### Forms (agent questions)

```
POST /api/session/{id}/form/{formID}/reply     body {"answer":{<key>:<typed value>}}
```

The server validates the **JSON type per field**: `string` → string,
`multiselect` → array of the option **values** (not labels), `number`/`integer` →
number, `boolean` → boolean. Sending labels produced `FormInvalidAnswerError`; the
typed value is what the server accepts. Errors: `FormNotFoundError`,
`FormAlreadySettledError`, `FormInvalidAnswerError`. Form ids match `^frm_…`; an SSE-built
question card can carry a non-form id, so the reply is retried once with the server's
pending form id.

### Extensions (Settings → Extensions)

```
GET  /api/mcp                                   -> [{name, status:{status}}]   # connected|disabled|failed|needs_auth|pending
POST /api/experimental/mcp/{server}/connect     -> 204    # per-server switch ON
POST /api/experimental/mcp/{server}/disconnect  -> 204    # per-server switch OFF
PUT  /api/experimental/mcp/{server}             -> 204    # body {config} (web: add/update)
DELETE /api/experimental/mcp/{server}           -> 204    # web: remove
GET  /api/mcp/resource                          -> {data:{resources:[{server,name,uri,description,mimeType}]}}
GET  /api/plugin                                -> [{id, source:{type}, features:{…}, state:{status}}]
GET  /api/skill                                 -> [{id, name, description, autoinvoke?, path?}]
```

The toggle endpoints were read out of the web bundle (`mcp.connect`/`mcp.disconnect`)
and re-verified against the running server: switching a server on/off issues exactly
one `POST …/connect|disconnect` and returns 204. The MCP list is **not** scoped per
session in the app's Extensions tab (the web shows "available to all projects"), so
the request is sent without a directory header.

## Model / provider

- `/api/model` item: `{id, modelID, providerID, family?, name, package, settings,
headers?, capabilities:{tools,input:[],output:[]}, variants:[{id,settings}],
time:{released}, cost:[{input,output,cache:{read,write}}], status, enabled,
limit:{context,input?,output}}`.
- `/api/provider` item: `{id, integrationID?, name, activation, package, settings,
headers?}`. Models are **not** nested — join via `/api/model` `providerID`.

## Migration status (Android client)

Implemented and compiling/tests green:

- `data/OpenCodeV2Api.kt` (Retrofit v2 surface) + `data/OpenCodeApiAdapter.kt`
  (V1→V2 shape bridge) behind the unchanged `data/OpenCodeApi.kt` interface.
- SSE: `SseClient` reads `/api/event` with a manual line reader (so the
  `: heartbeat` comment resets the liveness watchdog — okhttp-sse hides
  comments); `V2EventNormalizer` maps v2 events onto the existing vocabulary.
- Messages/sessions decode the v2 envelopes; session directory resolved from
  `location`; project root from `canonical`.
- Provider connect/disconnect via `/api/credential`; provider auth methods and
  OAuth via `/api/integration`.
- Questions map to `/api/form`; reject maps to form cancel.
- Todos arrive via the `todo.updated` event (`StreamEffect.TodosUpdated`).

Known gaps (no native 2.x endpoint / deliberately not wired):

- Server self-upgrade: 2.x has no upgrade endpoint (updates follow the `update`
  config); the dead Settings action was removed.
- `shareSession` uses the session's existing `share.url` (there is no share
  endpoint; the server `share` config controls it).
- PTY/TUI routes are not used by the UI and are not declared.
- `getTodos` returns empty; the panel is driven entirely by `todo.updated`.
- `markViewed` (`POST /api/session/{id}/view`) is not called (informational only).
