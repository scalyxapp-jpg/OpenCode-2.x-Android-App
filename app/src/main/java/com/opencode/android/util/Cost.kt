package com.opencode.android.util

import java.util.Locale

/**
 * Formats a session cost for display.
 *
 * A raw `"$${cost}"` produced values like "$2.1897245279999997"; this rounds to
 * cents and calls out sub-cent amounts instead of showing "$0.00".
 *
 * Non-finite values (a missing/NaN field on the wire) and negatives (a delta or
 * a credit) used to print "$NaN" / "$Infinity" / a bogus "<$0.01"; they are now
 * handled explicitly.
 */
/**
 * Groups a large count for display ("1,287,634" instead of "1287634"): raw token
 * numbers are unreadable, and the app already formats costs.
 */
fun formatCount(value: Long): String = String.format(java.util.Locale.ENGLISH, "%,d", value)

fun formatCount(value: Int): String = formatCount(value.toLong())

fun formatCost(cost: Double): String =
    when {
        !cost.isFinite() -> "—"
        cost < 0.0 -> "-" + formatCost(-cost)
        cost < 0.01 -> "<$0.01"
        else -> "$" + String.format(Locale.ENGLISH, "%.2f", cost)
    }
