package com.opencode.android.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class Session(
    val id: String,
    val slug: String? = null,
    @SerialName("projectID") val projectId: String? = null,
    val directory: String? = null,
    // OpenCode 2.x nests the working directory under `location`.
    val location: SessionLocation? = null,
    // Working directory as reported by the server in some V1 payloads. Use
    // [directory] (filled from `location.directory` by the adapter) as the
    // canonical path; this field is only a fallback.
    val path: String? = null,
    val title: String? = null,
    val version: String? = null,
    val time: SessionTime? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
    // Present on GET /session?directory=... items (web home list).
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val summary: SessionSummary? = null,
    @SerialName("status") val status: SessionStatus? = null,
    @SerialName("sessionGuard") val sessionGuard: SessionGuard? = null,
    // OpenCode 2.x reports an existing share link here; sharing itself is
    // controlled by the server `share` config (manual|auto|disabled), not an
    // endpoint.
    val share: SessionShare? = null,
)

@Serializable
@Immutable
data class SessionShare(
    val url: String? = null,
)

@Serializable
@Immutable
data class SessionGuard(
    val revision: Long = 0,
    @SerialName("providerID") val providerID: String = "",
    @SerialName("modelID") val modelID: String = "",
    val variant: String = "default",
    val agent: String = "build",
    val mismatch: Boolean = false,
    @SerialName("serverProviderID") val serverProviderID: String = "",
    @SerialName("serverModelID") val serverModelID: String = "",
    @SerialName("serverAgent") val serverAgent: String = "",
)

@Serializable
@Immutable
data class SessionGuardMetrics(
    val requests: Int = 0,
    val errors: Int = 0,
    @SerialName("avg_ms") val avgMs: Double = 0.0,
    @SerialName("max_ms") val maxMs: Double = 0.0,
)

@Serializable
@Immutable
data class SessionGuardHealth(
    val ok: Boolean = false,
    val service: String = "",
    val upstream: String = "",
    val circuit: String = "closed",
    @SerialName("guardedSessions") val guardedSessions: Int = 0,
    val metrics: SessionGuardMetrics? = null,
)

@Serializable
@Immutable
data class UploadResponse(
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
)

@Serializable
@Immutable
data class SessionModel(
    val id: String? = null,
    val provider: String? = null,
    // /session list items use providerID instead of provider.
    val providerID: String? = null,
    val model: String? = null,
    // SSE message.updated info.model uses { providerID, modelID }.
    @SerialName("modelID") val modelID: String? = null,
    val variant: String? = null,
)

// Extra fields the web home list receives from GET /session?directory=...
// (cost, tokens, file summary). All optional so /api/session still parses.
@Serializable
@Immutable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

@Serializable
@Immutable
data class SessionLocation(
    val directory: String? = null,
)

@Serializable
@Immutable
data class SessionCreateRequest(
    val location: SessionLocation? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
)

@Serializable
@Immutable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
    val archived: Long? = null,
)

@Serializable
@Immutable
data class SessionListResponse(
    val data: List<Session> = emptyList(),
    val cursor: Cursor? = null,
)

@Serializable
@Immutable
data class SessionResponse(
    val data: Session,
)

@Serializable
@Immutable
data class Cursor(
    val previous: String? = null,
    val next: String? = null,
)

// Two message SHAPES exist side by side in this model:
// - { info: { role, agent, model, time }, parts[] }
// - { type, agent, model, text?, content[], cost?, tokens? }
// On V1 they came from different routes; V2 serves both through
// `/api/session/{id}/message`, so the double fetch in ChatRepository exists to
// reconcile/retry, not to switch schemas. Both shapes are still parsed so an
// older server keeps working.
@Serializable
@Immutable
data class Message(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String? = null,
    val type: String? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
    val text: String? = null,
    val parts: List<Part> = emptyList(),
    val content: List<ContentPart> = emptyList(),
    val time: MessageTime? = null,
    // Server sends an object (e.g. { name: "MessageAbortedError", data: { message } }),
    // older/classic endpoints may send a plain string → keep it as raw JSON.
    val error: kotlinx.serialization.json.JsonElement? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    // Compaction messages (`type = "compaction"`) carry their own state instead of
    // a role: `status` (running/completed/failed), `reason` (auto/manual) and the
    // generated `summary` markdown. Rendered as the web's compaction block.
    val status: String? = null,
    val reason: String? = null,
    val summary: String? = null,
    // Web endpoint structure: { info, parts }
    val info: MessageInfo? = null,
)

