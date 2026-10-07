package com.opencode.android.data

import com.opencode.android.domain.Agent
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.CompactBody
import com.opencode.android.domain.ContextItem
import com.opencode.android.domain.CreateSessionBody
import com.opencode.android.domain.FileContent
import com.opencode.android.domain.ForkBody
import com.opencode.android.domain.FormReplyBody
import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.PermissionDecision
import com.opencode.android.domain.Project
import com.opencode.android.domain.PromptBody
import com.opencode.android.domain.PtyShell
import com.opencode.android.domain.RevertStageBody
import com.opencode.android.domain.ServerInfo
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionListResponse
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SessionUpdateRequest
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.WireCommand
import com.opencode.android.domain.WireCredential
import com.opencode.android.domain.WireCredentialCreate
import com.opencode.android.domain.WireData
import com.opencode.android.domain.WireEnvelope
import com.opencode.android.domain.WireForm
import com.opencode.android.domain.WireFsEntry
import com.opencode.android.domain.WireIntegration
import com.opencode.android.domain.WireLocation
import com.opencode.android.domain.WireMcp
import com.opencode.android.domain.WireModel
import com.opencode.android.domain.WireOAuthAttempt
import com.opencode.android.domain.WirePermission
import com.opencode.android.domain.WireProvider
import com.opencode.android.domain.WireSessionExport
import com.opencode.android.domain.WireSkill
import com.opencode.android.domain.WireVcs
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit definition of the OpenCode 2.x `/api` surface.
 *
 * Every path is namespaced with `api/` and directory scoping is the
 * `x-opencode-directory` **header** (the V1 `?directory=` query param is
 * ignored by the server). See `.opencode/opencode-v2-api.md`.
 *
 * Only the endpoints the Android client actually uses are declared here; the
 * app-facing, domain-typed view is [OpenCodeApi], implemented by
 * [OpenCodeApiAdapter].
 */
interface OpenCodeV2Api {
    // --- server ------------------------------------------------------------

    @GET("api/info")
    suspend fun info(): ServerInfo

    // --- sessions ----------------------------------------------------------

    @GET("api/session")
    suspend fun listSessions(
        @Query("limit") limit: Int? = null,
        @Query("order") order: String? = null,
        @Query("search") search: String? = null,
        @Query("parentID") parentId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): SessionListResponse

