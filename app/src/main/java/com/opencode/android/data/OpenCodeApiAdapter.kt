package com.opencode.android.data

import com.opencode.android.domain.Agent
import com.opencode.android.domain.AuthSetRequest
import com.opencode.android.domain.CommandEntry
import com.opencode.android.domain.CompactBody
import com.opencode.android.domain.ContextItem
import com.opencode.android.domain.ContextUsage
import com.opencode.android.domain.CreateSessionBody
import com.opencode.android.domain.DataListResponse
import com.opencode.android.domain.FileContent
import com.opencode.android.domain.FileEntry
import com.opencode.android.domain.ForkBody
import com.opencode.android.domain.ForkRequest
import com.opencode.android.domain.FormReplyBody
import com.opencode.android.domain.HealthResponse
import com.opencode.android.domain.McpStatus
import com.opencode.android.domain.Message
import com.opencode.android.domain.MessageListResponse
import com.opencode.android.domain.Model
import com.opencode.android.domain.ModelRef
import com.opencode.android.domain.ModelRefRequest
import com.opencode.android.domain.PathInfo
import com.opencode.android.domain.PermissionDecision
import com.opencode.android.domain.PermissionReplyRequest
import com.opencode.android.domain.PermissionRequest
import com.opencode.android.domain.Project
import com.opencode.android.domain.ProjectAgent
import com.opencode.android.domain.PromptAgent
import com.opencode.android.domain.PromptAsyncRequest
import com.opencode.android.domain.PromptBody
import com.opencode.android.domain.PromptFile
import com.opencode.android.domain.PromptRequest
import com.opencode.android.domain.PromptResponse
import com.opencode.android.domain.PromptResult
import com.opencode.android.domain.Provider
import com.opencode.android.domain.ProviderAuthMethod
import com.opencode.android.domain.ProviderAuthPrompt
import com.opencode.android.domain.ProviderEntry
import com.opencode.android.domain.ProvidersResponse
import com.opencode.android.domain.QuestionItem
import com.opencode.android.domain.QuestionOption
import com.opencode.android.domain.QuestionReplyRequest
import com.opencode.android.domain.QuestionToolRef
import com.opencode.android.domain.RevertRequest
import com.opencode.android.domain.RevertStageBody
import com.opencode.android.domain.Session
import com.opencode.android.domain.SessionCreateRequest
import com.opencode.android.domain.SessionGuard
import com.opencode.android.domain.SessionGuardHealth
import com.opencode.android.domain.SessionListResponse
import com.opencode.android.domain.SessionQuestion
import com.opencode.android.domain.SessionQuestionListResponse
import com.opencode.android.domain.SessionResponse
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SessionUpdateRequest
import com.opencode.android.domain.SummarizeRequest
import com.opencode.android.domain.TodoItem
import com.opencode.android.domain.UploadResponse
import com.opencode.android.domain.VcsDiffFile
import com.opencode.android.domain.VcsInfo
import com.opencode.android.domain.WireCredentialCreate
import com.opencode.android.domain.WireForm
import com.opencode.android.domain.WireFormField
import com.opencode.android.domain.WirePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

/**
 * Adapts the OpenCode 2.x `/api` surface ([OpenCodeV2Api]) to the app-facing
 * [OpenCodeApi]. All V1→V2 shape differences live here so the UI and
 * repositories keep their stable domain model. See `.opencode/opencode-v2-api.md`.
 */
