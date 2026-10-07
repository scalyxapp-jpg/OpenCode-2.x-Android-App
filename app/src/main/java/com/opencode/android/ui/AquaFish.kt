package com.opencode.android.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private data class Fish(
    var x: Float,
    var y: Float,
    var speed: Float,
    var size: Float,
    var dir: Int,
    var phase: Float,
    val color: Int,
)

private data class Bubble(
    var x: Float,
    var y: Float,
    var radius: Float,
    var speed: Float,
)

private const val AQUA_SEED = 4242

/**
 * Keeps a fish vertically inside the canvas, clamped against the fish radius.
 *
 * Pure and separate because the naive `value.coerceIn(size, h - size)` throws
 * `IllegalArgumentException: Cannot coerce value to an empty range` whenever
 * the canvas is shorter than twice a fish (small window, IME open, first
 * layout frame) — a crash observed in the client reports.
 */
internal fun clampFishY(
    value: Float,
    size: Float,
    h: Float,
): Float {
    val minY = size
    val maxY = (h - size).coerceAtLeast(minY)
    return value.coerceIn(minY, maxY)
}

/**
 * Animated fish backdrop for the Aqua theme: small fish swimming behind the
 * chat plus a few rising bubbles. Deliberately subtle (low alpha, slow) so
 * messages stay readable — wallpaper, not content.
 *
 * One canvas, one ~30 fps frame loop, no per-fish recomposition.
 */
@Composable
internal fun AquaFishBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // ~30 fps (see MatrixRain): delay between frames so the effect does not
        // wake on every vsync.
        while (true) {
            withFrameNanos { frameNs = it }
            kotlinx.coroutines.delay(33)
        }
    }

    val fishColors =
        remember {
            intArrayOf(
                android.graphics.Color.parseColor("#BDE9FA"),
                android.graphics.Color.parseColor("#8FD8F2"),
                android.graphics.Color.parseColor("#5CC3E6"),
                android.graphics.Color.parseColor("#29A3D8"),
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
    val tailPath = remember { Path() }
    val rect = remember { RectF() }
    val fishes = remember { mutableListOf<Fish>() }
    val bubbles = remember { mutableListOf<Bubble>() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val t = frameNs / 1_000_000_000f
        val maxAlpha = if (dark) 0.30f else 0.20f

        if (fishes.isEmpty()) {
            val rng = Random(AQUA_SEED)
            val count = ((w * h) / 260_000f).roundToInt().coerceIn(6, 18)
            repeat(count) {
                fishes +=
                    Fish(
                        x = rng.nextFloat() * w,
                        y = rng.nextFloat() * h,
                        speed = 14f + rng.nextFloat() * 26f,
                        size = 10f + rng.nextFloat() * 14f,
                        dir = if (rng.nextBoolean()) 1 else -1,
                        phase = rng.nextFloat() * (2f * PI.toFloat()),
                        color = fishColors[rng.nextInt(fishColors.size)],
                    )
            }
            repeat((count / 2).coerceAtLeast(3)) {
                bubbles +=
                    Bubble(
                        x = rng.nextFloat() * w,
                        y = rng.nextFloat() * h,
                        radius = 1.5f + rng.nextFloat() * 3f,
                        speed = 8f + rng.nextFloat() * 14f,
                    )
            }
        }

        // Bubbles first: they sit behind the fish.
        paint.color = fishColors[0]
        for (bubble in bubbles) {
            bubble.y -= bubble.speed / 60f
            if (bubble.y < -bubble.radius * 2f) {
                bubble.y = h + bubble.radius * 2f
                bubble.x = Random.nextFloat() * w
            }
            paint.alpha = (255 * maxAlpha * 0.5f).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawCircle(
                bubble.x,
                bubble.y,
                bubble.radius,
                paint,
            )
        }

        for (fish in fishes) {
            fish.x += fish.speed * fish.dir / 60f
            val bob = sin(t * 1.4f + fish.phase) * fish.size * 0.35f
            val y = clampFishY(fish.y + bob, fish.size, h)
            val halfW = fish.size * 0.85f
            val halfH = fish.size * 0.45f
            val left = if (fish.dir > 0) fish.x - halfW else fish.x - halfW
            rect.set(left, y - halfH, left + halfW * 2f, y + halfH)

            // Body.
            paint.color = fish.color
            paint.alpha = (255 * maxAlpha).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawOval(rect, paint)

            // Tail triangle behind the body.
            val tailX = if (fish.dir > 0) left else left + halfW * 2f
            tailPath.reset()
            tailPath.moveTo(tailX, y)
            tailPath.lineTo(tailX - fish.dir * fish.size * 0.7f, y - fish.size * 0.5f)
            tailPath.lineTo(tailX - fish.dir * fish.size * 0.7f, y + fish.size * 0.5f)
            tailPath.close()
            paint.alpha = (255 * maxAlpha * 0.85f).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawPath(tailPath, paint)

            // Eye.
            val eyeX = fish.x + fish.dir * fish.size * 0.45f
            paint.color = android.graphics.Color.WHITE
            paint.alpha = (255 * maxAlpha * 1.4f).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawCircle(eyeX, y - fish.size * 0.12f, fish.size * 0.10f, paint)
            paint.color = android.graphics.Color.parseColor("#06121F")
            paint.alpha = (255 * maxAlpha * 1.6f).roundToInt().coerceIn(0, 255)
            drawContext.canvas.nativeCanvas.drawCircle(eyeX, y - fish.size * 0.12f, fish.size * 0.045f, paint)

            // Wrap at the edges; randomise the lane so it does not repeat.
            if (fish.dir > 0 && fish.x - halfW > w) {
                fish.x = -halfW
                fish.y = Random.nextFloat() * h
            } else if (fish.dir < 0 && fish.x + halfW < 0f) {
                fish.x = w + halfW
                fish.y = Random.nextFloat() * h
            }
        }
    }
}
