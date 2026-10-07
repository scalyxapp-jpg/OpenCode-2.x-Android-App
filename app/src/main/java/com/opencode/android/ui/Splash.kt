package com.opencode.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opencode.android.R
import kotlinx.coroutines.delay

/**
 * Branded launch moment: logo tile pops in, the name staggers letter by
 * letter, an accent bar sweeps underneath — then the whole thing fades.
 * Theme-aware (primary tile, themed text), so Default/Matrix/Aqua/Metal each
 * get their own flavour for free.
 *
 * @param onDone Called after the exit animation finishes.
 */
@Composable
internal fun SplashOverlay(onDone: () -> Unit) {
    // Controls whether the splash is currently visible.
    var splashVisible by remember { mutableStateOf(true) }
    // After a delay, hide the splash (trigger exit animation).
    LaunchedEffect(Unit) {
        delay(2_000) // Total splash duration: 2 seconds.
        splashVisible = false
    }

    // Infinite breathe & sweep animations (run while visible).
    val breathe = rememberInfiniteTransition(label = "splashBreathe")
    val breatheScale by breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1_400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "splashBreatheScale",
    )
    val sweep by breathe.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1_100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "splashSweep",
    )

    // Popup scale for the logo tile (scale+fade pop-in).
    val pop =
        animateFloatAsState(
            targetValue = if (splashVisible) 1f else 0.6f,
            animationSpec = tween(450, easing = FastOutSlowInEasing),
            label = "splashPop",
        )

    if (splashVisible) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Logo tile: scale+fade pop, then a gentle idle breathe.
                Box(
                    modifier =
                        Modifier
                            .size(96.dp)
                            .scale(pop.value * breatheScale)
                            .graphicsLayer { alpha = pop.value }
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.size(64.dp),
                    )
                }
                // Wordmark: letters stagger in from below.
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    "OpenCode".forEachIndexed { i, ch ->
                        AnimatedVisibility(
                            visible = splashVisible,
                            enter =
                                fadeIn(tween(220, delayMillis = 150 + i * 55)) +
                                    slideInVertically(tween(220, delayMillis = 150 + i * 55)) { it / 2 },
                        ) {
                            Text(
                                text = ch.toString(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
                // Accent sweep bar, then a shimmer while the app settles.
                Box(
                    modifier =
                        Modifier
                            .height(4.dp)
                            .width(120.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(48.dp)
                                .height(4.dp)
                                .graphicsLayer {
                                    translationX = sweep * (120.dp.toPx() - 48.dp.toPx())
                                }.clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}
