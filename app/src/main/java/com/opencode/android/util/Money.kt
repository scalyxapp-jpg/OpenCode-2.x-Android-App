package com.opencode.android.util

import java.util.Locale

/**
 * The ONE money formatter.
 *
 * Cost used to be formatted in two places (`GlanceStore.cost` and the Centre), which is how
 * two surfaces end up showing the same number differently. Both use this now.
 */
object Money {
    /** `$1.23` — the shape every surface shows (the server reports USD). */
    fun usd(amount: Double?): String? =
        amount?.let { "$" + String.format(Locale.ENGLISH, "%.2f", it) }

    /**
     * `+45% vs. 7-day average` — a change against a baseline.
     *
     * `null` when either side is unknown or the baseline is zero: a percentage against zero is
     * noise, not information.
     */
    fun trend(
        value: Double?,
        baseline: Double?,
        baselineLabel: String,
    ): String? {
        if (value == null || baseline == null || baseline <= 0.0) return null
        // Round, not truncate: 44.999… must read "+45%", and truncation understated every
        // increase by one point (found by the test).
        val percent = Math.round((value - baseline) / baseline * 100.0).toInt()
        val sign = if (percent > 0) "+" else ""
        return "$sign$percent% vs. $baselineLabel"
    }
}
