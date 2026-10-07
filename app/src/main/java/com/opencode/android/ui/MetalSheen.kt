package com.opencode.android.ui

import android.graphics.Paint
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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated brushed-metal backdrop for the Metal theme: fine diagonal brush
 * strokes with a soft highlight sheen sweeping slowly across them. Derived
 * from the app icon (charcoal + silver), so the accent stays achromatic.
 *
 * Deliberately subtle (low alpha, slow) so messages stay readable — it is
 * wallpaper, not content. One canvas, one ~30 fps frame loop.
 */
@Composable
internal fun MetalSheenBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    var frameNs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        // ~30 fps: publish then delay, so the backdrop does not schedule a frame
        // on every vsync.
        while (true) {
            withFrameNanos { frameNs = it }
            kotlinx.coroutines.delay(33)
        }
    }
    // Icon palette: silver highlight over a charcoal void. Light mode uses a
    // darker steel so the strokes read against a pale surface.
    val brushColor = if (dark) 0xFFC9D2DA.toInt() else 0xFF4E5866.toInt()
    val paint =
        remember {
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
            }
        }
    val sheenPaint =
        remember {
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
            }
        }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val diagonal = kotlin.math.sqrt(w * w + h * h)
        // Brush spacing scales with the viewport so folds/rotation just work.
        val spacing = (diagonal / 90f).coerceIn(4f, 14f)
        paint.strokeWidth = 1f
        paint.color = brushColor
        val t = frameNs / 1_000_000_000f
        // Sheen travels along the brush-normal over a ~14 s cycle.
        val phase = (sin(t * 2.0 * Math.PI / 14.0).toFloat() + 1f) / 2f
        val sheenCenter = phase * (diagonal * 1.4f) - diagonal * 0.2f
        val sheenWidth = diagonal * 0.16f

        // 45° brush strokes. Coordinates run along the brush axis; the
        // perpendicular offset is what the sheen brightens.
        val nx = cos(Math.PI / 4).toFloat()
        val ny = sin(Math.PI / 4).toFloat()
        val steps = (diagonal * 1.4f / spacing).toInt().coerceIn(1, 600)
        for (i in 0..steps) {
            val offset = i * spacing - diagonal * 0.2f
            // Start far enough outside the canvas that the 45° line covers it.
            val startX = -diagonal + nx * offset
            val startY = -diagonal + ny * offset
            val endX = diagonal + nx * offset
            val endY = diagonal + ny * offset
            val dist = kotlin.math.abs(offset - sheenCenter)
            val sheen = (1f - dist / sheenWidth).coerceIn(0f, 1f)
            // Base strokes barely visible; the sheen adds a soft metallic glow.
            val baseAlpha = if (dark) 0.05f else 0.05f
            paint.alpha = (255 * baseAlpha).toInt()
            drawContext.canvas.nativeCanvas.drawLine(startX, startY, endX, endY, paint)
            if (sheen > 0f) {
                sheenPaint.strokeWidth = spacing * 0.6f
                sheenPaint.color = brushColor
                sheenPaint.alpha = (255 * (if (dark) 0.10f else 0.09f) * sheen).toInt().coerceIn(0, 255)
                drawContext.canvas.nativeCanvas.drawLine(startX, startY, endX, endY, sheenPaint)
            }
        }
    }
}
