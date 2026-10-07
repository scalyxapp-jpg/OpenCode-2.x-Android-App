package com.opencode.android.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the OpenCode 2.x `/api` surface.
 *
 * Kept separate from the app's domain models so the blast radius of the V1→V2
 * migration stays in [com.opencode.android.data.OpenCodeApiAdapter] + the
 * mappers, and the UI keeps consuming the stable domain types in `Models.kt`.
 *
 * Shapes verified against the live 2.0.x server (OpenAPI + captured payloads);
 * see `.opencode/opencode-v2-api.md`.
 */

/** Standard `{ location?, data }` envelope used by most v2 endpoints. */
@Serializable
data class WireEnvelope<T>(
    val location: Location? = null,
    val data: T? = null,
)

/** Standard `{ data: <object> }` envelope used by session/credential routes. */
@Serializable
data class WireData<T>(
    val data: T? = null,
)

@Serializable
data class ServerInfo(
    val version: String? = null,
    val pid: Long? = null,
    val urls: List<String> = emptyList(),
)

/** `GET /api/location` */
@Serializable
data class WireLocation(
    val directory: String? = null,
    val project: LocationProject? = null,
)

/** `POST /api/session` body. */
@Serializable
data class CreateSessionBody(
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val location: SessionLocation? = null,
)

/** `PATCH /api/session/{id}` body (v2 has no `time.archived`). */
@Serializable
data class SessionPatch(
    val title: String? = null,
)

/** `POST /api/session/{id}/prompt` body. */
@Serializable
data class PromptBody(
    // Client-generated message id (`msg_…`). The server echoes it on the user
    // message, so the app can dedupe its optimistic row by id (web parity).
    val id: String? = null,
    val text: String = "",
    val files: List<PromptFile> = emptyList(),
    val agents: List<PromptAgent> = emptyList(),
    val delivery: String? = null,
    val resume: Boolean? = null,
)

@Serializable
data class PromptFile(
    val uri: String,
    val name: String? = null,
)

@Serializable
data class PromptAgent(
    val name: String,
)

/** `POST /api/session/{id}/compact` body. */
@Serializable
data class CompactBody(
    val id: String? = null,
    val delivery: String? = null,
)

/** `POST /api/session/{id}/fork` body. */
@Serializable
data class ForkBody(
    val before: String? = null,
)

/** `POST /api/session/{id}/revert/stage` body. */
@Serializable
data class RevertStageBody(
    val messageID: String,
    val files: Boolean? = null,
)

/** `POST /api/session/{id}/permission/{rid}/reply` body. */
@Serializable
data class PermissionDecision(
    val decision: String,
    val message: String? = null,
)

/** `POST /api/session/{id}/form/{fid}/reply` body. */
@Serializable
data class FormReplyBody(
    val answer: Map<String, List<String>> = emptyMap(),
)

/** A pending v2 permission request (`action`/`resources`, not `permission`). */
@Serializable
data class WirePermission(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val action: String? = null,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: kotlinx.serialization.json.JsonElement? = null,
    val message: String? = null,
)

/** A v2 form (replaces V1 questions). Fields are a tagged union. */
@Serializable
data class WireForm(
    val id: String? = null,
    @SerialName("sessionID") val sessionId: String? = null,
    val title: String? = null,
    val metadata: kotlinx.serialization.json.JsonElement? = null,
    val fields: List<WireFormField> = emptyList(),
)

@Serializable
data class WireFormField(
    val key: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val required: Boolean = false,
    val options: List<WireFormOption> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = false,
)

@Serializable
data class WireFormOption(
    val label: String? = null,
    val description: String? = null,
    val value: String? = null,
)

/** `/api/model` item. */
@Serializable
data class WireModel(
    val id: String,
    @SerialName("modelID") val modelId: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    val name: String? = null,
    val family: String? = null,
    val limit: ModelLimit? = null,
    val variants: List<WireVariant> = emptyList(),
    val enabled: Boolean = true,
    val status: String? = null,
)

@Serializable
data class WireVariant(
    val id: String,
    val name: String? = null,
)

/** `/api/provider` item (models live on `/api/model`). */
@Serializable
data class WireProvider(
    val id: String,
    @SerialName("integrationID") val integrationId: String? = null,
    val name: String? = null,
    val activation: String? = null,
    val disabled: Boolean = false,
)

/** `/api/mcp` item. */
@Serializable
data class WireMcp(
    val name: String,
    val status: WireMcpStatus? = null,
)

@Serializable
data class WireMcpStatus(
    val status: String? = null,
    val error: String? = null,
)

/** `/api/vcs` payload. */
@Serializable
data class WireVcs(
    val provider: String? = null,
    val branch: WireBranch? = null,
)

@Serializable
data class WireBranch(
    val current: String? = null,
    val default: String? = null,
)

/** `/api/fs/list` and `/api/fs/find` item. */
@Serializable
data class WireFsEntry(
    val path: String,
    val type: String? = null,
)

/** `/api/command` item. */
@Serializable
data class WireCommand(
    val name: String,
    val description: String? = null,
)

/** `/api/skill` item. */
@Serializable
data class WireSkill(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val autoinvoke: Boolean = false,
    val path: String? = null,
)

/** `/api/credential` item. */
@Serializable
data class WireCredential(
    val id: String? = null,
    @SerialName("integrationID") val integrationId: String? = null,
    val label: String? = null,
    val active: Boolean = false,
    val value: kotlinx.serialization.json.JsonElement? = null,
)

/** `POST /api/credential` body. */
@Serializable
data class WireCredentialCreate(
    @SerialName("integrationID") val integrationId: String,
    val label: String? = null,
    val value: kotlinx.serialization.json.JsonElement,
    val activate: Boolean = true,
)

/** `/api/integration` item. */
@Serializable
data class WireIntegration(
    val id: String,
    val name: String? = null,
    val methods: List<WireIntegrationMethod> = emptyList(),
)

@Serializable
data class WireIntegrationMethod(
    val type: String? = null,
    val id: String? = null,
    val label: String? = null,
    val names: List<String> = emptyList(),
    val prompts: List<WireIntegrationPrompt> = emptyList(),
    // Key/OAuth methods may declare a `form` of required inputs (V2 shape).
    val form: List<WireIntegrationFormField> = emptyList(),
)

@Serializable
data class WireIntegrationFormField(
    val key: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val placeholder: String? = null,
)

/** `GET /api/experimental/session/{id}/export` response data. */
@Serializable
data class WireSessionExport(
    val info: Session? = null,
    val messages: List<Message> = emptyList(),
)

@Serializable
data class WireIntegrationPrompt(
    val type: String? = null,
    val key: String? = null,
    val message: String? = null,
    val placeholder: String? = null,
)

/** `POST /api/integration/{id}/connect/oauth` response data. */
@Serializable
data class WireOAuthAttempt(
    @SerialName("attemptID") val attemptId: String? = null,
    val url: String? = null,
    val instructions: String? = null,
    val mode: String? = null,
)