class OpenCodeApiAdapter(
    private val v2: OpenCodeV2Api,
) : OpenCodeApi {
    private val json = Json { ignoreUnknownKeys = true }

    // Populated by the question/form list fetches so the global reply/reject
    // routes (request-id only) can find the owning session, which the V2 form
    // reply route requires in its path.
    private val formSessionIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val formFields = java.util.concurrent.ConcurrentHashMap<String, List<WireFormField>>()

    // Integration (provider auth) methods and OAuth attempts, cached per provider.
    private val integrationMethods =
        java.util.concurrent.ConcurrentHashMap<String, List<com.opencode.android.domain.WireIntegrationMethod>>()
    private val oauthAttempts = java.util.concurrent.ConcurrentHashMap<String, String>()

    // --- health / info -----------------------------------------------------

    override suspend fun health(): HealthResponse = info()

    override suspend fun globalHealth(): HealthResponse = info()

    private suspend fun info(): HealthResponse =
        try {
            val i = v2.info()
            HealthResponse(healthy = true, version = i.version)
        } catch (e: Exception) {
            HealthResponse(healthy = false, version = null)
        }

    // --- sessions ----------------------------------------------------------

    override suspend fun getSessions(
        limit: Int,
        order: String,
    ): SessionListResponse {
        val page = v2.listSessions(limit = limit, order = order)
        return page.copy(data = page.data.map { it.withDirectory() })
    }

    override suspend fun getSessionStatuses(directory: String): Map<String, SessionStatus> {
        val raw = v2.activeSessions(directory = directory).data ?: return emptyMap()
        // V2 reports "running"; the app (and its badges) key off "busy".
        return raw.mapValues { (_, s) ->
            if (s.type == "running") s.copy(type = "busy") else s
        }
    }

    override suspend fun createSession(body: Map<String, String>): SessionResponse =
        SessionResponse(
            data = (v2.createSession(CreateSessionBody()).data ?: error("empty session response")).withDirectory(),
        )

    override suspend fun createSessionIn(body: SessionCreateRequest): SessionResponse =
        SessionResponse(
            data =
                v2
                    .createSession(
                        CreateSessionBody(
                            location = body.location,
                            agent = body.agent,
                            model = body.model,
                        ),
                    ).data ?: error("empty session response"),
        ).let { it.copy(data = it.data.withDirectory()) }

    override suspend fun getProjectSessions(
        directory: String,
        roots: Boolean,
        limit: Int,
    ): List<Session> {
        // Web parity: the home list is fetched GLOBALLY (`parentID=null`, a high
        // limit) and grouped by each session's `location.directory` client-side.
        // Scoping the request by directory (V1 behaviour) put sessions under the
        // wrong project. Fetch once with the web's limit and filter here.
        val all =
            v2
                .listSessions(
                    limit = HOME_SESSION_LIMIT,
                    order = "desc",
                    parentId = if (roots) "null" else null,
                ).data
                .map { it.withDirectory() }
        return all.filter { it.directory == directory }
    }

    override suspend fun renameProject(
        projectId: String,
        body: Map<String, String>,
    ): ResponseBody = requireBody(v2.renameProject(projectId, body))

    override suspend fun getSession(sessionId: String): SessionResponse =
        SessionResponse(data = (v2.getSession(sessionId).data ?: error("empty session response")).withDirectory())

    override suspend fun getSessionFull(sessionId: String): Session =
        (v2.getSession(sessionId).data ?: error("empty session response")).withDirectory()

    override suspend fun getMessages(
        sessionId: String,
        limit: Int?,
    ): List<Message> = v2.messages(sessionId, limit = limit).data

    override suspend fun getMessagesRaw(
        sessionId: String,
        limit: Int?,
        before: String?,
    ): Response<ResponseBody> = v2.messagesRaw(sessionId, limit = limit, cursor = before)

    override suspend fun getApiMessages(
        sessionId: String,
        limit: Int?,
    ): MessageListResponse = v2.messages(sessionId, limit = limit)

    override suspend fun sendPrompt(
        sessionId: String,
        request: PromptRequest,
    ): PromptResponse {
        // V1 carried selection in the prompt; V2 stores it on the session.
        val model = request.model?.let(::parseModelRef)
        if (model != null) setModel(sessionId, ModelRefRequest(model))
        request.agent?.takeIf { it.isNotBlank() }?.let { setAgent(sessionId, mapOf("agent" to it)) }
        v2.prompt(sessionId, PromptBody(text = request.prompt.text))
        return PromptResponse(data = PromptResult(sessionId = sessionId))
    }

    override suspend fun sendPromptAsync(
        sessionId: String,
        request: PromptAsyncRequest,
        guardRevision: Long?,
    ): Response<ResponseBody> {
        // V2 selects the model/agent via the session, not the prompt body. The
        // guard proxy bumps its revision on each of those writes and returns the
        // new revision in `X-Session-Guard-Revision`; carry it onto the prompt so
        // the guard's compare-and-set does not see our own writes as drift.
        var revision = guardRevision
        request.model?.let { m ->
            val response =
                setModel(
                    sessionId,
                    ModelRefRequest(ModelRef(id = m.modelID, providerId = m.providerID, variant = request.variant)),
                )
            revision = guardRevisionOf(response) ?: revision
        }
        request.agent?.takeIf { it.isNotBlank() }?.let {
            val response = setAgent(sessionId, mapOf("agent" to it))
            revision = guardRevisionOf(response) ?: revision
        }

        val text =
            request.parts
                .filter { it.type == "text" }
                .mapNotNull { it.text }
                .joinToString("\n")
        val files =
            request.parts
                .filter { it.type == "file" && it.url != null }
                .map { PromptFile(uri = it.url!!, name = it.filename) }
        return v2.prompt(
            sessionId = sessionId,
            body = PromptBody(id = request.messageID, text = text, files = files),
            guardRevision = revision,
        )
    }

    override suspend fun interrupt(sessionId: String) {
        v2.interrupt(sessionId)
    }

    override suspend fun abort(sessionId: String): Response<ResponseBody> = v2.interrupt(sessionId)

    override suspend fun setModel(
        sessionId: String,
        body: ModelRefRequest,
    ): Response<ResponseBody> = v2.setModel(sessionId, body)

    override suspend fun setAgent(
        sessionId: String,
        body: Map<String, String>,
    ): Response<ResponseBody> = v2.setAgent(sessionId, body)

    override suspend fun renameSession(
        sessionId: String,
        body: Map<String, String>,
    ): Session = patchSession(sessionId, SessionUpdateRequest(title = body["title"]))

    override suspend fun updateSession(
        sessionId: String,
        body: SessionUpdateRequest,
    ): Session = patchSession(sessionId, SessionUpdateRequest(title = body.title))

    /**
     * V2 `PATCH /api/session/{id}` answers **204 with no body** (verified live),
     * so the updated session must be re-read instead of decoded from the patch
     * response — decoding a non-null body from an empty 204 threw
     * "response body was null but declared as non-null".
     */
    private suspend fun patchSession(
        sessionId: String,
        body: SessionUpdateRequest,
    ): Session {
        val response = v2.updateSession(sessionId, body)
        if (!response.isSuccessful) throw IOException("session update HTTP ${response.code()}")
        return getSessionFull(sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        v2.deleteSession(sessionId)
    }

    override suspend fun revertMessage(
        sessionId: String,
        body: RevertRequest,
    ): ResponseBody =
        requireBody(
            v2.revertStage(sessionId, RevertStageBody(messageID = body.messageID)),
        )

    override suspend fun unrevertSession(sessionId: String): ResponseBody = requireBody(v2.revertClear(sessionId))

    override suspend fun forkSession(
        sessionId: String,
        body: ForkRequest,
    ): JsonObject {
        val forked = v2.fork(sessionId, ForkBody(before = body.messageID)).data
        return buildJsonObject {
            putJsonObject("data") {
                forked?.let { put("id", it.id) }
            }
        }
    }

    override suspend fun shareSession(sessionId: String): Session = getSessionFull(sessionId)

    override suspend fun exportSession(sessionId: String): List<Message> =
        v2
            .exportSession(sessionId)
            .data
            ?.messages
            .orEmpty()

    override suspend fun getSessionChildren(sessionId: String): List<Session> =
        v2.listSessions(parentId = sessionId).data.map { it.withDirectory() }

    override suspend fun getSessionDiff(
        sessionId: String,
        messageId: String?,
    ): List<JsonElement> =
        v2.sessionDiff(sessionId, messageId = messageId).data.orEmpty().map {
            json.encodeToJsonElement(VcsDiffFile.serializer(), it)
        }

    override suspend fun revertClear(sessionId: String) {
        v2.revertClear(sessionId)
    }

    override suspend fun revertCommit(sessionId: String) {
        v2.revertCommit(sessionId)
    }

    override suspend fun revertStage(sessionId: String) {
        // V2 requires a message id; the bare V1 stage call has none, so nothing
        // can be staged. Kept for interface compatibility.
    }

    override suspend fun getContext(sessionId: String): ContextUsage = ContextUsage(data = v2.context(sessionId).data.orEmpty())

    // --- filesystem / location --------------------------------------------

    override suspend fun getFiles(
        path: String,
        directory: String,
    ): List<FileEntry> =
        v2
            .fsList(path = path, directory = directory)
            .data
            .orEmpty()
            .map { it.toFileEntry(directory) }

    override suspend fun findFiles(
        query: String,
        type: String,
        limit: Int,
        directory: String?,
    ): List<String> =
        v2
            .fsFind(query = query, type = type, limit = limit, directory = directory)
            .data
            .orEmpty()
            .map { it.path }

    override suspend fun getPathInfo(): PathInfo {
        // V2 has no dedicated "home" route; seed the browser at the active
        // location (which the UI can navigate up from).
        val dir = v2.location().directory
        return PathInfo(home = dir, directory = dir)
    }

    override suspend fun getFileContent(path: String): FileContent {
        val response = v2.fsRead(path)
        val body = response.body() ?: return FileContent(content = null, path = path)
        val text = body.use { it.string() }
        return FileContent(content = text, path = path)
    }

    // --- catalog -----------------------------------------------------------

    override suspend fun getAgents(): DataListResponse<Agent> = DataListResponse(data = v2.agents().data.orEmpty())

    override suspend fun getProjectAgents(directory: String?): List<ProjectAgent> =
        v2
            .agents(directory)
            .data
            .orEmpty()
            .map { ProjectAgent(name = it.id, description = it.description, mode = it.mode) }

    override suspend fun getModels(): DataListResponse<Model> =
        DataListResponse(
            data =
                v2
                    .models()
                    .data
                    .orEmpty()
                    .map { it.toModel() },
        )

    override suspend fun getProviders(): DataListResponse<Provider> {
        val modelsByProvider =
            v2
                .models()
                .data
                .orEmpty()
                .groupBy { it.providerId.orEmpty() }
        return DataListResponse(
            data =
                v2
                    .providers()
                    .data
                    .orEmpty()
                    .map { p ->
                        Provider(
                            id = p.id,
                            name = p.name,
                            models = modelsByProvider[p.id].orEmpty().map { it.toModel() },
                        )
                    },
        )
    }

    override suspend fun getProviderList(): ProvidersResponse {
        val providers = v2.providers().data.orEmpty()
        val modelsByProvider =
            v2
                .models()
                .data
                .orEmpty()
                .groupBy { it.providerId.orEmpty() }
        val all =
            providers.map { p ->
                ProviderEntry(
                    id = p.id,
                    name = p.name,
                    source = p.activation,
                    models =
                        modelsByProvider[p.id]
                            .orEmpty()
                            .associate { m -> m.id to m.toModel() },
                )
            }
        val connected = providers.filter { modelsByProvider.containsKey(it.id) }.map { it.id }
        return ProvidersResponse(all = all, default = emptyMap(), connected = connected)
    }

    override suspend fun getCommands(directory: String?): List<CommandEntry> =
        v2
            .commands(directory)
            .data
            .orEmpty()
            .map { CommandEntry(name = it.name, description = it.description) }

    override suspend fun getSkills(): List<JsonElement> =
        v2.skills().data.orEmpty().map {
            json.encodeToJsonElement(
                com.opencode.android.domain.WireSkill
                    .serializer(),
                it,
            )
        }

    // --- project / vcs -----------------------------------------------------

    override suspend fun getProjects(): List<Project> = v2.projects()

    override suspend fun getVcs(directory: String?): VcsInfo {
        val branch = v2.vcs(directory).data?.branch
        return VcsInfo(branch = branch?.current, defaultBranch = branch?.default)
    }

    override suspend fun getVcsDiff(
        mode: String,
        directory: String?,
    ): List<VcsDiffFile> {
        // V1 used mode=git; V2's Vcs.Mode accepts `working` / `branch`. The app's
        // Changes tab wants the working-tree diff, so map the legacy default.
        val v2Mode = if (mode.isBlank() || mode == "git") "working" else mode
        return v2.vcsDiff(mode = v2Mode, directory = directory).data.orEmpty()
    }

    override suspend fun getTodos(sessionId: String): List<TodoItem> = emptyList()

    // --- providers / auth --------------------------------------------------

    override suspend fun disconnectProvider(providerId: String): Response<ResponseBody> {
        // V2 removed `/auth/{id}`: credentials are first-class and keyed by
        // integration id. Remove every credential for the provider.
        return try {
            val credentials = v2.credentials().data.orEmpty()
            val matching = credentials.filter { it.integrationId == providerId }
            matching.forEach { credential -> credential.id?.let { v2.deleteCredential(it) } }
            Response.success(emptyBody())
        } catch (e: retrofit2.HttpException) {
            Response.error(e.code(), emptyBody())
        }
    }

    override suspend fun getProviderAuth(): Map<String, List<ProviderAuthMethod>> {
        val integrations =
            try {
                v2.integrations().data.orEmpty()
            } catch (_: Exception) {
                return emptyMap()
            }
        val result = LinkedHashMap<String, List<ProviderAuthMethod>>()
        for (integration in integrations) {
            integrationMethods[integration.id] = integration.methods
            val methods =
                integration.methods.mapNotNull { method ->
                    when (method.type) {
                        "key" -> {
                            ProviderAuthMethod(type = "api", label = method.label, prompts = integrationPrompts(method))
                        }

                        "oauth" -> {
                            ProviderAuthMethod(type = "oauth", label = method.label, prompts = integrationPrompts(method))
                        }

                        else -> {
                            null
                        }
                    }
                }
            if (methods.isNotEmpty()) result[integration.id] = methods
        }
        return result
    }

    override suspend fun setProviderAuth(
        providerId: String,
        body: AuthSetRequest,
    ): Response<ResponseBody> {
        val value =
            buildJsonObject {
                put("type", "key")
                body.key?.takeIf { it.isNotBlank() }?.let { put("key", it) }
                if (body.prompts.isNotEmpty()) {
                    putJsonObject("metadata") {
                        body.prompts.forEach { (key, promptValue) -> put(key, promptValue) }
                    }
                }
            }
        return try {
            v2.createCredential(
                WireCredentialCreate(
                    integrationId = providerId,
                    label = "API key",
                    value = value,
                    activate = true,
                ),
            )
            Response.success(emptyBody())
        } catch (e: retrofit2.HttpException) {
            Response.error(e.code(), emptyBody())
        }
    }

    override suspend fun globalDispose(): Response<ResponseBody> = v2.reloadLocation()

    override suspend fun providerOauthAuthorize(
        providerId: String,
        body: JsonElement,
    ): JsonElement {
        val methodIndex = (body as? JsonObject)?.get("method")?.jsonPrimitive?.intOrNull ?: 0
        val answer = (body as? JsonObject)?.get("answer") as? JsonObject
        val methods = integrationMethods[providerId] ?: loadIntegrationMethods(providerId)
        val oauthMethods = methods.filter { it.type == "oauth" }
        val methodId = oauthMethods.getOrNull(methodIndex)?.id ?: oauthMethods.firstOrNull()?.id ?: return JsonObject(emptyMap())
        val attempt =
            v2
                .connectIntegrationOauth(
                    providerId,
                    buildJsonObject {
                        put("methodID", methodId)
                        // Required OAuth method inputs (e.g. GitHub Enterprise URL).
                        if (answer != null && answer.isNotEmpty()) put("answer", answer)
                    },
                ).data
        attempt?.attemptId?.let { oauthAttempts[providerId] = it }
        return buildJsonObject {
            attempt?.url?.let { put("url", it) }
            attempt?.instructions?.let { put("instructions", it) }
        }
    }

    override suspend fun providerOauthCallback(
        providerId: String,
        body: JsonElement,
    ): ResponseBody {
        val attemptId = oauthAttempts[providerId] ?: throw IOException("no OAuth attempt for $providerId")
        val code = (body as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        return v2
            .completeIntegrationOauth(
                providerId,
                attemptId,
                buildJsonObject { code?.let { put("code", it) } },
            ).body() ?: emptyBody()
    }

    private suspend fun loadIntegrationMethods(providerId: String): List<com.opencode.android.domain.WireIntegrationMethod> =
        try {
            val methods =
                v2
                    .integration(providerId)
                    .data
                    ?.methods
                    .orEmpty()
            integrationMethods[providerId] = methods
            methods
        } catch (_: Exception) {
            emptyList()
        }

    /** Auth inputs for a method, from both the V2 `prompts` and `form` shapes. */
    private fun integrationPrompts(method: com.opencode.android.domain.WireIntegrationMethod): List<ProviderAuthPrompt> {
        val fromPrompts =
            method.prompts.mapNotNull { prompt ->
                prompt.key?.let {
                    ProviderAuthPrompt(
                        type = prompt.type ?: "text",
                        key = it,
                        message = prompt.message,
                        placeholder = prompt.placeholder,
                    )
                }
            }
        val fromForm =
            method.form.mapNotNull { field ->
                field.key?.takeIf { !field.hidden }?.let {
                    ProviderAuthPrompt(
                        type = field.type ?: "text",
                        key = it,
                        message = field.title ?: field.description,
                        placeholder = field.placeholder,
                    )
                }
            }
        return fromPrompts + fromForm
    }

    // --- mcp ---------------------------------------------------------------

    override suspend fun getMcpServers(directory: String?): Map<String, McpStatus> =
        v2
            .mcp(directory)
            .data
            .orEmpty()
            .associate { it.name to McpStatus(status = it.status?.status) }

    override suspend fun connectMcp(name: String): ResponseBody = requireBody(v2.mcpConnect(name))

    override suspend fun disconnectMcp(name: String): ResponseBody = requireBody(v2.mcpDisconnect(name))

    override suspend fun authenticateMcp(name: String): ResponseBody = requireBody(v2.mcpConnect(name))

    // --- permissions / questions (v2 forms) --------------------------------

    override suspend fun getPermissions(directory: String?): List<PermissionRequest> =
        v2
            .permissionRequests(directory)
            .data
            .orEmpty()
            .map { it.toDomain() }

    override suspend fun replyPermission(
        requestId: String,
        body: PermissionReplyRequest,
    ): ResponseBody {
        val sessionId = formSessionIds[requestId] ?: throw IOException("unknown permission request $requestId")
        return requireBody(v2.replyPermission(sessionId, requestId, PermissionDecision(decision = body.reply)))
    }

    override suspend fun replySessionPermission(
        sessionId: String,
        permissionId: String,
        body: PermissionReplyRequest,
    ): ResponseBody = requireBody(v2.replyPermission(sessionId, permissionId, PermissionDecision(decision = body.reply)))

    override suspend fun getSessionQuestions(sessionId: String): SessionQuestionListResponse {
        val forms = v2.sessionForms(sessionId).data.orEmpty()
        rememberForms(forms)
        return SessionQuestionListResponse(data = forms.map { it.toDomain() })
    }

    override suspend fun getQuestionRequests(directory: String?): SessionQuestionListResponse {
        val forms = v2.forms(directory).data.orEmpty()
        rememberForms(forms)
        return SessionQuestionListResponse(data = forms.map { it.toDomain() })
    }

    override suspend fun replyQuestion(
        requestId: String,
        body: QuestionReplyRequest,
    ): ResponseBody {
        val sessionId = formSessionIds[requestId] ?: throw IOException("unknown question $requestId")
        return requireBody(v2.replyForm(sessionId, requestId, FormReplyBody(answerMap(requestId, body))))
    }

    override suspend fun rejectQuestion(requestId: String): ResponseBody {
        val sessionId = formSessionIds[requestId] ?: throw IOException("unknown question $requestId")
        return requireBody(v2.cancelForm(sessionId, requestId))
    }

    override suspend fun replySessionQuestion(
        sessionId: String,
        requestId: String,
        body: QuestionReplyRequest,
    ): ResponseBody = requireBody(v2.replyForm(sessionId, requestId, FormReplyBody(answerMap(requestId, body))))

    override suspend fun rejectSessionQuestion(
        sessionId: String,
        requestId: String,
    ): ResponseBody = requireBody(v2.cancelForm(sessionId, requestId))

    // --- misc --------------------------------------------------------------

    override suspend fun initSession(
        sessionId: String,
        body: JsonElement,
    ): ResponseBody =
        requireBody(
            v2.runCommand(
                sessionId,
                buildJsonObject {
                    put("name", "init")
                    put("text", "")
                },
            ),
        )

    override suspend fun summarizeSession(
        sessionId: String,
        directory: String?,
        body: SummarizeRequest,
    ): Response<ResponseBody> = v2.compact(sessionId, CompactBody(), directory)

    override suspend fun postLog(body: JsonElement): ResponseBody {
        // OpenCode 2.x has no `/log` server endpoint. Forward through the
        // optional session-guard proxy's client-log route (best effort); a plain
        // server simply ignores it.
        val obj = body as? JsonObject
        val message = (obj?.get("message") as? JsonPrimitive)?.contentOrNull ?: return emptyBody()
        val level = (obj["level"] as? JsonPrimitive)?.contentOrNull ?: "info"
        val service = (obj["service"] as? JsonPrimitive)?.contentOrNull ?: "opencode-android"
        val text = "[$level] $service: $message\n"
        return try {
            v2
                .clientLog(
                    name = "android-client.log",
                    append = "1",
                    body = text.toRequestBody("text/plain; charset=utf-8".toMediaType()),
                ).body() ?: emptyBody()
        } catch (_: Exception) {
            emptyBody()
        }
    }

    // --- session guard (optional proxy) -----------------------------------

    override suspend fun sessionGuardHealth(): Response<SessionGuardHealth> = v2.sessionGuardHealth()

    override suspend fun sessionGuardMetrics(): Response<ResponseBody> = v2.sessionGuardMetrics()

    override suspend fun adoptServerSelection(
        sessionId: String,
        expectedRevision: Long?,
    ): Response<SessionGuard> = v2.adoptServerSelection(sessionId, expectedRevision)

    override suspend fun pushGuardSelection(
        sessionId: String,
        expectedRevision: Long?,
    ): Response<SessionGuard> = v2.pushGuardSelection(sessionId, expectedRevision)

    override suspend fun removeGuardSelection(
        sessionId: String,
        expectedRevision: Long?,
    ): Response<Unit> = v2.removeGuardSelection(sessionId, expectedRevision)

    override suspend fun uploadAttachment(
        name: String,
        contentType: String,
        body: RequestBody,
    ): Response<UploadResponse> = v2.uploadAttachment(name, contentType, body)

    // --- helpers -----------------------------------------------------------

    private fun rememberForms(forms: List<WireForm>) {
        for (form in forms) {
            val id = form.id ?: continue
            form.sessionId?.let { formSessionIds[id] = it }
            formFields[id] = form.fields
        }
    }

    private fun answerMap(
        requestId: String,
        body: QuestionReplyRequest,
    ): Map<String, List<String>> {
        val fields = formFields[requestId].orEmpty()
        return body.answers
            .mapIndexedNotNull { index, answers ->
                fields.getOrNull(index)?.key?.let { it to answers }
            }.toMap()
    }

    /** `provider/model#variant` or a bare model id → a V2 [ModelRef]. */
    private fun parseModelRef(value: String): ModelRef? {
        if (value.isBlank()) return null
        val variant = value.substringAfter('#', "").takeIf { it.isNotBlank() }
        val withoutVariant = value.substringBefore('#')
        val providerId = withoutVariant.substringBefore('/', "").takeIf { it.isNotBlank() } ?: return null
        val modelId = withoutVariant.substringAfter('/', withoutVariant)
        return ModelRef(id = modelId, providerId = providerId, variant = variant)
    }

    private fun requireBody(response: Response<ResponseBody>): ResponseBody {
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
        return response.body() ?: emptyBody()
    }

    private fun emptyBody(): ResponseBody = ResponseBody.create(null, ByteArray(0))

    private fun emptyResponse(): Response<ResponseBody> = Response.success(emptyBody())

    /** Guard revision reported by the optional session-guard proxy, if present. */
    private fun guardRevisionOf(response: Response<ResponseBody>): Long? = response.headers()["X-Session-Guard-Revision"]?.toLongOrNull()

    /** OpenCode 2.x nests the working directory under `location`. */
    private fun Session.withDirectory(): Session = if (directory.isNullOrBlank()) copy(directory = location?.directory) else this

    private fun WirePermission.toDomain(): PermissionRequest =
        PermissionRequest(
            id = id,
            sessionId = sessionId,
            permission = action,
            title = message ?: action,
            description = resources.joinToString(", ").takeIf { it.isNotBlank() },
            metadata = metadata,
        )

    private fun WireForm.toDomain(): SessionQuestion {
        val questions =
            fields.map { field ->
                QuestionItem(
                    question = field.title,
                    header = field.key,
                    multiple = field.multiple,
                    options =
                        field.options.map {
                            QuestionOption(label = it.label ?: it.value.orEmpty(), description = it.description)
                        },
                )
            }
        return SessionQuestion(
            id = id ?: "",
            sessionId = sessionId,
            questions = questions,
            tool = null as QuestionToolRef?,
        )
    }

    private fun com.opencode.android.domain.WireFsEntry.toFileEntry(baseDir: String): FileEntry =
        FileEntry(
            name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
            // `path` is relative to the location (`baseDir`) and is fed back
            // verbatim as the next listing's `path`.
            path = path,
            absolute = joinPath(baseDir, path),
            type = if (type == "directory" || path.endsWith("/")) "directory" else "file",
        )

    /** Resolves a location-relative V2 path against an absolute base directory. */
    private fun joinPath(
        base: String,
        relative: String,
    ): String {
        if (relative.startsWith("/")) return normalizePath(relative)
        val combined = base.trimEnd('/') + "/" + relative
        return normalizePath(combined)
    }

    private fun normalizePath(path: String): String {
        val absolute = path.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {
                    Unit
                }

                ".." -> {
                    if (parts.isNotEmpty() && parts.last() != "..") {
                        parts.removeLast()
                    } else if (!absolute) {
                        parts.addLast("..")
                    }
                }

                else -> {
                    parts.addLast(segment)
                }
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    private fun com.opencode.android.domain.WireModel.toModel(): Model =
        Model(
            id = id,
            name = name,
            providerId = providerId,
            model = modelId ?: id,
            limit = limit,
        )

    companion object {
        /** Web home list fetches `/api/session?limit=5000&order=desc&parentID=null`. */
        private const val HOME_SESSION_LIMIT = 5000
    }
}
