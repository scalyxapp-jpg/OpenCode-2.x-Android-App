package com.opencode.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [ComposerActions] bundle is pure data: this guards that each bundled
 * callback is the one the composer would invoke, so moving the flat composer
 * parameters into the bundle did not swap or drop a callback.
 */
class ComposerActionsTest {

    @Test
    fun actionsInvokeTheirCallbacks() {
        val calls = mutableListOf<String>()
        val actions = ComposerActions(
            onInputChange = { calls += "input:$it" },
            onSend = { calls += "send" },
            onInterrupt = { calls += "interrupt" },
            onAddFiles = { calls += "addFiles" },
            onRemoveAttachment = { calls += "remove:$it" },
            onPickCommand = { calls += "pick:$it" },
            onAgentSelect = { calls += "agent:$it" },
            onModelSelect = { calls += "model:$it" },
            onVariantSelect = { calls += "variant:$it" },
        )

        actions.onInputChange("hi")
        actions.onSend()
        actions.onInterrupt()
        actions.onAddFiles()
        actions.onRemoveAttachment("a")
        actions.onPickCommand("/x")
        actions.onAgentSelect("build")
        actions.onModelSelect("deepseek/v4")
        actions.onVariantSelect("low")

        assertEquals(
            listOf(
                "input:hi", "send", "interrupt", "addFiles", "remove:a",
                "pick:/x", "agent:build", "model:deepseek/v4", "variant:low",
            ),
            calls,
        )
    }

    @Test
    fun optionalCallbacksDefaultToNoOp() {
        val actions = ComposerActions(
            onInputChange = {},
            onSend = {},
            onInterrupt = {},
            onAddFiles = {},
            onRemoveAttachment = {},
            onPickCommand = {},
            onAgentSelect = {},
            onModelSelect = {},
            onVariantSelect = {},
        )

        // None of these may throw when the caller omits them.
        actions.onClearAttachments()
        actions.onBuiltinCommand("compact")
        actions.onToggleModel("provider", "model", true)
        actions.onToggleProvider("provider", listOf("model"), false)
    }
}
