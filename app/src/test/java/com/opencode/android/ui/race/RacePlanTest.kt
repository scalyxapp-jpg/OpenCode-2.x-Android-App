package com.opencode.android.ui.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race rules are pure: a race needs the prompt and at least two DISTINCT models,
 * and it is capped because every member is a full turn on the user's own key.
 */
class RacePlanTest {
    private val catalog =
        mapOf(
            "deepseek/deepseek-flash" to Triple("DeepSeek Flash", "deepseek", "deepseek-flash"),
            "deepseek/deepseek-v4-pro" to Triple("V4 Pro", "deepseek", "deepseek-v4-pro"),
            "nvidia/x" to Triple("X", "nvidia", "x"),
            "nvidia/y" to Triple("Y", "nvidia", "y"),
        )

    private fun resolve(ref: String) = catalog[ref]

    @Test
    fun `a race needs a prompt`() {
        val result = RacePlan.build("   ", listOf("deepseek/deepseek-flash", "nvidia/x"), ::resolve)
        assertEquals(RacePlan.Result.Rejected(RacePlan.Result.Reason.EMPTY_PROMPT), result)
    }

    @Test
    fun `one model is not a race`() {
        val result = RacePlan.build("go", listOf("deepseek/deepseek-flash"), ::resolve)
        assertEquals(RacePlan.Result.Rejected(RacePlan.Result.Reason.TOO_FEW_MODELS), result)
    }

    @Test
    fun `the same model twice is not a race either`() {
        val result = RacePlan.build("go", listOf("nvidia/x", "nvidia/x"), ::resolve)
        assertEquals(RacePlan.Result.Rejected(RacePlan.Result.Reason.TOO_FEW_MODELS), result)
    }

    @Test
    fun `an unknown model is refused instead of creating a phantom session`() {
        val result = RacePlan.build("go", listOf("nvidia/x", "ghost/model"), ::resolve)
        assertEquals(RacePlan.Result.Rejected(RacePlan.Result.Reason.UNKNOWN_MODEL), result)
    }

    @Test
    fun `members carry display label and resolved ids`() {
        val result = RacePlan.build("go", listOf("deepseek/deepseek-flash", "nvidia/y"), ::resolve)
        assertTrue(result is RacePlan.Result.Ok)
        val members = (result as RacePlan.Result.Ok).members
        assertEquals(listOf("DeepSeek Flash", "Y"), members.map { it.label })
        assertEquals("deepseek", members.first().providerId)
        assertEquals("deepseek-flash", members.first().modelId)
    }

    @Test
    fun `the race is capped at three members`() {
        val result =
            RacePlan.build(
                "go",
                listOf("deepseek/deepseek-flash", "deepseek/deepseek-v4-pro", "nvidia/x", "nvidia/y"),
                ::resolve,
            )
        val members = (result as RacePlan.Result.Ok).members
        assertEquals(RacePlan.MAX_MODELS, members.size)
    }

    @Test
    fun `cheapest and fastest are picked from the finished members`() {
        val members =
            listOf(
                RaceMember("A", "p/a", "p", "a"),
                RaceMember("B", "p/b", "p", "b"),
                RaceMember("C", "p/c", "p", "c"),
            )
        val state =
            RaceState(
                prompt = "go",
                directory = null,
                startedAt = 0L,
                members =
                    listOf(
                        RaceMemberState(members[0], running = false, cost = 0.05, elapsedMs = 9_000),
                        RaceMemberState(members[1], running = false, cost = 0.01, elapsedMs = 4_000),
                        RaceMemberState(members[2], running = true, cost = 0.001, elapsedMs = 1_000),
                    ),
            )
        assertEquals("p/b", state.cheapest?.member?.modelRef)
        assertEquals("p/b", state.fastest?.member?.modelRef)
        assertTrue("a running member is not finished", !state.finished)
    }
}
