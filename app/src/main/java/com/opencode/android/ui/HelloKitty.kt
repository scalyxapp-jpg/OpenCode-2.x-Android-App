package com.opencode.android.ui

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Hello Kitty debuted in 1974 — used as the layout seed so the backdrop is
// stable across recompositions but still feels hand-placed.
private const val KITTY_SEED = 1974

private data class Heart(
    var x: Float,
    var y: Float,
    val size: Float,
    val speed: Float,
    val phase: Float,
    val color: Int,
    val alpha: Float,
)

private data class Bow(
    var x: Float,
    var y: Float,
    val size: Float,
    val speed: Float,
    val phase: Float,
    val color: Int,
)

private data class Sparkle(
    val x: Float,
    val y: Float,
    val size: Float,
    val phase: Float,
    val speed: Float,
)

/**
 * Animated "kawaii drift" backdrop for the Hello Kitty theme: pastel hearts
 * float gently upward, the signature red bow tumbles slowly, and warm-yellow
 * sparkles twinkle behind them. Deliberately subtle (low alpha, slow) so
 * messages stay readable — wallpaper, not content.
 *
 * One canvas, one ~30 fps frame loop, no per-particle recomposition.
 */
@Composable
internal fun HelloKittyBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // ~30 fps: publish, then delay, so the backdrop does not wake on every
        // vsync (same throttle as Matrix/Aqua/Metal).
        while (true) {
            withFrameNanos { frameNs = it }
            kotlinx.coroutines.delay(33)
        }
    }

    // Bow red, blush pink, pale pink, plus the nose yellow for the sparkles.
    val heartColors =
        remember {
            intArrayOf(
                android.graphics.Color.parseColor("#E4002B"),
                android.graphics.Color.parseColor("#FF6B8A"),
                android.graphics.Color.parseColor("#F48FB1"),
                android.graphics.Color.parseColor("#FFD6DE"),
            )
        }
    val bowColors =
        remember {
            intArrayOf(
                android.graphics.Color.parseColor("#E4002B"),
                android.graphics.Color.parseColor("#FF6B8A"),
            )
        }
    val sparkleColors =
        remember {
            intArrayOf(
                android.graphics.Color.parseColor("#FFD206"),
                android.graphics.Color.parseColor("#FFFFFF"),
            )
        }
    val paint =
        remember {
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }
        }
    val heartPath = remember { Path() }
    val bowPath = remember { Path() }
    val sparklePath = remember { Path() }
    val hearts = remember { mutableListOf<Heart>() }
    val bows = remember { mutableListOf<Bow>() }
    val sparkles = remember { mutableListOf<Sparkle>() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val t = frameNs / 1_000_000_000f
        val maxAlpha = if (dark) 0.30f else 0.20f

        if (hearts.isEmpty()) {
            val rng = Random(KITTY_SEED)
            val count = ((w * h) / 240_000f).roundToInt().coerceIn(7, 20)
            repeat(count) {
                hearts +=
                    Heart(
                        x = rng.nextFloat() * w,
                        y = rng.nextFloat() * h,
                        size = 9f + rng.nextFloat() * 16f,
                        speed = 9f + rng.nextFloat() * 20f,
                        phase = rng.nextFloat() * (2f * PI.toFloat()),
                        color = heartColors[rng.nextInt(heartColors.size)],
                        alpha = 0.45f + rng.nextFloat() * 0.55f,
                    )
            }
            repeat((count / 3).coerceAtLeast(2)) {
                bows +=
                    Bow(
                        x = rng.nextFloat() * w,
                        y = rng.nextFloat() * h,
                        size = 11f + rng.nextFloat() * 12f,
                        speed = 7f + rng.nextFloat() * 14f,
                        phase = rng.nextFloat() * (2f * PI.toFloat()),
                        color = bowColors[rng.nextInt(bowColors.size)],
                    )
            }
            repeat((count / 2).coerceAtLeast(4)) {
                sparkles +=
                    Sparkle(
                        x = rng.nextFloat() * w,
                        y = rng.nextFloat() * h,
                        size = 3f + rng.nextFloat() * 5f,
                        phase = rng.nextFloat() * (2f * PI.toFloat()),
                        speed = 0.8f + rng.nextFloat() * 1.6f,
                    )
            }
        }

        // Sparkles first: they sit furthest back.
        for (sparkle in sparkles) {
            val twinkle = (sin(t * sparkle.speed + sparkle.phase) + 1f) / 2f
            paint.color = sparkleColors[(sparkle.phase * 10f).toInt() and 1]
            paint.alpha = (255 * maxAlpha * (0.15f + 0.85f * twinkle)).roundToInt().coerceIn(0, 255)
            buildSparkle(sparklePath, sparkle.x, sparkle.y, sparkle.size, t * 0.6f + sparkle.phase)
            drawContext.canvas.nativeCanvas.drawPath(sparklePath, paint)
        }

        // Hearts drift upward with a gentle side-to-side sway.
        for (heart in hearts) {
            heart.y -= heart.speed / 60f
            if (heart.y < -heart.size * 2f) {
                heart.y = h + heart.size * 2f
                heart.x = Random.nextFloat() * w
            }
            val sway = sin(t * 0.9f + heart.phase) * heart.size * 0.5f
            paint.color = heart.color
            paint.alpha = (255 * maxAlpha * heart.alpha).roundToInt().coerceIn(0, 255)
            buildHeart(heartPath, heart.x + sway, heart.y, heart.size)
            drawContext.canvas.nativeCanvas.drawPath(heartPath, paint)
        }

        // The signature bow, tumbling slowly as it rises.
        for (bow in bows) {
            bow.y -= bow.speed / 60f
            if (bow.y < -bow.size * 2f) {
                bow.y = h + bow.size * 2f
                bow.x = Random.nextFloat() * w
            }
            val tilt = sin(t * 0.7f + bow.phase) * 0.35f
            paint.color = bow.color
            paint.alpha = (255 * maxAlpha * 0.85f).roundToInt().coerceIn(0, 255)
            buildBow(bowPath, bow.x, bow.y, bow.size, tilt)
            drawContext.canvas.nativeCanvas.drawPath(bowPath, paint)
        }
    }
}

