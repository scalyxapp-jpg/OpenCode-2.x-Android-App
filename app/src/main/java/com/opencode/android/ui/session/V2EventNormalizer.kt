package com.opencode.android.ui.session

import com.opencode.android.domain.CacheTokens
import com.opencode.android.domain.Event
import com.opencode.android.domain.EventData
import com.opencode.android.domain.EventProperties
import com.opencode.android.domain.Part
import com.opencode.android.domain.PartTime
import com.opencode.android.domain.SessionModel
import com.opencode.android.domain.SessionStatus
import com.opencode.android.domain.SseEnvelope
import com.opencode.android.domain.Tokens
import com.opencode.android.domain.VcsDiffFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Decodes an OpenCode 2.x `/api/event` frame into a domain [Event].
 *
 * The V2 wire frame is `{"id","created","type","location","data","durable"}`:
 * the payload lives under **`data`** (not `properties`, not wrapped in
 * `payload`). Rather than rewrite the whole streaming reducer, this normaliser
 * rewrites the V2 event vocabulary onto the app's existing event names
 * (`session.next.*`, `message.part.*`, `session.status`, …) and fills the
 * domain [EventData]/[EventProperties] the reducer already understands.
 *
 * See `.opencode/opencode-v2-api.md`.
 */
object V2EventNormalizer {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** V2 frame. `data` is kept raw so field extraction never fails a decode. */
    @kotlinx.serialization.Serializable
    private data class Frame(
        val id: String? = null,
        val type: String? = null,
        val data: JsonElement? = null,
    )

    /** Null on anything unparseable so one bad frame cannot kill the stream. */
    fun normalize(raw: String): Event? =
        try {
            // Backwards compatibility: an older V1/proxy frame wrapped the event
            // in `{"payload":{...}}`. Try that first, then the V2 frame.
            val envelope = json.decodeFromString(SseEnvelope.serializer(), raw)
            envelope.payload?.let { return it }
            val frame = json.decodeFromString(Frame.serializer(), raw)
            toEvent(frame)
        } catch (_: Exception) {
            null
        }

    private fun toEvent(frame: Frame): Event? {
        val type = frame.type ?: return null
        val d = (frame.data as? JsonObject) ?: JsonObject(emptyMap())
        return when (type) {
            // --- assistant turn lifecycle ---------------------------------
            "session.step.started", "session.next.step.started" -> {
                Event(
                    type = "session.next.step.started",
                    properties = EventProperties(sessionId = d.str("sessionID")),
                    data =
                        EventData(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            agent = d.str("agent"),
                            model = modelOf(d["model"]),
                        ),
                )
            }

            "session.step.ended", "session.next.step.ended" -> {
                Event(
                    type = "session.next.step.ended",
                    properties = EventProperties(sessionId = d.str("sessionID")),
                    data =
                        EventData(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            finish = d.str("finish"),
                            cost = d.num("cost"),
                            tokens = tokensOf(d["tokens"]),
                        ),
                )
            }

            "session.step.failed", "session.next.step.failed" -> {
                Event(
                    type = "session.next.step.failed",
                    properties = EventProperties(sessionId = d.str("sessionID")),
                    data = EventData(sessionId = d.str("sessionID"), error = d["error"] ?: d["data"]),
                )
            }

            // --- reasoning stream -----------------------------------------
            "session.reasoning.started" -> {
                Event(
                    type = "session.next.reasoning.started",
                    properties = EventProperties(sessionId = d.str("sessionID")),
                    data = EventData(sessionId = d.str("sessionID")),
                )
            }

            "session.reasoning.ended" -> {
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            part = textPart(d.str("reasoningID") ?: "reasoning", "reasoning", d.str("text"), d),
                        ),
                )
            }

