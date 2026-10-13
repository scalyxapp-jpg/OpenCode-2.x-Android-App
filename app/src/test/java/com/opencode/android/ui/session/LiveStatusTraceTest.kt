package com.opencode.android.ui.session

import com.opencode.android.domain.Model
import com.opencode.android.ui.LiveStatusKind
import com.opencode.android.ui.liveStatus
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIAGNOSTIC: real wire trace of one full turn on OpenCode 2.0.26, replayed
 * through normaliser + filter + reducer.
 */
class LiveStatusTraceTest {
    private val models =
        listOf(Model(id = "deepseek-v4-flash", name = "V4 Flash", providerId = "deepseek"))

    private val sid = "ses_eca3f1001ffe4b3bs5OWybg8sQ"

    private val trace =
        listOf(
            "{\"id\":\"evt_135c0f00d001pT3NpIToIinM4y\",\"created\":1791903199245,\"type\":\"session.created\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"slug\":\"brave-island\",\"version\":\"2.0.26\",\"projectID\":\"8e7bbe1020e8c2398c57debd6951a6c9df1125b4\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"subpath\":\"\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":0,\"version\":1}}",
            "{\"id\":\"evt_135c0f017001OZYmdIkST3Ux48\",\"created\":1791903199255,\"type\":\"session.inbox.enqueued\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"inboxID\":\"msg_135c0f015001AVIUsiyIN54muG\",\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"item\":{\"type\":\"user\",\"payload\":{\"text\":\"Run the shell tool with the command `echo hallo` and then answer with exactly one sentence.\"},\"delivery\":\"steer\"}},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":1,\"version\":1}}",
            "{\"id\":\"evt_135c0f01a001qBVw0ccnKggTzo\",\"created\":1791903199258,\"type\":\"session.execution.started\",\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":2,\"version\":1}}",
            "{\"id\":\"evt_135c0f02a001nI8QnTqeEdsnY7\",\"created\":1791903199274,\"metadata\":{\"instructions\":{\"initial\":true}},\"type\":\"session.instructions.updated\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"delta\":{\"core/codemode\":\"f3141c6ef32b62b2425a008b751e03cb9cfa238200c6d446b06b3bddf67b0b68\",\"core/mcp-guidance\":\"47a7da67bb709ded10ed2fe6fcab284a21ac59fd99f6e1f61dcb7cd4a5412edc\",\"core/reference-guidance\":\"44ca5e5203c826d728e441c4d4458801af3af133fb962b05f43a4ece33f38355\",\"core/skill-guidance\":\"c3f5630ceb4bde3adb72e5a080a5a1cebcf2f5214722f3343344cacde1eb3ebb\",\"core/instructions\":\"30c3f36eb9d9ec98f0cf5116ea6cd79acdca81a1cbb868300f2c12527570709d\",\"core/date\":\"81c205a6214c44f0249bc63dae25defe7c5f8a0c09b957cc8b0f4e5983dacc26\",\"core/environment\":\"ad461410cc3322a8984151abd221c347821dcd7aa9bd955c5dc7b9930fd5f9b0\"}},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":3,\"version\":2}}",
            "{\"id\":\"evt_135c0f02e00127omWgngIu5pqH\",\"created\":1791903199278,\"type\":\"session.inbox.delivered\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"inboxID\":\"msg_135c0f015001AVIUsiyIN54muG\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":4,\"version\":1}}",
            "{\"id\":\"evt_135c0f46e001cjIEfxVHYVkQdr\",\"created\":1791903200366,\"type\":\"session.step.started\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"agent\":\"build\",\"model\":{\"id\":\"deepseek-v4-flash\",\"providerID\":\"deepseek\"},\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"snapshot\":\"42125b8895436471b8780d2ede326abd9371c566\",\"started\":1791903199320},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":5,\"version\":1}}",
            "{\"id\":\"evt_135c0f472001YDI0nVp681HvI6\",\"created\":1791903200370,\"type\":\"session.reasoning.started\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"ordinal\":0,\"state\":{\"reasoningField\":\"reasoning_content\"}},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":6,\"version\":1}}",
            "{\"id\":\"evt_135c0f4d9001wQDb73DgRX8qn9\",\"created\":1791903200473,\"type\":\"session.reasoning.delta\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"ordinal\":0,\"delta\":\"Simple task. Run shell tool.\"}}",
            "{\"id\":\"evt_135c0f4da001urNWr4t5paxkjc\",\"created\":1791903200474,\"type\":\"session.tool.input.started\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"id\":\"call_00_tg6pjoDlKWBCHDLvkCmg4531\",\"name\":\"shell\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":7,\"version\":1}}",
            "{\"id\":\"evt_135c0f52b001dMx0vBd7dkweW0\",\"created\":1791903200556,\"type\":\"session.reasoning.ended\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"ordinal\":0,\"text\":\"Simple task. Run shell tool.\",\"state\":{\"reasoningField\":\"reasoning_content\"}},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":8,\"version\":1}}",
            "{\"id\":\"evt_135c0f530001so30d7kGkrLp3j\",\"created\":1791903200560,\"type\":\"session.tool.input.ended\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"id\":\"call_00_tg6pjoDlKWBCHDLvkCmg4531\",\"text\":\"{\\\"command\\\": \\\"echo hallo\\\"}\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":9,\"version\":1}}",
            "{\"id\":\"evt_135c0f533001grJr6UtVL3Cga7\",\"created\":1791903200563,\"type\":\"session.tool.called\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"id\":\"call_00_tg6pjoDlKWBCHDLvkCmg4531\",\"input\":{\"command\":\"echo hallo\"},\"executed\":false},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":10,\"version\":1}}",
            "{\"id\":\"evt_135c0f536001AnA67GFK36ruJ6\",\"created\":1791903200566,\"type\":\"session.step.streamed\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":11,\"version\":1}}",
            "{\"id\":\"evt_135c0f53f00143zO95Wl69nbhl\",\"created\":1791903200575,\"type\":\"shell.created\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"info\":{\"id\":\"sh_135c0f53d001nvINTZktNWAaS3\",\"status\":\"running\",\"command\":\"echo hallo\",\"cwd\":\"/home/scalyxsoft/Documents/coding/opencode-android\",\"shell\":\"/bin/bash\",\"file\":\"/home/scalyxsoft/.local/share/opencode/shell/8e7bbe1020e8c2398c57debd6951a6c9df1125b4/sh_135c0f53d001nvINTZktNWAaS3.out\",\"metadata\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\"},\"time\":{\"started\":1791903200573}}}}",
            "{\"id\":\"evt_135c0f540001xiTp2QMQKzD2k0\",\"created\":1791903200576,\"type\":\"session.tool.progress\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"id\":\"call_00_tg6pjoDlKWBCHDLvkCmg4531\",\"metadata\":{\"shellID\":\"sh_135c0f53d001nvINTZktNWAaS3\"}}}",
            "{\"id\":\"evt_135c0f5440012OuMKFxK8KE0ID\",\"created\":1791903200580,\"type\":\"session.tool.success\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"id\":\"call_00_tg6pjoDlKWBCHDLvkCmg4531\",\"content\":[{\"type\":\"text\",\"text\":\"hallo\\n\"}],\"metadata\":{\"status\":\"completed\",\"truncated\":false,\"exit\":0},\"executed\":false},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":12,\"version\":2}}",
            "{\"id\":\"evt_135c0f54e001b1R5CL065MvnHm\",\"created\":1791903200591,\"type\":\"session.step.ended\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f043001N4Suoxazc5cHtN\",\"finish\":\"tool-calls\",\"rawFinish\":\"tool_calls\",\"cost\":0.000314944,\"tokens\":{\"input\":198,\"output\":39,\"reasoning\":7,\"cache\":{\"read\":34432,\"write\":0}},\"snapshot\":\"42125b8895436471b8780d2ede326abd9371c566\",\"files\":[]},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":13,\"version\":1}}",
            "{\"id\":\"evt_135c0f551001x33mgCtGTdk2ZI\",\"created\":1791903200593,\"type\":\"session.usage.updated\",\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"cost\":0.000314944,\"tokens\":{\"input\":198,\"output\":39,\"reasoning\":7,\"cache\":{\"read\":34432,\"write\":0}}}}",
            "{\"id\":\"evt_135c0f6b3001wgGxNxmZT50g3I\",\"created\":1791903200947,\"type\":\"session.usage.updated\",\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"cost\":0.000440392,\"tokens\":{\"input\":378,\"output\":46,\"reasoning\":126,\"cache\":{\"read\":34816,\"write\":0}}}}",
            "{\"id\":\"evt_135c0f6b40011tvg28eF6FNinO\",\"created\":1791903200948,\"type\":\"session.renamed\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"title\":\"Executing echo hallo command\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":15,\"version\":1}}",
            "{\"id\":\"evt_135c0fa2b001Ky75bPEErYoA5n\",\"created\":1791903201835,\"type\":\"session.step.started\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"agent\":\"build\",\"model\":{\"id\":\"deepseek-v4-flash\",\"providerID\":\"deepseek\"},\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\",\"snapshot\":\"42125b8895436471b8780d2ede326abd9371c566\",\"started\":1791903200635},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":16,\"version\":1}}",
            "{\"id\":\"evt_135c0fa31001jUinUdEQOUlIr6\",\"created\":1791903201841,\"type\":\"session.text.started\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\",\"ordinal\":0},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":17,\"version\":1}}",
            "{\"id\":\"evt_135c0fa7c001B2u1aTCWnDhd9d\",\"created\":1791903201916,\"type\":\"session.text.delta\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\",\"ordinal\":0,\"delta\":\"The shell command `echo hallo` executed successfully and printed \\\"hallo\\\".\"}}",
            "{\"id\":\"evt_135c0fa7e0017gIUidlnUhrKyd\",\"created\":1791903201918,\"type\":\"session.text.ended\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\",\"ordinal\":0,\"text\":\"The shell command `echo hallo` executed successfully and printed \\\"hallo\\\".\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":18,\"version\":1}}",
            "{\"id\":\"evt_135c0fa82001UZqtT71fp3q3wv\",\"created\":1791903201922,\"type\":\"session.step.streamed\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":19,\"version\":1}}",
            "{\"id\":\"evt_135c0fa8e001Ol6C0qo2Aj0HVG\",\"created\":1791903201934,\"type\":\"session.step.ended\",\"location\":{\"directory\":\"/home/scalyxsoft/Documents/coding/opencode-android\"},\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"assistantMessageID\":\"msg_135c0f564001zMJ23wfOKOrXHk\",\"finish\":\"stop\",\"rawFinish\":\"stop\",\"cost\":0.000309224,\"tokens\":{\"input\":259,\"output\":17,\"reasoning\":0,\"cache\":{\"read\":34432,\"write\":0}},\"snapshot\":\"42125b8895436471b8780d2ede326abd9371c566\",\"files\":[]},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":20,\"version\":1}}",
            "{\"id\":\"evt_135c0fa91001sPAzXwQsMedd8e\",\"created\":1791903201937,\"type\":\"session.usage.updated\",\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"cost\":0.000749616,\"tokens\":{\"input\":637,\"output\":63,\"reasoning\":126,\"cache\":{\"read\":69248,\"write\":0}}}}",
            "{\"id\":\"evt_135c0fa92001YfkI96JLNyyRNS\",\"created\":1791903201938,\"type\":\"session.execution.succeeded\",\"data\":{\"sessionID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\"},\"durable\":{\"aggregateID\":\"ses_eca3f1001ffe4b3bs5OWybg8sQ\",\"seq\":21,\"version\":1}}"
        )

