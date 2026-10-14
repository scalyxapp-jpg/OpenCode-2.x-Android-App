package com.opencode.android.data

/**
 * The ambient glance state: what the widget, the ongoing notification and the Quick
 * Settings tile show without opening the app.
 *
 * All three surfaces derive from ONE fact set so they can never disagree, and the
 * derivation is pure so the wording rules are unit-tested.
 */
enum class GlanceKind {
    /** Nothing running, nothing waiting. */
    IDLE,

    /** A turn is running (with or without a known step). */
    RUNNING,

    /** Something needs the human (pending permission/question). */
    WAITING,

    /** Running, but calls are failing. */
    STUCK,
}

/** Raw facts, merged from the live stream, the background watcher and the server. */
@kotlinx.serialization.Serializable
data class GlanceFacts(
    val sessionId: String? = null,
    val title: String? = null,
    val running: Boolean = false,
    val step: Int = 0,
    /** Wall-clock of the running turn (0 when idle). */
    val elapsedMs: Long = 0L,
    val toolName: String? = null,
    val pendingDecisions: Int = 0,
    val failures: Int = 0,
    val costTodayUsd: Double? = null,
    val updatedAt: Long = 0L,
)

object GlanceStatus {
    /**
     * Precedence: waiting for the human wins over running (that is the thing you must
     * act on), then failures, then running, else idle.
     */
    fun kind(facts: GlanceFacts): GlanceKind =
        when {
            facts.pendingDecisions > 0 -> GlanceKind.WAITING
            facts.running && facts.failures > 0 -> GlanceKind.STUCK
            facts.running -> GlanceKind.RUNNING
            facts.failures > 0 -> GlanceKind.STUCK
            else -> GlanceKind.IDLE
        }

    /**
     * The progress part, e.g. `Step 7 · 42 s` or `Shell · 42 s`.
     *
     * Kept locale-neutral (numbers + unit) — the labels around it come from the
     * widget/notification so the surfaces stay translatable.
     */
    fun progress(facts: GlanceFacts): String? {
        val parts = mutableListOf<String>()
        if (facts.step > 0) parts += "Step ${facts.step}"
        facts.toolName?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (facts.running && facts.elapsedMs > 0) parts += "${facts.elapsedMs / 1000}s"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Cost of the day, e.g. `$3.06` — null when unknown (never guessed). */
    fun cost(costTodayUsd: Double?): String? = com.opencode.android.util.Money.usd(costTodayUsd)

    /**
     * True when two fact sets would render the same surface.
     *
     * The elapsed seconds are compared in [BUCKET_MS] steps on purpose: the surfaces DO
     * show seconds, but refreshing them once per second would be exactly the timer-driven
     * wake-up Android throttles. So the state advances in 10-second steps while running —
     * the line stays live, the wake-ups stay rare.
     */
    fun sameSurface(
        a: GlanceFacts,
        b: GlanceFacts,
    ): Boolean =
        kind(a) == kind(b) &&
            progress(bucketed(a)) == progress(bucketed(b)) &&
            cost(a.costTodayUsd) == cost(b.costTodayUsd) &&
            a.pendingDecisions == b.pendingDecisions &&
            a.sessionId == b.sessionId

    /** Elapsed time rounded down to [BUCKET_MS] (see [sameSurface]). */
    internal fun bucketed(facts: GlanceFacts): GlanceFacts =
        facts.copy(elapsedMs = (facts.elapsedMs / BUCKET_MS) * BUCKET_MS)

    /** 10 s: visible progress without a per-second refresh. */
    internal const val BUCKET_MS = 10_000L
}

/**
 * Persists the glance facts and refreshes the surfaces when the *displayed* state
 * changes.
 *
 * Why a persisted snapshot: widgets, notifications and tiles render in other
 * processes/entry points, and Android throttles background wake-ups. So the app never
 * polls a timer for the UI — every writer updates this store and the store only pushes
 * a refresh when the derived line actually changed (see [GlanceStatus.sameSurface]).
 */
object GlanceStore {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(GlanceFacts())
    val state: kotlinx.coroutines.flow.StateFlow<GlanceFacts> = _state

    /** Set once by the app: pushes a refresh into widget + ongoing notification. */
    @Volatile
    var onSurfaceChanged: ((GlanceFacts) -> Unit)? = null

    fun facts(): GlanceFacts = _state.value

    /**
     * Forgets the ambient state (setting turned off).
     *
     * Otherwise the widget kept showing the last "Step 7 · 42s" forever — a stale surface
     * is worse than a static one. The persisted snapshot is cleared with it so the widget
     * process sees the reset.
     */
    fun reset() {
        val cleared = GlanceFacts(updatedAt = System.currentTimeMillis())
        _state.value = cleared
        onSurfaceChanged?.invoke(cleared)
    }

    /**
     * Merges new facts into the snapshot.
     *
     * @param clearRunning true when a turn ended: clears step/tool/elapsed and the
     *   failure counter of that turn, so a finished turn cannot look stuck.
     */
    fun update(
        sessionId: String? = null,
        title: String? = null,
        running: Boolean? = null,
        step: Int? = null,
        elapsedMs: Long? = null,
        toolName: String? = null,
        pendingDecisions: Int? = null,
        failures: Int? = null,
        costTodayUsd: Double? = null,
        clearRunning: Boolean = false,
    ) {
        val previous = _state.value
        val next =
            previous.copy(
                sessionId = sessionId ?: previous.sessionId,
                title = title ?: previous.title,
                running = running ?: previous.running,
                step = step ?: previous.step,
                elapsedMs = elapsedMs ?: previous.elapsedMs,
                toolName = toolName ?: previous.toolName,
                pendingDecisions = pendingDecisions ?: previous.pendingDecisions,
                failures = failures ?: previous.failures,
                costTodayUsd = costTodayUsd ?: previous.costTodayUsd,
                updatedAt = System.currentTimeMillis(),
            )
        val cleared =
            if (clearRunning) {
                next.copy(
                    running = false,
                    step = 0,
                    elapsedMs = 0L,
                    toolName = null,
                    failures = 0,
                )
            } else {
                next
            }
        // Skip the state write when nothing but the timestamp changed: the live feed calls
        // this several times per second and an emission per flush is pure waste.
        if (previous.copy(updatedAt = 0L) != cleared.copy(updatedAt = 0L)) _state.value = cleared
        // Only a change of the DISPLAYED line costs a surface refresh.
        if (!GlanceStatus.sameSurface(previous, cleared)) onSurfaceChanged?.invoke(cleared)
    }
}