            "session.reasoning.delta" -> {
                Event(
                    type = "message.part.delta",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            partId = d.str("reasoningID"),
                            field = "reasoning",
                            delta = d.str("delta"),
                        ),
                )
            }

            // --- text stream ----------------------------------------------
            "session.text.started" -> {
                Event(
                    type = "session.next.text.started",
                    properties = EventProperties(sessionId = d.str("sessionID")),
                    data = EventData(sessionId = d.str("sessionID")),
                )
            }

            "session.text.delta" -> {
                Event(
                    type = "message.part.delta",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            partId = d.str("textID"),
                            field = "text",
                            delta = d.str("delta"),
                        ),
                )
            }

            "session.text.ended" -> {
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            part = textPart(d.str("textID") ?: "text", "text", d.str("text"), d),
                        ),
                )
            }

            // --- tools ----------------------------------------------------
            "session.tool.called" -> {
                Event(type = "message.part.updated", properties = toolProperties(d, "running"))
            }

            "session.tool.input.ended" -> {
                // `text` is the raw JSON input captured so far.
                val input = d.str("text")
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("assistantMessageID"),
                            part = toolPart(d.callId(), "running", inputElement = parseOrNull(input), d),
                        ),
                )
            }

            "session.tool.input.started" -> {
                Event(type = "message.part.updated", properties = toolProperties(d, "running"))
            }

            "session.tool.progress" -> {
                Event(type = "message.part.updated", properties = toolProperties(d, "running"))
            }

            "session.tool.success" -> {
                Event(type = "message.part.updated", properties = toolProperties(d, "completed"))
            }

            "session.tool.failed" -> {
                Event(type = "message.part.updated", properties = toolProperties(d, "error"))
            }

            // --- messages / parts (present in V2 too) ----------------------
            "message.part.delta" -> {
                Event(
                    type = "message.part.delta",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("messageID"),
                            partId = d.str("partID"),
                            field = d.str("field"),
                            delta = d.str("delta"),
                        ),
                )
            }

            "message.part.updated" -> {
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            messageId = d.str("messageID"),
                            part =
                                runCatching {
                                    json.decodeFromJsonElement(
                                        Part.serializer(),
                                        d["part"] ?: JsonObject(emptyMap()),
                                    )
                                }.getOrNull(),
                        ),
                )
            }

            "message.updated" -> {
                val role = d.str("type")
                val info =
                    com.opencode.android.domain.MessageInfo(
                        id = d.str("id"),
                        sessionId = d.str("sessionID"),
                        role = if (role == "user" || role == "assistant") role else null,
                        agent = d.str("agent"),
                        model = modelOf(d["model"]),
                        tokens = tokensOf(d["tokens"]),
                        cost = d.num("cost"),
                        error = d["error"],
                    )
                Event(type = "message.updated", properties = EventProperties(sessionId = d.str("sessionID"), info = info))
            }

            "message.removed", "message.part.removed" -> {
                Event(
                    type = type,
                    properties = EventProperties(sessionId = d.str("sessionID"), messageId = d.str("messageID")),
                )
            }

            // --- session state --------------------------------------------
            // The user's prompt is echoed via the inbox, NOT `message.updated`.
            // `inboxID` is the client-generated message id, so recording it lets
            // the optimistic row confirm and the echoed user parts stay out of the
            // assistant stream (no double prompt).
            "session.inbox.enqueued" -> {
                Event(
                    type = "message.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            info =
                                com.opencode.android.domain.MessageInfo(
                                    id = d.str("inboxID"),
                                    sessionId = d.str("sessionID"),
                                    role = "user",
                                ),
                        ),
                )
            }

            "session.inbox.delivered", "session.inbox.cancelled", "session.inbox.delivery.changed",
            "session.step.streamed",
            -> {
                null
            }

            "session.idle" -> {
                Event(type = "session.idle", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "session.status" -> {
                Event(
                    type = "session.status",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            status = statusOf(d["status"] ?: d),
                        ),
                )
            }

            "session.error" -> {
                Event(
                    type = "session.error",
                    properties = EventProperties(sessionId = d.str("sessionID"), error = d["error"] ?: d["data"]),
                )
            }

            "session.diff" -> {
                Event(
                    type = "session.diff",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            diff =
                                runCatching {
                                    json.decodeFromJsonElement<List<VcsDiffFile>>(
                                        d["diff"] ?: JsonArray(emptyList()),
                                    )
                                }.getOrDefault(emptyList()),
                        ),
                )
            }

            "session.updated", "session.renamed", "session.created", "session.moved" -> {
                Event(type = "session.updated", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "todo.updated" -> {
                Event(
                    type = "todo.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            todos =
                                runCatching {
                                    json.decodeFromJsonElement<List<com.opencode.android.domain.TodoItem>>(
                                        d["todos"] ?: JsonArray(emptyList()),
                                    )
                                }.getOrDefault(emptyList()),
                        ),
                )
            }

            "session.model.selected", "session.agent.selected", "session.next.model.switched" -> {
                Event(type = "session.next.model.switched", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            // --- requests -------------------------------------------------
            "permission.asked", "permission.v2.asked" -> {
                Event(type = "permission.asked", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "permission.replied" -> {
                Event(type = "permission.replied", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "question.asked", "question.v2.asked" -> {
                Event(
                    type = type,
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            questionId = d.str("id"),
                            requestId = d.str("id"),
                            questions =
                                runCatching {
                                    json.decodeFromJsonElement<List<com.opencode.android.domain.QuestionItem>>(
                                        d["questions"] ?: JsonArray(emptyList()),
                                    )
                                }.getOrDefault(emptyList()),
                        ),
                )
            }

            "question.replied", "question.rejected", "question.v2.replied", "question.v2.rejected" -> {
                Event(
                    type = type,
                    properties = EventProperties(sessionId = d.str("sessionID"), requestId = d.str("id") ?: d.str("requestID")),
                )
            }

            // --- misc (no UI effect; dropped) ------------------------------
            "session.usage.updated", "session.message.content.updated", "session.metadata.updated" -> {
                // Cost/token/usage changes: trigger a metadata reconcile.
                Event(type = "session.updated", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "session.compaction.started" -> {
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            part =
                                com.opencode.android.domain.Part(
                                    id = "compaction",
                                    type = "compaction",
                                    messageId = d.str("assistantMessageID"),
                                ),
                        ),
                )
            }

            "session.compaction.ended", "session.compacted", "session.compaction.failed" -> {
                Event(
                    type = "message.part.updated",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            part =
                                com.opencode.android.domain.Part(
                                    id = "compaction",
                                    type = "step-finish",
                                    messageId = d.str("assistantMessageID"),
                                ),
                        ),
                )
            }

            "session.retry.scheduled" -> {
                Event(
                    type = "session.status",
                    properties =
                        EventProperties(
                            sessionId = d.str("sessionID"),
                            status =
                                SessionStatus(
                                    type = "retry",
                                    message = d.str("message") ?: "Request failed — retrying…",
                                    attempt = (d["attempt"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                                    next = d["next"]?.jsonPrimitive?.longOrNull,
                                ),
                        ),
                )
            }

            "session.execution.started" -> {
                Event(
                    type = "session.status",
                    properties = EventProperties(sessionId = d.str("sessionID"), status = SessionStatus(type = "busy")),
                )
            }

            "session.execution.succeeded", "session.execution.interrupted" -> {
                Event(type = "session.idle", properties = EventProperties(sessionId = d.str("sessionID")))
            }

            "session.execution.failed" -> {
                Event(
                    type = "session.error",
                    properties = EventProperties(sessionId = d.str("sessionID"), error = d["error"] ?: d["data"]),
                )
            }

            "server.connected" -> {
                null
            }

            else -> {
                null
            }
        }
    }

    private fun toolProperties(
        d: JsonObject,
        status: String,
    ): EventProperties =
        EventProperties(
            sessionId = d.str("sessionID"),
            messageId = d.str("assistantMessageID"),
            part = toolPart(d.callId(), status, inputElement = d["input"], d = d),
        )

    private fun toolPart(
        callId: String?,
        status: String,
        inputElement: JsonElement?,
        d: JsonObject,
    ): Part {
        val state =
            buildJsonObject {
                put("status", status)
                inputElement?.let { put("input", it) }
                d["content"]?.let { put("content", it) }
                d["structured"]?.let { put("structured", it) }
                d["metadata"]?.let { put("metadata", it) }
                d["error"]?.let { put("error", it) }
            }
        return Part(
            id = callId,
            type = "tool",
            name = d.str("name") ?: d.str("tool"),
            tool = d.str("tool") ?: d.str("name"),
            messageId = d.str("assistantMessageID"),
            state = state,
            time = PartTime(start = d.long("created"), end = null),
        )
    }

    private fun textPart(
        id: String,
        type: String,
        text: String?,
        d: JsonObject,
    ): Part =
        Part(
            id = id,
            type = type,
            text = text,
            messageId = d.str("assistantMessageID"),
            time = PartTime(end = d.long("completed") ?: 1L),
        )

    // --- field readers -----------------------------------------------------

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.callId(): String? = str("id") ?: str("callID")

    private fun parseOrNull(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    private fun modelOf(element: JsonElement?): SessionModel? {
        val obj = element as? JsonObject ?: return null
        return SessionModel(
            id = obj.str("id") ?: obj.str("modelID"),
            providerID = obj.str("providerID"),
            modelID = obj.str("modelID") ?: obj.str("id"),
            variant = obj.str("variant"),
        )
    }

    private fun tokensOf(element: JsonElement?): Tokens? {
        val obj = element as? JsonObject ?: return null
        val cache = obj["cache"] as? JsonObject
        return Tokens(
            input = obj["input"]?.jsonPrimitive?.longOrNull,
            output = obj["output"]?.jsonPrimitive?.longOrNull,
            reasoning = obj["reasoning"]?.jsonPrimitive?.longOrNull,
            cache =
                cache?.let {
                    CacheTokens(
                        read = it["read"]?.jsonPrimitive?.longOrNull,
                        write = it["write"]?.jsonPrimitive?.longOrNull,
                    )
                },
        )
    }

    private fun statusOf(element: JsonElement?): SessionStatus? {
        val obj = element as? JsonObject ?: return null
        val type = obj.str("type") ?: return null
        return SessionStatus(
            type = if (type == "running") "busy" else type,
            message = obj.str("message"),
            attempt = (obj["attempt"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            next = obj["next"]?.jsonPrimitive?.longOrNull,
        )
    }
}
