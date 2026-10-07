package com.opencode.android.ui.session

import com.opencode.android.domain.PromptAsyncModel
import com.opencode.android.domain.PromptAsyncPart
import com.opencode.android.domain.PromptAsyncRequest

/**
 * Candidate 1 strangler: the pure prompt-body construction of the send path.
 *
 * `performSend` used to assemble the `prompt_async` payload inline: omit a
 * blank agent, omit a blank/`default` variant, omit the model when the id is
 * blank, and drop the text part entirely for an attachment-only turn. Those
 * rules are wire contract (the server rejects/ignores the wrong shapes), so
 * they live in a pure function with tests instead of inside a 300-line method.
 *
 * The ids are passed in because they are generated (`msg_…` / `prt_…`) at the
 * call site; that keeps this function deterministic and testable.
 */
fun buildPromptAsyncRequest(
    messageId: String,
    partId: String,
    agent: String,
    providerId: String,
    modelId: String,
    variant: String,
    finalText: String,
    // Portable attachments: one `file` part per attachment, each with a base64
    // data URL (the web shape). Used when the optional guard proxy is absent, so
    // a plain OpenCode server still receives the files. Empty when the guard
    // uploaded them to the host and the paths went into [finalText].
    fileParts: List<PromptAsyncPart> = emptyList(),
): PromptAsyncRequest =
    PromptAsyncRequest(
        messageID = messageId,
        agent = agent.ifEmpty { null },
        model =
            modelId.takeIf { it.isNotBlank() }?.let {
                PromptAsyncModel(modelID = it, providerID = providerId)
            },
        // Web sends variant as a top-level field; omit "default".
        variant = variant.takeIf { it.isNotBlank() && it != "default" },
        parts =
            buildList {
                if (finalText.isNotBlank()) {
                    add(PromptAsyncPart(id = partId, type = "text", text = finalText))
                }
                addAll(fileParts)
            },
    )