/** Two bezier lobes meeting at a bottom point. [s] is the half-width. */
private fun buildHeart(
    path: Path,
    cx: Float,
    cy: Float,
    s: Float,
) {
    path.reset()
    path.moveTo(cx, cy + s)
    path.cubicTo(cx - s * 1.5f, cy - s * 0.2f, cx - s * 0.6f, cy - s * 1.2f, cx, cy - s * 0.4f)
    path.cubicTo(cx + s * 0.6f, cy - s * 1.2f, cx + s * 1.5f, cy - s * 0.2f, cx, cy + s)
    path.close()
}

/**
 * The iconic bow: two leaf-shaped loops meeting at a round knot, rotated by
 * [tilt]. Coordinates are rotated by hand so the whole bow can be one path.
 */
private fun buildBow(
    path: Path,
    cx: Float,
    cy: Float,
    s: Float,
    tilt: Float,
) {
    val cosT = cos(tilt)
    val sinT = sin(tilt)

    fun rx(
        x: Float,
        y: Float,
    ) = cx + x * cosT - y * sinT

    fun ry(
        x: Float,
        y: Float,
    ) = cy + x * sinT + y * cosT
    path.reset()
    // Left loop: straight top edge, curved outer edge, straight bottom edge.
    path.moveTo(rx(0f, 0f), ry(0f, 0f))
    path.lineTo(rx(-s * 1.4f, -s * 0.85f), ry(-s * 1.4f, -s * 0.85f))
    path.quadTo(
        rx(-s * 1.85f, 0f),
        ry(-s * 1.85f, 0f),
        rx(-s * 1.4f, s * 0.85f),
        ry(-s * 1.4f, s * 0.85f),
    )
    path.close()
    // Right loop: mirror.
    path.moveTo(rx(0f, 0f), ry(0f, 0f))
    path.lineTo(rx(s * 1.4f, -s * 0.85f), ry(s * 1.4f, -s * 0.85f))
    path.quadTo(
        rx(s * 1.85f, 0f),
        ry(s * 1.85f, 0f),
        rx(s * 1.4f, s * 0.85f),
        ry(s * 1.4f, s * 0.85f),
    )
    path.close()
    // Knot.
    path.addCircle(cx, cy, s * 0.34f, Path.Direction.CW)
}

/** A four-point twinkle, rotated by [rot]. */
private fun buildSparkle(
    path: Path,
    cx: Float,
    cy: Float,
    r: Float,
    rot: Float,
) {
    path.reset()
    val points = 4
    for (i in 0 until points * 2) {
        val radius = if (i % 2 == 0) r else r * 0.32f
        val angle = rot + i * PI.toFloat() / points
        val x = cx + cos(angle) * radius
        val y = cy + sin(angle) * radius
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}
