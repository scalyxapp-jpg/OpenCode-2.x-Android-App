package com.opencode.android.util

import java.util.Locale

/**
 * Formats a session cost for display.
 *
 * A raw `"$${cost}"` produced values like "$2.1897245279999997"; this rounds to
 * cents and calls out sub-cent amounts instead of showing "$0.00".
 */
fun formatCost(cost: Double): String =
    if (cost < 0.01) {
        "<$0.01"
    } else {
        "$" + String.format(Locale.ENGLISH, "%.2f", cost)
    }