    @Test
    fun `the real turn trace produces detailed statuses`() {
        var state = StreamState()
        val kinds = mutableListOf<LiveStatusKind>()
        val toolStatuses = mutableListOf<com.opencode.android.ui.LiveStatus>()
        val steps = mutableListOf<Int>()
        for (raw in trace) {
            val event = V2EventNormalizer.normalize(raw)
            if (event == null) {
                println("DROPPED (normalise) " + raw.take(120))
                continue
            }
            if (!SseFilter.shouldDeliver(event, sid)) {
                println(
                    "DROPPED (filter) " + event.type +
                        " evtSession=" + event.properties?.sessionId +
                        " dataSession=" + event.data?.sessionId +
                        " sid=" + sid,
                )
                continue
            }
            state = StreamReducer.reduce(state, event, models) { null }
            val status =
                liveStatus(
                    connected = true,
                    compacting = state.isCompacting,
                    thinking = state.live.thinking,
                    waiting =
                        state.live.response.isBlank() &&
                            state.live.reasoning.isBlank() &&
                            state.live.parts.isEmpty(),
                    parts = state.live.parts,
                    streamingChars = state.live.response.length,
                    step = state.live.stepIndex,
                )
            kinds += status.kind
            steps += status.step
            if (status.kind == LiveStatusKind.TOOL) toolStatuses += status
            println(
                "after " + (event.type ?: "?").padEnd(28) +
                    " parts=" + state.live.parts.size +
                    " resp=" + state.live.response.length +
                    " reason=" + state.live.reasoning.length +
                    " gen=" + state.isGenerating +
                    " step=" + state.live.stepIndex +
                    " -> " + status.kind + " " + (status.tool ?: "") + " " + (status.detail ?: ""),
            )
        }
        assertTrue("no frame reached the reducer", kinds.isNotEmpty())
        // Regression pins for the two defects this trace exposed:
        // 1) the tool NAME must survive the later frames of the same call
        //    (`called` carries only the input, so replacing the part lost it),
        assertTrue(
            "a TOOL status without a tool name: " + kinds,
            toolStatuses.none { it.tool.isNullOrBlank() },
        )
        // 2) the step counter must grow within one turn, so the status row can
        //    name the running step instead of a bare "waiting" line.
        assertTrue("step counter never advanced: " + steps, (steps.maxOrNull() ?: 0) > 1)
        assertTrue("tool status never appeared", kinds.contains(LiveStatusKind.TOOL))
    }
}
