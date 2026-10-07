package com.opencode.android.ui

import android.graphics.Paint
import android.graphics.Typeface
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
import kotlin.math.roundToInt
import kotlin.random.Random

// Katakana + digits + a few latin letters, like the film wall.
private const val GLYPHS = "アカサタナハマヤラワ0123456789ZTHSXM"

private data class Drop(
    val xFrac: Float,
    var y: Float,
    var speed: Float,
    var len: Int,
    val seed: Int,
)

/**
 * Animated "digital rain" backdrop for the Matrix theme: dim green glyph
 * columns falling behind the chat. Deliberately subtle (low alpha, slow)
 * so messages stay readable — it is wallpaper, not content.
 *
 * One canvas, one frame loop (~30 fps), no per-glyph recomposition: the
 * frame clock is the only state.
 */
@Composable
internal fun MatrixRainBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // ~30 fps: publish, then delay, so the effect does not schedule a frame
        // on EVERY vsync (the old throttle discarded values but still woke the
        // chassis 60-120x/s, draining battery whenever the theme was active).
        while (true) {
            withFrameNanos { frameNs = it }
            kotlinx.coroutines.delay(33)
        }
    }
    // Film colours: near-white head (#CCFFCC), phosphor mid, deep-green
    // tail dissolving into pure black — never grey.
    val headColor = android.graphics.Color.parseColor("#CCFFCC")
    val tailColor = android.graphics.Color.parseColor("#008F11")
    val paint =
        remember {
            Paint().apply {
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
                textAlign = Paint.Align.CENTER
            }
        }
    // Drops scale with the canvas size, so rotation/foldables just work.
    val drops = remember { mutableListOf<Drop>() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val cell = (size.minDimension / 34f).coerceIn(18f, 44f)
        paint.textSize = cell
        val wantCols = (w / cell).roundToInt().coerceIn(8, 64)
        val t = frameNs / 1_000_000_000f
        if (drops.size != wantCols) {
            drops.clear()
            val rng = Random(MATRIX_SEED)
            repeat(wantCols) { i ->
                drops +=
                    Drop(
                        xFrac = (i + 0.5f) / wantCols,
                        y = rng.nextFloat() * h,
                        speed = cell * rng.nextFloat().let { 1.5f + it * 4f },
                        len = 6 + rng.nextInt(14),
                        seed = rng.nextInt(1_000),
                    )
            }
        }
        val maxAlpha = if (dark) 0.22f else 0.14f
        for ((col, d) in drops.withIndex()) {
            d.y += d.speed / 60f
            val trail = d.len * cell
            if (d.y - trail > h + cell) {
                d.y = -cell * Random.nextFloat() * 4f
                d.speed = cell * (1.5f + Random.nextFloat() * 4f)
                d.len = 6 + Random.nextInt(14)
            }
            val x = d.xFrac * w
            val step = (t * d.speed / cell).roundToInt()
            for (row in 0 until d.len) {
                val gy = d.y - row * cell
                if (gy < -cell || gy > h + cell) continue
                // Head bright, tail dissolving into the background.
                val fade = 1f - row / d.len.toFloat()
                val ch = GLYPHS[(row * 7 + col * 13 + d.seed + step) % GLYPHS.length]
                paint.color = if (row == 0) headColor else tailColor
                paint.alpha = (255 * maxAlpha * fade * fade).roundToInt().coerceIn(0, 255)
                drawContext.canvas.nativeCanvas.drawText(ch.toString(), x, gy, paint)
            }
        }
    }
}

private const val MATRIX_SEED = 1337