@Serializable
@Immutable
data class MessageInfo(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val role: String? = null,
    val time: MessageTime? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
    val error: kotlinx.serialization.json.JsonElement? = null,
    // Per-message usage. The web context panel reads these off the LAST
    // assistant message (not the session aggregate) — that is the current
    // context-window occupancy.
    val tokens: Tokens? = null,
    val cost: Double? = null,
)

@Serializable
@Immutable
data class ContentPart(
    val type: String? = null,
    val id: String? = null,
    val text: String? = null,
    // Tool content parts carry name/state/time instead of tool/title.
    val name: String? = null,
    val state: kotlinx.serialization.json.JsonElement? = null,
    val time: MessageTime? = null,
)

@Serializable
@Immutable
data class MessageTime(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
@Immutable
data class Part(
    // Both optional: a single malformed part (missing id/type) must NOT fail
    // deserialisation of the WHOLE message list, which previously left the
    // conversation empty.
    val id: String? = null,
    val type: String = "",
    val text: String? = null,
    val tool: String? = null,
    // New-schema tool parts (inside content[]) use "name" instead of "tool".
    val name: String? = null,
    val state: kotlinx.serialization.json.JsonElement? = null,
    val title: String? = null,
    val time: PartTime? = null,
    // Which message this part belongs to. Used to tell a USER part (the prompt
    // echoed back by the server) from an ASSISTANT part, so the prompt is never
    // rendered as the live assistant response.
    @SerialName("messageID") val messageId: String? = null,
)

// Session-scoped agent questions:
// GET /api/session/{id}/question → { data: [{ id, questions[], tool{messageID,callID} }] }
@Serializable
@Immutable
data class QuestionOption(
    val label: String,
    /**
     * The value the server expects in an answer (`Form.Option.value`).
     *
     * The answer is validated against this, NOT against the label — sending labels
     * produced `FormInvalidAnswerError`, which surfaced to the user as "the question
     * has expired" while the answer never arrived. Falls back to the label.
     */
    val value: String? = null,
    val description: String? = null,
)

@Serializable
@Immutable
data class QuestionItem(
    val question: String? = null,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean = false,
)

@Serializable
@Immutable
data class QuestionToolRef(
    val messageID: String? = null,
    val callID: String? = null,
)

@Serializable
@Immutable
data class SessionQuestion(
    val id: String,
    @SerialName("sessionID") val sessionId: String? = null,
    val questions: List<QuestionItem> = emptyList(),
    val tool: QuestionToolRef? = null,
)

@Serializable
@Immutable
data class SessionQuestionListResponse(
    val data: List<SessionQuestion> = emptyList(),
)

// Retrofit + kotlinx.serialization cannot serialize Map bodies with generics,
// so the question reply uses a dedicated model. answers is string[][] — one
// answer array per question (mirrors web: answers.map(s => [...s])).
@Serializable
@Immutable
data class QuestionReplyRequest(
    val answers: List<List<String>>,
)

/**
 * A pending tool-permission prompt. Shape mirrors `GET /permission` (the same
 * object the web permission dialog renders).
 */
@Serializable
@Immutable
data class PermissionRequest(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    // The server's `action` value for the pending request (V1 called the field
// "permission", e.g. "bash"; V2 sends a free-form `action`, e.g. "Read file").
// It is shown to the user and must not be interpreted as a fixed tool id.
    val permission: String? = null,
    val title: String? = null,
    val description: String? = null,
    val metadata: kotlinx.serialization.json.JsonElement? = null,
    val time: MessageTime? = null,
)

/**
 * Reply values are fixed by the protocol (verified in the web bundle):
 * "once" (Allow once), "always" (Allow always), "reject" (Deny).
 */
@Serializable
@Immutable
data class PermissionReplyRequest(
    val reply: String,
)

// Web prompt flow (verified via browser trace):
// POST /session/{id}/prompt_async with client-generated msg_/prt_ IDs,
// agent + model per call and a parts[] array. 204 + everything via SSE.
@Serializable
@Immutable
data class PromptAsyncPart(
    val id: String,
    // No default: Kotlinx omits default values, and the server requires `type`.
    val type: String,
    val text: String? = null,
    // File parts (web: {"type":"file","mime":"image/png","url":"data:...","filename":"x.png"}).
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
)

@Serializable
@Immutable
data class PromptAsyncModel(
    val modelID: String,
    val providerID: String,
)

@Serializable
@Immutable
data class PromptAsyncRequest(
    val messageID: String,
    val agent: String? = null,
    val model: PromptAsyncModel? = null,
    // Web sends the variant as a top-level field (verified via browser trace):
    // {"agent":"oracle","model":{...},"variant":"high","parts":[...]}
    val variant: String? = null,
    /**
     * How the server should treat this prompt: `steer` (default) runs it now,
     * `queue` keeps it for after the running turn — what Ctrl+Enter does in the
     * web composer. Verified against the live server.
     */
    val delivery: String? = null,
    val parts: List<PromptAsyncPart>,
)

/** `GET /api/session/{id}/inbox` item payload (what the user queued). */
@Serializable
@Immutable
data class InboxPayload(
    val text: String? = null,
)

/**
 * One entry of the session INBOX: a prompt that is waiting to be delivered.
 *
 * The web renders these in the transcript (badge "Queued") and offers
 * "Steer" (deliver now, `PATCH …/inbox/{id}` `{delivery:"steer"}`) and
 * "Remove" (`DELETE …/inbox/{id}`). Verified against the live server.
 */
@Serializable
@Immutable
data class InboxItem(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val type: String? = null,
    val time: MessageTime? = null,
    val payload: InboxPayload? = null,
    val delivery: String? = null,
)

@Serializable
@Immutable
data class PartTime(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
@Immutable
data class Agent(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val mode: String? = null,
)

/**
 * Web-shape agent entry from `GET /agent?directory=`: the identifier field is
 * `name` (the legacy `GET /api/agent` uses `id`). Mapped to [Agent] with
 * `id = name`.
 */
@Serializable
@Immutable
data class ProjectAgent(
    /** Wire `id`: the value a prompt must reference. */
    val id: String,
    /** Wire `name`: the human-readable label (falls back to the id). */
    val name: String? = null,
    val description: String? = null,
    val mode: String? = null,
)

@Serializable
@Immutable
data class ModelLimit(
    val context: Long? = null,
    val output: Long? = null,
)

@Serializable
@Immutable
data class Model(
    /** Canonical model id (wire `id` / `modelID`, provider prefix stripped). */
    val id: String,
    val name: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    // Second, wire-side model id (V1 `model` field). Use [id] as the canonical
    // id; this one is only kept so the availability guard accepts both forms.
    val model: String? = null,
    // GET /provider models carry { limit: { context, output } }; this is the
    // authoritative context window the web UI shows ("Context Limit").
    val limit: ModelLimit? = null,
)

@Serializable
@Immutable
data class Provider(
    val id: String,
    val name: String? = null,
    val models: List<Model> = emptyList(),
)

// GET /provider returns { all: [ { id, name, models: { "<provider>/<model>": Model } } ], ... }
// The web renders its model picker from exactly this payload.
@Serializable
@Immutable
data class ProvidersResponse(
    val all: List<ProviderEntry> = emptyList(),
    // Never populated by the V2 adapter (the route has no default map). An empty
    // map therefore means "not reported", NOT "the server has no default".
    val default: Map<String, String> = emptyMap(),
    /**
     * Provider ids the SERVER OFFERS (V2 `/api/provider` = available/activated).
     * This is NOT an auth signal: a provider can be listed without credentials.
     * The name is carried over from the V1 wire field.
     */
    val connected: List<String> = emptyList(),
)

@Serializable
@Immutable
data class HealthResponse(
    val healthy: Boolean = false,
    // GET /global/health also reports the serve version (web shows it in Settings).
    val version: String? = null,
)

// V1 route only. V2 has no GET /session/{id}/todo; todos arrive via the
// `todo.updated` event (an empty list is therefore "unsupported", not "none").
@Serializable
@Immutable
data class TodoItem(
    val content: String,
    val status: String? = null,
    val priority: String? = null,
)

// GET /vcs?directory=… → { branch, default_branch }
@Serializable
@Immutable
data class VcsInfo(
    val branch: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
)

// GET /vcs/diff?mode=git&directory=… → [{ file, patch }]
@Serializable
@Immutable
data class VcsDiffFile(
    val file: String,
    val patch: String? = null,
    // Stats the server already computes and the web renders directly
    // ("Files Changed 50", "+3894/-284", per-file "+66/-0", badge A/M/D).
    // The app used to ignore these and re-count "+"/"-" lines out of the patch
    // on every recomposition — slower, and it drifted from the web numbers.
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null,
)

// GET /pty/shells → [{ path, name, acceptable }]
@Serializable
@Immutable
data class PtyShell(
    val path: String,
    val name: String? = null,
    val acceptable: Boolean = true,
)

@Serializable
@Immutable
data class ProviderEntry(
    val id: String,
    val name: String? = null,
    val models: Map<String, Model> = emptyMap(),
    // Where the credentials came from: "env" | "config" | "api" | null.
    // null = the provider only exists because the config file names it (no
    // connection), so the UI shows no badge and offers no Disconnect.
    // Drives the badge and whether Disconnect is offered (web parity).
    val source: String? = null,
    val env: List<String> = emptyList(),
)

// GET /provider/auth → { "<providerID>": [ {type:"oauth"|"api", label, prompts?} ] }
@Serializable
@Immutable
data class ProviderAuthMethod(
    val type: String,
    val label: String? = null,
    val prompts: List<ProviderAuthPrompt> = emptyList(),
)

@Serializable
@Immutable
data class ProviderAuthPrompt(
    val type: String,
    val key: String,
    val message: String? = null,
    val placeholder: String? = null,
    // Conditional visibility: show only when another prompt has this value.
    @SerialName("when")
    val whenCondition: ProviderAuthCondition? = null,
)

@Serializable
@Immutable
data class ProviderAuthCondition(
    val key: String,
    val op: String,
    val value: String,
)

// PUT /auth/{providerID} body: {"type":"api","key":"…", …prompt values}
@Serializable
@Immutable
data class AuthSetRequest(
    val type: String,
    val key: String? = null,
    val prompts: Map<String, String> = emptyMap(),
)

// Wrapper for list endpoints that return { location, data }
@Serializable
@Immutable
data class DataListResponse<T>(
    val location: Location? = null,
    val data: List<T> = emptyList(),
)

@Serializable
@Immutable
data class Location(
    val directory: String? = null,
    val project: LocationProject? = null,
)

@Serializable
@Immutable
data class LocationProject(
    val id: String? = null,
    val directory: String? = null,
)

@Serializable
@Immutable
data class MessageListResponse(
    val data: List<Message> = emptyList(),
    val cursor: Cursor? = null,
)

@Serializable
@Immutable
data class PromptResponse(
    val data: PromptResult = PromptResult(),
)

@Serializable
@Immutable
data class PromptResult(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val prompt: PromptInput? = null,
    val delivery: String? = null,
)

@Serializable
@Immutable
data class PromptRequest(
    val prompt: PromptInput,
    val agent: String? = null,
    val model: String? = null,
)

@Serializable
@Immutable
data class PromptInput(
    val text: String,
)

@Serializable
@Immutable
data class ModelRefRequest(
    val model: ModelRef,
)

// Web shape for POST /api/session/{id}/model is { model: { providerID, id, variant? } }
// (verified: server rejects modelID with Missing key at ["model"]["id"]).
@Serializable
@Immutable
data class ModelRef(
    val id: String,
    @SerialName("providerID") val providerId: String,
    val variant: String? = null,
)

// LEGACY / proxy alias. The feed the app dials today (`/api/event`) sends
//   data: {"id":"evt_...","created":...,"type":"...","data":{...},"durable":...}
// and is decoded by V2EventNormalizer (which also accepts this older
// payload-wrapped shape). Do not read this as the current wire contract.
// The app previously parsed a top-level { type, data } shape, which never
// matched — every event decoded to type=null and the live stream was dead.
@Serializable
@Immutable
data class SseEnvelope(
    val payload: Event? = null,
)

@Serializable
@Immutable
data class Event(
    val id: String? = null,
    val type: String? = null,
    // Real server events carry their fields here (sessionID, part, delta, …).
    val properties: EventProperties? = null,
    // Legacy session.next.* shape used a top-level `data`; kept so any
    // still-emitted legacy event continues to parse.
    val data: EventData? = null,
)

@Serializable
@Immutable
data class EventProperties(
    @SerialName("sessionID") val sessionId: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    // message.part.delta carries the target part + incremental chunk.
    @SerialName("partID") val partId: String? = null,
    val field: String? = null,
    val delta: String? = null,
    // message.part.updated carries the full part snapshot.
    val part: Part? = null,
    // message.updated carries the message info (role/agent/model).
    val info: MessageInfo? = null,
    // session.status carries { type: "busy" | "idle" }.
    val status: SessionStatus? = null,
    // session.error carries { name, data: { message, … } }.
    val error: kotlinx.serialization.json.JsonElement? = null,
    // session.diff carries the changed-file list.
    val diff: List<VcsDiffFile> = emptyList(),
    val time: Long? = null,
    // question.asked carries the full pending request. v1 uses `id`, the v2
    // schema uses `requestID`; both are accepted.
    @SerialName("id") val questionId: String? = null,
    @SerialName("requestID") val requestId: String? = null,
    val questions: List<QuestionItem> = emptyList(),
    val tool: QuestionToolRef? = null,
    // `todo.updated` carries the session's current todo list (V2 has no
    // GET /session/{id}/todo endpoint).
    val todos: List<TodoItem> = emptyList(),
)

@Serializable
@Immutable
data class SessionStatus(
    val type: String? = null,
    // type == "retry": why the request is being retried and when the next try is.
    val message: String? = null,
    val attempt: Int? = null,
    val next: Long? = null,
)

@Serializable
@Immutable
data class EventData(
    val timestamp: Long? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    @SerialName("messageID") val messageId: String? = null,
    @SerialName("assistantMessageID") val assistantMessageId: String? = null,
    val textID: String? = null,
    val text: String? = null,
    val agent: String? = null,
    val model: SessionModel? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val prompt: PromptInput? = null,
    val delivery: String? = null,
    // step.failed / tool.failed carry { type, message } here.
    val error: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
@Immutable
data class Tokens(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: CacheTokens? = null,
)

@Serializable
@Immutable
data class CacheTokens(
    val read: Long? = null,
    val write: Long? = null,
)

@Serializable
@Immutable
data class Project(
    val id: String,
    val worktree: String? = null,
    // OpenCode 2.x names the project root `canonical`.
    val canonical: String? = null,
    val vcs: String? = null,
    val icon: ProjectIcon? = null,
    val time: SessionTime? = null,
    val sandboxes: List<String> = emptyList(),
)

@Serializable
@Immutable
data class ProjectIcon(
    val color: String? = null,
)

@Serializable
@Immutable
data class ContextUsage(
    val data: List<ContextItem> = emptyList(),
)

@Serializable
@Immutable
data class ContextItem(
    val id: String? = null,
    val type: String? = null,
    val text: String? = null,
    val time: MessageTime? = null,
)

@Serializable
@Immutable
data class ModelVariant(
    val id: String,
    val name: String? = null,
)

@Serializable
@Immutable
data class HistoryResponse(
    val data: List<HistoryEvent> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
@Immutable
data class HistoryEvent(
    val id: String? = null,
    val type: String? = null,
    val data: EventData? = null,
)

@Serializable
@Immutable
data class FileEntry(
    val name: String? = null,
    val path: String? = null,
    val absolute: String? = null,
    val type: String? = null,
    val ignored: Boolean = false,
)

// GET /path → filesystem locations for the file/project browser. In V2 the route
// only exposes the ACTIVE location, so `home` (the V1 wire name) is filled with
// that directory, NOT the serve host's home directory.
@Serializable
@Immutable
data class PathInfo(
    val home: String? = null,
    val state: String? = null,
    val config: String? = null,
    val worktree: String? = null,
    val directory: String? = null,
)

@Serializable
@Immutable
data class FileContent(
    val content: String? = null,
    val path: String? = null,
)

// Web composer: "/" lists commands (GET /command returns a bare array).
// Note: template is usually a string but can be an object (e.g. MCP
// commands return template:{}), so keep it lenient.
@Serializable
@Immutable
data class CommandEntry(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val template: kotlinx.serialization.json.JsonElement? = null,
)

/**
 * The command's template, when the server actually reports one. The V2
 * `/api/command` payload carries only `{name, description}`, so this is
 * effectively always null in production — callers must fall back to
 * "/name " and let the user type the arguments.
 */
fun commandTemplate(command: CommandEntry): String? =
    (command.template as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

// Client-side built-ins the server's GET /command does not return. Mirrors the
// web slash picker, which prepends these to the server commands.
const val BUILTIN_SOURCE = "builtin"

/**
 * Built-ins this app actually IMPLEMENTS. The "/" picker may only list a command
 * from here (or from the server), so a command is never offered that would do
 * nothing when tapped — the TUI's own client commands (/sessions, /themes, /undo,
 * /quit, …) have no server endpoint and therefore make no sense in this app.
 *
 * Every name here MUST have a branch in `ChatViewModel.runBuiltinCommand`;
 * `BuiltinCommandsTest` guards that the list and the handlers cannot drift apart.
 */
val IMPLEMENTED_BUILTIN_COMMANDS: Set<String> = setOf("compact", "mcp")

val BUILTIN_COMMANDS: List<CommandEntry> =
    listOf(
        CommandEntry(
            name = "compact",
            description = "Summarize the session to reduce context size",
            source = BUILTIN_SOURCE,
        ),
        CommandEntry(
            name = "mcp",
            description = "Toggle MCPs",
            source = BUILTIN_SOURCE,
        ),
    )

// GET /mcp → { "<name>": { "status": "connected" | "disabled" | "failed" |
// "needs_auth" | "pending" | "needs_client_registration" } }

/** One installed plugin (Extensions → Plugins, `GET /api/plugin`). */
@Serializable
@Immutable
data class PluginEntry(
    val id: String,
    val sourceType: String? = null,
    val status: String? = null,
    /** Feature flags the plugin contributes (e.g. `server`), for a compact row. */
    val features: List<String> = emptyList(),
)

/** One installed skill (Extensions → Skills, `GET /api/skill`). */
@Serializable
@Immutable
data class SkillEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

/** One MCP resource (`GET /api/mcp/resource`). */
@Serializable
@Immutable
data class McpResourceEntry(
    val server: String,
    val name: String? = null,
    val uri: String? = null,
    val description: String? = null,
)

@Serializable
@Immutable
data class McpStatus(
    val status: String? = null,
)

@Immutable
data class McpEntry(
    val name: String,
    val status: String,
) {
    // Web dialog counts "N of M enabled": anything not explicitly disabled is
    // enabled (connected, failed, needs_auth, pending all count).
    val enabled: Boolean get() = status != "disabled"
    val needsAuth: Boolean
        get() = status == "needs_auth" || status == "needs_client_registration"
}

@Serializable
@Immutable
data class SummarizeRequest(
    /**
     * Client-generated `msg_…` id for the compaction message.
     *
     * The web sends `{"id":"msg_…"}` and the server echoes that id on the
     * compaction message, so the client can follow its own request in the
     * transcript. An empty body made the server mint the id instead, which is
     * why the running compaction could not be matched to the request.
     */
    val id: String? = null,
)

@Serializable
@Immutable
data class SessionTimeUpdate(
    val archived: Long? = null,
)

@Serializable
@Immutable
data class SessionUpdateRequest(
    val title: String? = null,
    val time: SessionTimeUpdate? = null,
)

@Serializable
@Immutable
data class RevertRequest(
    val messageID: String,
    val partID: String? = null,
)

@Serializable
@Immutable
data class ForkRequest(
    val messageID: String,
)
