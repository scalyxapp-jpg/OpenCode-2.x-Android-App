package com.opencode.android.data

import com.opencode.android.domain.Agent
import com.opencode.android.domain.ProjectAgent
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.ContextUsage
import com.opencode.android.domain.McpStatus
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.QuestionReplyRequest
import com.opencode.android.domain.DataListResponse
import com.opencode.android.domain.FileContent
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.ForkRequest
import com.opencode.android.domain.HistoryResponse
import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.Model
import com.opencode.android.domain.PathInfo
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.domain.PromptRequest
import com.opencode.android.domain.PromptResponse
import com.opencode.android.domain.Project
import com.opencode.android.domain.AuthSetRequest
import com.opencode.android.domain.Provider
import com.opencode.android.domain.ProviderAuthMethod
import com.opencode.android.domain.HealthResponse
import com.opencode.android.domain.ProvidersResponse
import com.opencode.android.domain.PtyShell
import com.opencode.android.domain.TodoItem
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.VcsInfo
import com.opencode.android.domain.RevertRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionGuard
import com.opencode.android.domain.SessionGuardHealth
import com.opencode.android.domain.UploadResponse
import com.opencode.android.domain.SessionCreateRequest
import com.opencode.android.domain.SessionListResponse
import com.opencode.android.domain.SessionQuestionListResponse
import com.opencode.android.domain.SessionResponse
import com.opencode.android.domain.SessionUpdateRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenCodeApi {

    @GET("api/session")
    suspend fun getSessions(
        @Query("limit") limit: Int = 100,
        @Query("order") order: String = "desc",
    ): SessionListResponse

    @POST("api/session")
    suspend fun createSession(@Body body: Map<String, String> = emptyMap()): SessionResponse

    // Session creation with a target directory (web "New session" per project).
    @POST("api/session")
    suspend fun createSessionIn(@Body body: SessionCreateRequest): SessionResponse

    // Web home list: sessions of one project directory (bare array with
    // cost/tokens/summary), filtered to root sessions.
    @GET("session")
    suspend fun getProjectSessions(
        @Query("directory") directory: String,
        @Query("roots") roots: Boolean = true,
        @Query("limit") limit: Int = 55,
    ): List<Session>

    // Web project rename (per-project "More options").
    @PATCH("project/{projectID}")
    suspend fun renameProject(
        @Path("projectID") projectId: String,
        @Body body: Map<String, String>,
    ): ResponseBody

    @GET("api/session/{sessionID}")
    suspend fun getSession(@Path("sessionID") sessionId: String): SessionResponse

    // Web endpoint: returns the full session incl. `directory` (the /api one
    // omits it, which broke directory-scoped calls like /vcs/diff).
    @GET("session/{sessionID}")
    suspend fun getSessionFull(@Path("sessionID") sessionId: String): Session

    @GET("session/{sessionID}/message")
    suspend fun getMessages(
        @Path("sessionID") sessionId: String,
        @Query("limit") limit: Int? = null,
    ): List<Message>

    // Same endpoint, but handed back as a raw body so it can be decoded
    // straight from the response stream. The typed variant above goes through
    // the stock converter, which does ResponseBody.string() first — a heavy
    // session's message list is ~14 MB, and that transient String was what
    // triggered OutOfMemoryError (okio.Buffer.readString) on a phone.
    @GET("session/{sessionID}/message")
    suspend fun getMessagesRaw(
        @Path("sessionID") sessionId: String,
        @Query("limit") limit: Int? = null,
    ): ResponseBody

    // Single message fetch (web loads /session/{id}/message/{messageID} on demand)
    @GET("session/{sessionID}/message/{messageID}")
    suspend fun getMessage(
        @Path("sessionID") sessionId: String,
        @Path("messageID") messageId: String,
    ): Message

    // Legacy message shape (type/agent/model/content[], wrapped in {data}).
    @GET("api/session/{sessionID}/message")
    suspend fun getApiMessages(
        @Path("sessionID") sessionId: String,
        @Query("limit") limit: Int? = null,
    ): MessageListResponse

    @POST("api/session/{sessionID}/prompt")
    suspend fun sendPrompt(
        @Path("sessionID") sessionId: String,
        @Body request: PromptRequest,
    ): PromptResponse

    // Primary prompt flow (mirrors web): 204 + progress via SSE.
    @POST("session/{sessionID}/prompt_async")
    suspend fun sendPromptAsync(
        @Path("sessionID") sessionId: String,
        @Body request: PromptAsyncRequest,
        @Header("X-Session-Guard-Revision") guardRevision: Long? = null,
    ): retrofit2.Response<ResponseBody>

    @POST("api/session/{sessionID}/interrupt")
    suspend fun interrupt(@Path("sessionID") sessionId: String)

    // Stop action exactly as the web performs it:
    // POST /session/{id}/abort (no body) -> 200 "true".
    @POST("session/{sessionID}/abort")
    suspend fun abort(@Path("sessionID") sessionId: String): retrofit2.Response<ResponseBody>

    @POST("api/session/{sessionID}/model")
    suspend fun setModel(
        @Path("sessionID") sessionId: String,
        @Body body: ModelRefRequest,
    ): retrofit2.Response<ResponseBody>

    @POST("api/session/{sessionID}/agent")
    suspend fun setAgent(
        @Path("sessionID") sessionId: String,
        @Body body: Map<String, String>,
    ): retrofit2.Response<ResponseBody>

    @PATCH("session/{sessionID}")
    suspend fun renameSession(
        @Path("sessionID") sessionId: String,
        @Body body: Map<String, String>,
    ): Session

    // Partial session update (web uses this for Archive via time.archived)
    @PATCH("session/{sessionID}")
    suspend fun updateSession(
        @Path("sessionID") sessionId: String,
        @Body body: SessionUpdateRequest,
    ): Session

    @DELETE("session/{sessionID}")
    suspend fun deleteSession(@Path("sessionID") sessionId: String)

    // Message actions (mirror web client: revert / unrevert / fork)
    @POST("session/{sessionID}/revert")
    suspend fun revertMessage(
        @Path("sessionID") sessionId: String,
        @Body body: RevertRequest,
    ): ResponseBody

    @POST("session/{sessionID}/unrevert")
    suspend fun unrevertSession(@Path("sessionID") sessionId: String): ResponseBody

    @POST("session/{sessionID}/fork")
    suspend fun forkSession(
        @Path("sessionID") sessionId: String,
        @Body body: ForkRequest,
    ): JsonObject

    @POST("session/{sessionID}/share")
    suspend fun shareSession(@Path("sessionID") sessionId: String): Session

    @GET("api/session/{sessionID}/history")
    suspend fun getHistory(@Path("sessionID") sessionId: String): HistoryResponse

    // Web "Open project" folder browser (verified via Playwright):
    //   GET /file?path=<rel>&directory=<abs-base> → [{name, path, absolute, type, ignored}]
    // `path` is RELATIVE to `directory` ("." lists the directory itself);
    // passing an absolute path in `path` returns a server error.
    @GET("file")
    suspend fun getFiles(
        @Query("path") path: String = ".",
        @Query("directory") directory: String,
    ): List<FileEntry>

    // Web "Open project" search ("Search folders"):
    //   GET /find/file?query=&type=directory&limit=50&directory=<relative>
    //
    // Two gotchas verified against the live server:
    //  - `directory` must be RELATIVE to the serve root; an absolute path makes
    //    the server return [] (which looked like "no results").
    //  - the response is a JSON array of STRING paths, not FileEntry objects.
    //  - `dirs=true` does not actually filter; `type=directory` does.
    @GET("find/file")
    suspend fun findFiles(
        @Query("query") query: String,
        @Query("type") type: String = "directory",
        @Query("limit") limit: Int = 50,
        // Null/blank omits the parameter (searches from the serve root).
        @Query("directory") directory: String? = null,
    ): List<String>

    // GET /path → serve host filesystem locations (home seeds the browser).
    @GET("path")
    suspend fun getPathInfo(): PathInfo

    @GET("file/content")
    suspend fun getFileContent(@Query("path") path: String): FileContent

    @POST("api/session/{sessionID}/revert/clear")
    suspend fun revertClear(@Path("sessionID") sessionId: String)

    @POST("api/session/{sessionID}/revert/commit")
    suspend fun revertCommit(@Path("sessionID") sessionId: String)

    @POST("api/session/{sessionID}/revert/stage")
    suspend fun revertStage(@Path("sessionID") sessionId: String)

    @GET("api/agent")
    suspend fun getAgents(): DataListResponse<Agent>

    /**
     * Project-scoped agent list (the endpoint the web UI uses).
     *
     * `GET /api/agent` ignores `?directory=` (verified: its `location` stays the
     * global config dir), so an agent defined in a project's own opencode config
     * never appeared in the app. `GET /agent?directory=` returns it, in the web
     * shape where the identifier field is `name`, not `id`.
     */
    @GET("agent")
    suspend fun getProjectAgents(
        @Query("directory") directory: String? = null,
    ): List<ProjectAgent>

    @GET("api/model")
    suspend fun getModels(): DataListResponse<Model>

    @GET("api/provider")
    suspend fun getProviders(): DataListResponse<Provider>

    // Authoritative model catalog (same endpoint the web UI uses).
    @GET("provider")
    suspend fun getProviderList(): ProvidersResponse

    // Settings → "Terminal shell" dropdown (web calls this when Settings opens).
    @GET("pty/shells")
    suspend fun getShells(): List<PtyShell>

    @GET("api/health")
    suspend fun health(): HealthResponse

    // GET /global/health → { healthy, version } (Settings header shows the version).
    @GET("global/health")
    suspend fun globalHealth(): HealthResponse

    @GET("__session_guard__/health")
    suspend fun sessionGuardHealth(): retrofit2.Response<SessionGuardHealth>

    // Token-protected endpoint used to verify guard auth end to end.
    @GET("__session_guard__/metrics")
    suspend fun sessionGuardMetrics(): retrofit2.Response<okhttp3.ResponseBody>

    // Adopts the upstream session model/agent as the guard selection.
    @POST("__session_guard__/sessions/{sessionID}/adopt")
    suspend fun adoptServerSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): retrofit2.Response<SessionGuard>

    // Pushes the guard selection to the upstream session (Guard -> Server).
    @POST("__session_guard__/sessions/{sessionID}/push")
    suspend fun pushGuardSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): retrofit2.Response<SessionGuard>

    // Removes the guard selection for a session.
    @DELETE("__session_guard__/sessions/{sessionID}")
    suspend fun removeGuardSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): retrofit2.Response<Unit>

    // Uploads attachment bytes to the target host via the session-guard proxy.
    // Body is raw bytes; the proxy stores it under SESSION_GUARD_UPLOAD_DIR.
    @POST("__session_guard__/upload")
    suspend fun uploadAttachment(
        @Query("name") name: String,
        @Header("Content-Type") contentType: String,
        @Body body: okhttp3.RequestBody,
    ): retrofit2.Response<UploadResponse>

    // Review/Changes tab (web: GET /vcs + GET /vcs/diff?mode=git).
    @GET("vcs")
    suspend fun getVcs(@Query("directory") directory: String? = null): VcsInfo

    @GET("vcs/diff")
    suspend fun getVcsDiff(
        @Query("mode") mode: String = "git",
        @Query("directory") directory: String? = null,
    ): List<VcsDiffFile>

    // Web shows a todo panel above the composer ("N of M todos completed").
    @GET("session/{sessionID}/todo")
    suspend fun getTodos(@Path("sessionID") sessionId: String): List<TodoItem>

    // Settings → Providers → Disconnect (web: DELETE /auth/{id} then POST /global/dispose).
    @DELETE("auth/{providerID}")
    suspend fun disconnectProvider(@Path("providerID") providerId: String): retrofit2.Response<ResponseBody>

    // Settings → Providers → Connect. Web first asks which auth methods exist
    // (GET /provider/auth), then stores the credential (PUT /auth/{id}).
    @GET("provider/auth")
    suspend fun getProviderAuth(): Map<String, List<ProviderAuthMethod>>

    @PUT("auth/{providerID}")
    suspend fun setProviderAuth(
        @Path("providerID") providerId: String,
        @Body body: AuthSetRequest,
    ): retrofit2.Response<ResponseBody>

    @POST("global/dispose")
    suspend fun globalDispose(): retrofit2.Response<ResponseBody>

    @GET("project")
    suspend fun getProjects(): List<Project>

    @GET("project/current")
    suspend fun getCurrentProject(): Project

    @GET("api/session/{sessionID}/context")
    suspend fun getContext(@Path("sessionID") sessionId: String): ContextUsage

    // "/compact": the web client summarizes the session to shrink the context.
    // The newer /api/session/{id}/compact route answers 503 on this server, so
    // the V1 /session/{id}/summarize route is the one that actually works.
    @POST("session/{sessionID}/summarize")
    suspend fun summarizeSession(
        @Path("sessionID") sessionId: String,
        // The web sends the project directory as a URL-encoded header on this
        // call (captured: x-opencode-directory: %2Fhome%2Fuser%2FDocuments),
        // not as a query parameter like the other endpoints.
        @Header("x-opencode-directory") directory: String?,
        @Body body: SummarizeRequest,
        // Response (not ResponseBody) so a 4xx/5xx can be read instead of
        // Retrofit throwing a bare "HTTP 500 Internal Server Error".
    ): retrofit2.Response<ResponseBody>

    // "/mcp" (web: "Toggle MCPs"): the server reports every configured MCP
    // server and its status. Connect/disconnect toggles it at runtime.
    @GET("mcp")
    suspend fun getMcpServers(
        @Query("directory") directory: String? = null,
    ): Map<String, McpStatus>

    @POST("mcp/{name}/connect")
    suspend fun connectMcp(@Path("name") name: String): ResponseBody

    @POST("mcp/{name}/disconnect")
    suspend fun disconnectMcp(@Path("name") name: String): ResponseBody

    @POST("mcp/{name}/auth")
    suspend fun authenticateMcp(@Path("name") name: String): ResponseBody

    // Web composer data: "/" commands and pending agent questions.
    // Both endpoints return bare arrays (mirrors web client usage).
    @GET("command")
    suspend fun getCommands(@Query("directory") directory: String? = null): List<CommandEntry>

    @GET("question")
    suspend fun getPendingQuestions(
        @Query("directory") directory: String? = null,
        @Query("workspace") workspace: String? = null,
    ): List<JsonObject>

    @POST("question/{requestID}/reply")
    suspend fun replyQuestion(
        @Path("requestID") requestId: String,
        @Body body: QuestionReplyRequest,
    ): ResponseBody

    @POST("question/{requestID}/reject")
    suspend fun rejectQuestion(@Path("requestID") requestId: String): ResponseBody

    // Tool-permission prompts. Reply values are protocol-fixed:
    // "once" | "always" | "reject" (verified in the web bundle).
    @GET("permission")
    suspend fun getPermissions(
        @Query("directory") directory: String? = null,
    ): List<PermissionRequest>

    @POST("permission/{requestID}/reply")
    suspend fun replyPermission(
        @Path("requestID") requestId: String,
        @Body body: PermissionReplyRequest,
    ): ResponseBody

    // Session-scoped agent questions (primary question flow).
    @GET("api/session/{sessionID}/question")
    suspend fun getSessionQuestions(@Path("sessionID") sessionId: String): SessionQuestionListResponse

    @POST("api/session/{sessionID}/question/{requestID}/reply")
    suspend fun replySessionQuestion(
        @Path("sessionID") sessionId: String,
        @Path("requestID") requestId: String,
        @Body body: QuestionReplyRequest,
    ): ResponseBody

    @POST("api/session/{sessionID}/question/{requestID}/reject")
    suspend fun rejectSessionQuestion(
        @Path("sessionID") sessionId: String,
        @Path("requestID") requestId: String,
    ): ResponseBody

    // ------------------------------------------------------------------
    // Web-parity coverage for endpoints the app did not call yet.
    // Shapes are the ones verified against the running server; where the
    // response is opaque it is a JsonElement so the caller decodes what it
    // needs without a rigid DTO.
    // ------------------------------------------------------------------

    /** Child sessions (subagents spawned by this session). */
    @GET("session/{sessionID}/children")
    suspend fun getSessionChildren(@Path("sessionID") sessionId: String): List<Session>

    /** Per-session file diff (the Changes tab uses /vcs/diff; this is the
     *  session-scoped view the web session header exposes). */
    @GET("session/{sessionID}/diff")
    suspend fun getSessionDiff(
        @Path("sessionID") sessionId: String,
        @Query("messageID") messageId: String? = null,
    ): List<JsonElement>

    /** Create/refresh the project's AGENTS.md (web "Initialize"). */
    @POST("session/{sessionID}/init")
    suspend fun initSession(
        @Path("sessionID") sessionId: String,
        @Body body: JsonElement,
    ): ResponseBody

    /** Installed skills (Settings → Skills). */
    @GET("skill")
    suspend fun getSkills(): List<JsonElement>

    /** Provider configuration including credentials — do not render raw keys. */
    @GET("config/providers")
    suspend fun getConfigProviders(): JsonElement

    /** OAuth sign-in: step 1 returns the authorize URL + method. */
    @POST("provider/{providerID}/oauth/authorize")
    suspend fun providerOauthAuthorize(
        @Path("providerID") providerId: String,
        @Body body: JsonElement,
    ): JsonElement

    /** OAuth sign-in: step 2 exchanges the pasted code. */
    @POST("provider/{providerID}/oauth/callback")
    suspend fun providerOauthCallback(
        @Path("providerID") providerId: String,
        @Body body: JsonElement,
    ): ResponseBody

    /** Experimental: session list with extra metadata. */
    @GET("experimental/session")
    suspend fun getExperimentalSessions(): List<Session>

    /** Experimental: git worktrees of the project. */
    @GET("experimental/worktree")
    suspend fun getExperimentalWorktrees(
        @Query("directory") directory: String? = null,
    ): List<JsonElement>

    /** Experimental: MCP resources exposed by connected servers. */
    @GET("experimental/resource")
    suspend fun getExperimentalResources(
        @Query("directory") directory: String? = null,
    ): JsonElement

    /** Experimental: invoke an MCP tool directly. */
    @POST("experimental/tool")
    suspend fun callExperimentalTool(
        @Body body: JsonElement,
    ): ResponseBody

    /** Create a PTY. Attaching to a live PTY is a websocket at /pty/{id}. */
    @POST("pty")
    suspend fun createPty(
        @Body body: JsonElement,
    ): JsonElement

    /** PTY metadata (the data stream itself is a websocket, not HTTP). */
    @GET("pty/{ptyID}")
    suspend fun getPty(@Path("ptyID") ptyId: String): JsonElement

    @DELETE("pty/{ptyID}")
    suspend fun deletePty(@Path("ptyID") ptyId: String): ResponseBody

    /** Client log line forwarded to the server log. */
    @POST("log")
    suspend fun postLog(@Body body: JsonElement): ResponseBody

    /** Ask the server to upgrade itself. */
    @POST("global/upgrade")
    suspend fun globalUpgrade(@Body body: JsonElement): ResponseBody

    /** Session-scoped permission reply (the app also has the global form). */
    @POST("session/{sessionID}/permissions/{permissionID}")
    suspend fun replySessionPermission(
        @Path("sessionID") sessionId: String,
        @Path("permissionID") permissionId: String,
        @Body body: PermissionReplyRequest,
    ): ResponseBody

    // TUI control channel. These drive a running TUI, not the Android UI; kept
    // for API parity so a caller can reach them if a TUI session is attached.
    @POST("tui/append-prompt")
    suspend fun tuiAppendPrompt(@Body body: JsonElement): ResponseBody

    @POST("tui/submit-prompt")
    suspend fun tuiSubmitPrompt(): ResponseBody

    @POST("tui/clear-prompt")
    suspend fun tuiClearPrompt(): ResponseBody

    @POST("tui/open-help")
    suspend fun tuiOpenHelp(): ResponseBody

    @POST("tui/show-toast")
    suspend fun tuiShowToast(@Body body: JsonElement): ResponseBody
}