    @POST("api/session")
    suspend fun createSession(
        @Body body: CreateSessionBody,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireData<Session>

    @GET("api/session/{sessionID}")
    suspend fun getSession(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireData<Session>

    @PATCH("api/session/{sessionID}")
    suspend fun updateSession(
        @Path("sessionID") sessionId: String,
        @Body body: SessionUpdateRequest,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<Unit>

    @DELETE("api/session/{sessionID}")
    suspend fun deleteSession(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<Unit>

    @GET("api/session/active")
    suspend fun activeSessions(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<Map<String, SessionStatus>>

    @POST("api/session/{sessionID}/agent")
    suspend fun setAgent(
        @Path("sessionID") sessionId: String,
        @Body body: Map<String, String>,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/model")
    suspend fun setModel(
        @Path("sessionID") sessionId: String,
        @Body body: ModelRefRequest,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    /** Send a prompt. Progress arrives via SSE; 200 + `{data:{...}}` on accept. */
    @POST("api/session/{sessionID}/prompt")
    suspend fun prompt(
        @Path("sessionID") sessionId: String,
        @Body body: PromptBody,
        @Header("X-Session-Guard-Revision") guardRevision: Long? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/interrupt")
    suspend fun interrupt(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/command")
    suspend fun runCommand(
        @Path("sessionID") sessionId: String,
        @Body body: JsonElement,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/compact")
    suspend fun compact(
        @Path("sessionID") sessionId: String,
        @Body body: CompactBody,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/fork")
    suspend fun fork(
        @Path("sessionID") sessionId: String,
        @Body body: ForkBody,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireData<Session>

    @POST("api/session/{sessionID}/revert/stage")
    suspend fun revertStage(
        @Path("sessionID") sessionId: String,
        @Body body: RevertStageBody,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @DELETE("api/session/{sessionID}/revert")
    suspend fun revertClear(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/session/{sessionID}/revert/commit")
    suspend fun revertCommit(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @GET("api/session/{sessionID}/context")
    suspend fun context(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<ContextItem>>

    @GET("api/session/{sessionID}/diff")
    suspend fun sessionDiff(
        @Path("sessionID") sessionId: String,
        @Query("messageID") messageId: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<VcsDiffFile>>

    // --- messages ----------------------------------------------------------

    /** Typed decode (small sessions / single message). */
    @GET("api/session/{sessionID}/message")
    suspend fun messages(
        @Path("sessionID") sessionId: String,
        @Query("limit") limit: Int? = null,
        @Query("order") order: String? = null,
        @Query("cursor") cursor: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): MessageListResponse

    /**
     * Raw body so a heavy history is decoded straight from the stream (the V1
     * code path this preserves): a full `{data,cursor}` body decoded through the
     * stock converter materialised a transient multi-MB String that OOM'd.
     */
    @GET("api/session/{sessionID}/message")
    suspend fun messagesRaw(
        @Path("sessionID") sessionId: String,
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @GET("api/session/{sessionID}/message/{messageID}")
    suspend fun message(
        @Path("sessionID") sessionId: String,
        @Path("messageID") messageId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireData<Message>

    /** Full session transfer (session + messages) — the V2 export route. */
    @GET("api/experimental/session/{sessionID}/export")
    suspend fun exportSession(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireData<WireSessionExport>

    // --- permissions / forms ----------------------------------------------

    @GET("api/permission/request")
    suspend fun permissionRequests(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WirePermission>>

    @GET("api/session/{sessionID}/permission")
    suspend fun sessionPermissions(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WirePermission>>

    @POST("api/session/{sessionID}/permission/{requestID}/reply")
    suspend fun replyPermission(
        @Path("sessionID") sessionId: String,
        @Path("requestID") requestId: String,
        @Body body: PermissionDecision,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @GET("api/form")
    suspend fun forms(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireForm>>

    @GET("api/session/{sessionID}/form")
    suspend fun sessionForms(
        @Path("sessionID") sessionId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireForm>>

    @POST("api/session/{sessionID}/form/{formID}/reply")
    suspend fun replyForm(
        @Path("sessionID") sessionId: String,
        @Path("formID") formId: String,
        @Body body: FormReplyBody,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @DELETE("api/session/{sessionID}/form/{formID}")
    suspend fun cancelForm(
        @Path("sessionID") sessionId: String,
        @Path("formID") formId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    // --- catalog -----------------------------------------------------------

    @GET("api/agent")
    suspend fun agents(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<Agent>>

    @GET("api/model")
    suspend fun models(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireModel>>

    @GET("api/provider")
    suspend fun providers(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireProvider>>

    @GET("api/command")
    suspend fun commands(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireCommand>>

    @GET("api/skill")
    suspend fun skills(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireSkill>>

    @GET("api/config/shell")
    suspend fun shells(): List<PtyShell>

    // --- credentials / integrations ---------------------------------------

    @GET("api/credential")
    suspend fun credentials(): WireEnvelope<List<WireCredential>>

    @POST("api/credential")
    suspend fun createCredential(
        @Body body: WireCredentialCreate,
    ): WireData<WireCredential>

    @DELETE("api/credential/{credentialID}")
    suspend fun deleteCredential(
        @Path("credentialID") credentialId: String,
    ): Response<ResponseBody>

    @GET("api/integration")
    suspend fun integrations(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireIntegration>>

    @GET("api/integration/{integrationID}")
    suspend fun integration(
        @Path("integrationID") integrationId: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<WireIntegration>

    @POST("api/integration/{integrationID}/connect/key")
    suspend fun connectIntegrationKey(
        @Path("integrationID") integrationId: String,
        @Body body: JsonElement,
    ): Response<ResponseBody>

    @POST("api/integration/{integrationID}/connect/oauth")
    suspend fun connectIntegrationOauth(
        @Path("integrationID") integrationId: String,
        @Body body: JsonElement,
    ): WireEnvelope<WireOAuthAttempt>

    @POST("api/integration/{integrationID}/connect/oauth/{attemptID}/complete")
    suspend fun completeIntegrationOauth(
        @Path("integrationID") integrationId: String,
        @Path("attemptID") attemptId: String,
        @Body body: JsonElement,
    ): Response<ResponseBody>

    @GET("api/mcp")
    suspend fun mcp(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireMcp>>

    @POST("api/experimental/mcp/{name}/connect")
    suspend fun mcpConnect(
        @Path("name") name: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @POST("api/experimental/mcp/{name}/disconnect")
    suspend fun mcpDisconnect(
        @Path("name") name: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    // --- vcs / files -------------------------------------------------------

    @GET("api/vcs")
    suspend fun vcs(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<WireVcs>

    @GET("api/vcs/diff")
    suspend fun vcsDiff(
        @Query("mode") mode: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<VcsDiffFile>>

    @GET("api/vcs/status")
    suspend fun vcsStatus(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<VcsDiffFile>>

    @GET("api/fs/list")
    suspend fun fsList(
        @Query("path") path: String? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireFsEntry>>

    @GET("api/fs/find")
    suspend fun fsFind(
        @Query("query") query: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int? = null,
        @Header("x-opencode-directory") directory: String? = null,
    ): WireEnvelope<List<WireFsEntry>>

    @GET("api/fs/read/{path}")
    suspend fun fsRead(
        @Path("path") path: String,
        @Header("x-opencode-directory") directory: String? = null,
    ): Response<ResponseBody>

    @GET("api/location")
    suspend fun location(
        @Header("x-opencode-directory") directory: String? = null,
    ): WireLocation

    @POST("api/location/reload")
    suspend fun reloadLocation(): Response<ResponseBody>

    @GET("api/project")
    suspend fun projects(): List<Project>

    @PATCH("api/project/{projectID}")
    suspend fun renameProject(
        @Path("projectID") projectId: String,
        @Body body: Map<String, String>,
    ): Response<ResponseBody>

    // --- session guard (optional proxy) -----------------------------------

    @GET("__session_guard__/health")
    suspend fun sessionGuardHealth(): Response<com.opencode.android.domain.SessionGuardHealth>

    @GET("__session_guard__/metrics")
    suspend fun sessionGuardMetrics(): Response<ResponseBody>

    @POST("__session_guard__/sessions/{sessionID}/adopt")
    suspend fun adoptServerSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): Response<com.opencode.android.domain.SessionGuard>

    @POST("__session_guard__/sessions/{sessionID}/push")
    suspend fun pushGuardSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): Response<com.opencode.android.domain.SessionGuard>

    @DELETE("__session_guard__/sessions/{sessionID}")
    suspend fun removeGuardSelection(
        @Path("sessionID") sessionId: String,
        @Header("X-Session-Guard-Expected-Revision") expectedRevision: Long? = null,
    ): Response<Unit>

    @POST("__session_guard__/upload")
    suspend fun uploadAttachment(
        @Query("name") name: String,
        @Header("Content-Type") contentType: String,
        @Body body: RequestBody,
    ): Response<com.opencode.android.domain.UploadResponse>

    /**
     * Client diagnostic log line forwarded through the optional session-guard
     * proxy (OpenCode 2.x has no `/log` server endpoint). Raw text body.
     */
    @POST("__session_guard__/client-log")
    suspend fun clientLog(
        @Query("name") name: String,
        @Query("append") append: String? = null,
        @Body body: RequestBody,
    ): Response<ResponseBody>
}
