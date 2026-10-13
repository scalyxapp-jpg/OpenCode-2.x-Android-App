package com.opencode.android.ui.race

/**
 * One model competing in a race.
 *
 * @param label display name from the catalog ("DeepSeek V4 Flash"), never a raw ref
 * @param modelRef `provider/model` as the app stores it
 * @param providerId resolved provider id
 * @param modelId resolved model id
 */
data class RaceMember(
    val label: String,
    val modelRef: String,
    val providerId: String,
    val modelId: String,
)

/** One member's live result inside [RaceState]. */
data class RaceMemberState(
    val member: RaceMember,
    val sessionId: String? = null,
    val running: Boolean = true,
    val failed: Boolean = false,
    val answer: String = "",
    val cost: Double? = null,
    val tokens: Long? = null,
    val elapsedMs: Long = 0L,
)

/** A running (or finished) race. */
data class RaceState(
    val prompt: String,
    val directory: String?,
    val startedAt: Long,
    val members: List<RaceMemberState>,
) {
    val finished: Boolean get() = members.isNotEmpty() && members.none { it.running }

    /** Cheapest finished member — the "value" winner of the race. */
    val cheapest: RaceMemberState?
        get() =
            members
                .filter { !it.running && !it.failed && it.cost != null }
                .minByOrNull { it.cost ?: Double.MAX_VALUE }

    /** Fastest finished member. */
    val fastest: RaceMemberState?
        get() =
            members
                .filter { !it.running && !it.failed }
                .minByOrNull { it.elapsedMs }
}

/**
 * Builds and validates a race from the composer's prompt and the picked models.
 *
 * Kept pure so the rules are unit-tested instead of living in the ViewModel:
 * a race needs a prompt and at least two DISTINCT models, and it is capped — every
 * member is a full turn on the user's own key, so an accidental 10-model race would
 * be an expensive surprise.
 */
object RacePlan {
    const val MIN_MODELS = 2
    const val MAX_MODELS = 3

    sealed interface Result {
        data class Ok(
            val members: List<RaceMember>,
        ) : Result

        data class Rejected(
            val reason: Reason,
        ) : Result

        enum class Reason {
            EMPTY_PROMPT,
            TOO_FEW_MODELS,
            UNKNOWN_MODEL,
        }
    }

    /**
     * @param prompt the composer text (the same prompt goes to every member)
     * @param modelRefs picked `provider/model` refs, in pick order
     * @param resolve maps a ref to its (label, providerId, modelId), or null when the
     *   catalog does not know it — an unknown model must not create a phantom session
     */
    fun build(
        prompt: String,
        modelRefs: List<String>,
        resolve: (String) -> Triple<String, String, String>?,
    ): Result {
        if (prompt.isBlank()) return Result.Rejected(Result.Reason.EMPTY_PROMPT)
        val distinct = modelRefs.distinct().take(MAX_MODELS)
        if (distinct.size < MIN_MODELS) return Result.Rejected(Result.Reason.TOO_FEW_MODELS)
        val members =
            distinct.map { ref ->
                val resolved =
                    resolve(ref) ?: return Result.Rejected(Result.Reason.UNKNOWN_MODEL)
                RaceMember(
                    label = resolved.first,
                    modelRef = ref,
                    providerId = resolved.second,
                    modelId = resolved.third,
                )
            }
        return Result.Ok(members)
    }
}
