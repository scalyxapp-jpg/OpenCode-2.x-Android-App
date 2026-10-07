package com.opencode.android.data

import com.opencode.android.domain.Agent
import com.opencode.android.domain.AuthSetRequest
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.ContextUsage
import com.opencode.android.domain.DataListResponse
import com.opencode.android.domain.FileContent
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.ForkRequest
import com.opencode.android.domain.HealthResponse
import com.opencode.android.domain.McpStatus
import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.PathInfo
import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.Project
import com.opencode.android.domain.ProjectAgent
import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.domain.PromptRequest
import com.opencode.android.domain.PromptResponse
import com.opencode.android.domain.Provider
import com.opencode.android.domain.ProviderAuthMethod
import com.opencode.android.domain.ProvidersResponse
import com.opencode.android.domain.QuestionReplyRequest
import com.opencode.android.domain.RevertRequest
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionCreateRequest
import com.opencode.android.domain.SessionGuard
import com.opencode.android.domain.SessionGuardHealth
import com.opencode.android.domain.SessionListResponse
import com.opencode.android.domain.SessionQuestionListResponse
import com.opencode.android.domain.SessionResponse
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SessionUpdateRequest
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.domain.TodoItem
import com.opencode.android.domain.UploadResponse
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.VcsInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * App-facing view of the OpenCode server API.
 *
 * This is a **plain** interface (no Retrofit annotations): the concrete
 * implementation is [OpenCodeApiAdapter], which speaks the OpenCode 2.x `/api`
 * surface through [OpenCodeV2Api] and maps the wire shapes onto the app's
 * domain models. Keeping the domain signatures stable means the UI,
 * ViewModels and repositories did not have to change when the server moved
 * from the V1 to the V2 API.
 *
 * `directory` parameters are translated to the `x-opencode-directory` header by
 * the adapter — OpenCode 2.x ignores the V1 `?directory=` query param.
 */
interface OpenCodeApi {
    // --- health / info -----------------------------------------------------

    suspend fun health(): HealthResponse

    suspend fun globalHealth(): HealthResponse

    // --- sessions ----------------------------------------------------------

    suspend fun getSessions(
        limit: Int = 100,
        order: String = "desc",
    ): SessionListResponse

    suspend fun getSessionStatuses(directory: String): Map<String, SessionStatus>

    suspend fun createSession(body: Map<String, String> = emptyMap()): SessionResponse

    suspend fun createSessionIn(body: SessionCreateRequest): SessionResponse

    suspend fun getProjectSessions(
        directory: String,
        roots: Boolean = true,
        limit: Int = 55,
    ): List<Session>

    suspend fun renameProject(
        projectId: String,
        body: Map<String, String>,
    ): ResponseBody

    suspend fun getSession(sessionId: String): SessionResponse

    suspend fun getSessionFull(sessionId: String): Session

    suspend fun getMessages(
        sessionId: String,
        limit: Int? = null,
    ): List<Message>

    suspend fun getMessagesRaw(
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
    ): Response<ResponseBody>

    suspend fun getApiMessages(
        sessionId: String,
        limit: Int? = null,
    ): MessageListResponse

    suspend fun sendPrompt(
        sessionId: String,
        request: PromptRequest,
    ): PromptResponse

    suspend fun sendPromptAsync(
        sessionId: String,
        request: PromptAsyncRequest,
        guardRevision: Long? = null,
    ): Response<ResponseBody>

    suspend fun interrupt(sessionId: String)

    suspend fun abort(sessionId: String): Response<ResponseBody>

    suspend fun setModel(
        sessionId: String,
        body: ModelRefRequest,
    ): Response<ResponseBody>

    suspend fun setAgent(
        sessionId: String,
        body: Map<String, String>,
    ): Response<ResponseBody>

    suspend fun renameSession(
        sessionId: String,
        body: Map<String, String>,
    ): Session

    suspend fun updateSession(
        sessionId: String,
        body: SessionUpdateRequest,
    ): Session

    suspend fun deleteSession(sessionId: String)

    suspend fun revertMessage(
        sessionId: String,
        body: RevertRequest,
    ): ResponseBody

    suspend fun unrevertSession(sessionId: String): ResponseBody

    suspend fun forkSession(
        sessionId: String,
        body: ForkRequest,
    ): JsonObject

    suspend fun shareSession(sessionId: String): Session

    /** Full session history for export (V2 `/api/experimental/session/{id}/export`). */
    suspend fun exportSession(sessionId: String): List<Message>

    suspend fun getSessionChildren(sessionId: String): List<Session>

