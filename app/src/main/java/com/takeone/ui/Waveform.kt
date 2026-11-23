package com.takeone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * Editable waveform. Drag anywhere to move the nearest selection handle.
 * Positions are expressed as fractions (0..1) of the whole take.
 */
@Composable
fun EditableWaveform(
    envelope: FloatArray,
    start: Float,
    end: Float,
    playhead: Float?,
    onChange: (start: Float, end: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by rememberUpdatedState(start)
    val e by rememberUpdatedState(end)
    val change by rememberUpdatedState(onChange)

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat().coerceAtLeast(1f)
                val f0 = (down.position.x / w).coerceIn(0f, 1f)
                // Grab whichever handle is closer to the finger.
                val moveStart = abs(f0 - s) <= abs(f0 - e)
                fun update(f: Float) {
                    if (moveStart) change(f.coerceAtMost(e), e) else change(s, f.coerceAtLeast(s))
                }
                update(f0)
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    if (!c.pressed) break
                    update((c.position.x / w).coerceIn(0f, 1f))
                    c.consume()
                }
            }
        }
    ) {
        val mid = size.height / 2f
        val n = envelope.size
        val x0 = start * size.width
        val x1 = end * size.width

        // Selection background
        drawRect(Tk.Accent.copy(alpha = 0.10f), topLeft = Offset(x0, 0f), size = Size(max(0f, x1 - x0), size.height))

        if (n > 0) {
            val barW = size.width / n
            val stroke = max(1f, barW * 0.7f)
            for (i in 0 until n) {
                val x = i * barW + barW / 2f
                val h = max(1.dp.toPx(), envelope[i] * (size.height / 2f - 4.dp.toPx()))
                val inSel = x in x0..x1
                drawLine(
                    if (inSel) Tk.Wave else Tk.WaveDim,
                    Offset(x, mid - h), Offset(x, mid + h),
                    strokeWidth = stroke, cap = StrokeCap.Butt,
                )
            }
        }

        // Handles
        listOf(x0, x1).forEach { x ->
            drawLine(Tk.Accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            drawRoundRect(
                Tk.Accent,
                topLeft = Offset(x - 7.dp.toPx(), mid - 16.dp.toPx()),
                size = Size(14.dp.toPx(), 32.dp.toPx()),
                cornerRadius = CornerRadius(5.dp.toPx()),
            )
        }

        playhead?.let { p ->
            val x = p * size.width
            drawLine(Tk.Rec, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Scrolling live level strip shown while recording. */
@Composable
fun LiveWave(history: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val slots = 160
        val barW = size.width / slots
        val mid = size.height / 2f
        val offset = slots - history.size
        history.forEachIndexed { i, v ->
            val x = (offset + i) * barW + barW / 2f
            val h = max(1.5.dp.toPx(), v.coerceIn(0f, 1f) * (size.height / 2f))
            drawLine(color, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = barW * 0.6f, cap = StrokeCap.Round)
        }
        if (history.isEmpty()) {
            drawLine(Tk.Line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        }
    }
}