    suspend fun getSessionDiff(
        sessionId: String,
        messageId: String? = null,
    ): List<JsonElement>

    suspend fun revertClear(sessionId: String)

    suspend fun revertCommit(sessionId: String)

    suspend fun revertStage(sessionId: String)

    suspend fun getContext(sessionId: String): ContextUsage

    // --- filesystem / location --------------------------------------------

    suspend fun getFiles(
        path: String = ".",
        directory: String,
    ): List<FileEntry>

    suspend fun findFiles(
        query: String,
        type: String = "directory",
        limit: Int = 50,
        directory: String? = null,
    ): List<String>

    suspend fun getPathInfo(): PathInfo

    suspend fun getFileContent(path: String): FileContent

    // --- catalog -----------------------------------------------------------

    suspend fun getAgents(): DataListResponse<Agent>

    suspend fun getProjectAgents(directory: String? = null): List<ProjectAgent>

    suspend fun getModels(): DataListResponse<Model>

    suspend fun getProviders(): DataListResponse<Provider>

    suspend fun getProviderList(): ProvidersResponse

    suspend fun getCommands(directory: String? = null): List<CommandEntry>

    suspend fun getSkills(): List<JsonElement>

    // --- project / vcs -----------------------------------------------------

    suspend fun getProjects(): List<Project>

    suspend fun getVcs(directory: String? = null): VcsInfo

    suspend fun getVcsDiff(
        mode: String = "git",
        directory: String? = null,
    ): List<VcsDiffFile>

    suspend fun getTodos(sessionId: String): List<TodoItem>

    // --- providers / auth --------------------------------------------------

    suspend fun disconnectProvider(providerId: String): Response<ResponseBody>

    suspend fun getProviderAuth(): Map<String, List<ProviderAuthMethod>>

    suspend fun setProviderAuth(
        providerId: String,
        body: AuthSetRequest,
    ): Response<ResponseBody>

    suspend fun globalDispose(): Response<ResponseBody>

    suspend fun providerOauthAuthorize(
        providerId: String,
        body: JsonElement,
    ): JsonElement

    suspend fun providerOauthCallback(
        providerId: String,
        body: JsonElement,
    ): ResponseBody

    // --- mcp ---------------------------------------------------------------

    suspend fun getMcpServers(directory: String? = null): Map<String, McpStatus>

    suspend fun connectMcp(name: String): ResponseBody

    suspend fun disconnectMcp(name: String): ResponseBody

    suspend fun authenticateMcp(name: String): ResponseBody

    // --- permissions / questions (v2 forms) --------------------------------

    suspend fun getPermissions(directory: String? = null): List<PermissionRequest>

    suspend fun replyPermission(
        requestId: String,
        body: PermissionReplyRequest,
    ): ResponseBody

    suspend fun replySessionPermission(
        sessionId: String,
        permissionId: String,
        body: PermissionReplyRequest,
    ): ResponseBody

    suspend fun getSessionQuestions(sessionId: String): SessionQuestionListResponse

    suspend fun getQuestionRequests(directory: String? = null): SessionQuestionListResponse

    suspend fun replyQuestion(
        requestId: String,
        body: QuestionReplyRequest,
    ): ResponseBody

    suspend fun rejectQuestion(requestId: String): ResponseBody

    suspend fun replySessionQuestion(
        sessionId: String,
        requestId: String,
        body: QuestionReplyRequest,
    ): ResponseBody

    suspend fun rejectSessionQuestion(
        sessionId: String,
        requestId: String,
    ): ResponseBody

    // --- misc --------------------------------------------------------------

    suspend fun initSession(
        sessionId: String,
        body: JsonElement,
    ): ResponseBody

    suspend fun summarizeSession(
        sessionId: String,
        directory: String?,
        body: SummarizeRequest,
    ): Response<ResponseBody>

    suspend fun postLog(body: JsonElement): ResponseBody

    // --- session guard (optional proxy) -----------------------------------

    suspend fun sessionGuardHealth(): Response<SessionGuardHealth>

    suspend fun sessionGuardMetrics(): Response<ResponseBody>

    suspend fun adoptServerSelection(
        sessionId: String,
        expectedRevision: Long? = null,
    ): Response<SessionGuard>

    suspend fun pushGuardSelection(
        sessionId: String,
        expectedRevision: Long? = null,
    ): Response<SessionGuard>

    suspend fun removeGuardSelection(
        sessionId: String,
        expectedRevision: Long? = null,
    ): Response<Unit>

    suspend fun uploadAttachment(
        name: String,
        contentType: String,
        body: RequestBody,
    ): Response<UploadResponse>
}
