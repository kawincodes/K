package io.github.kawincodes.stiksound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kawincodes.stiksound.core.AudioTrim
import io.github.kawincodes.stiksound.core.PeakBuckets
import io.github.kawincodes.stiksound.core.TrimRange
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DragMode { START, END, MOVE }

/**
 * Waveform with the selected window. Drag the edges to resize, drag inside to move,
 * tap elsewhere to jump the window there.
 */
@Composable
fun TrimBar(
    peaks: PeakBuckets,
    totalMs: Int,
    trim: TrimRange,
    minMs: Int,
    maxMs: Int,
    playheadMs: Int?,
    onTrim: (TrimRange) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val currentTrim by rememberUpdatedState(trim)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val limits by rememberUpdatedState(Triple(totalMs, minMs, maxMs))
    var mode by remember { mutableStateOf(DragMode.MOVE) }
    var startTrim by remember { mutableStateOf(trim) }
    var dragPx by remember { mutableFloatStateOf(0f) }

    fun resize(width: Float) {
        val (total, minSel, maxSel) = limits
        val minEff = AudioTrim.effectiveMinMs(total, maxSel, minSel)
        val upper = min(maxSel, total)
        val deltaMs = (dragPx / width * total).roundToInt()
        val init = startTrim
        val next = when (mode) {
            DragMode.START -> {
                val s = (init.startMs + deltaMs).coerceIn(max(0, init.endMs - upper), max(0, init.endMs - minEff))
                TrimRange(s, init.endMs)
            }
            DragMode.END -> {
                val e = (init.endMs + deltaMs).coerceIn(min(total, init.startMs + minEff), min(total, init.startMs + upper))
                TrimRange(init.startMs, e)
            }
            DragMode.MOVE -> AudioTrim.slideWindow(init.startMs + deltaMs, init.durationMs, total, maxSel)
        }
        currentOnTrim(next)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerHighest)
            .semantics { contentDescription = description },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val (total, _, maxSel) = limits
                        val t = currentTrim
                        val centerMs = (offset.x / size.width * total).roundToInt()
                        currentOnTrim(AudioTrim.slideWindow(centerMs - t.durationMs / 2, t.durationMs, total, maxSel))
                    }
                }
                .pointerInput(Unit) {
                    val touch = 28.dp.toPx()
                    detectDragGestures(
                        onDragStart = { offset ->
                            val total = limits.first
                            val t = currentTrim
                            val sx = t.startMs.toFloat() / total * size.width
                            val ex = t.endMs.toFloat() / total * size.width
                            val ds = abs(offset.x - sx)
                            val de = abs(offset.x - ex)
                            mode = when {
                                ds <= touch && ds <= de -> DragMode.START
                                de <= touch -> DragMode.END
                                else -> DragMode.MOVE
                            }
                            startTrim = t
                            dragPx = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPx += amount.x
                            resize(size.width.toFloat())
                        },
                    )
                },
        ) {
            val width = size.width
            val height = size.height
            val mid = height / 2
            val selStart = trim.startMs.toFloat() / totalMs * width
            val selEnd = trim.endMs.toFloat() / totalMs * width

            // Selected window background.
            drawRect(colors.primary.copy(alpha = 0.16f), Offset(selStart, 0f), Size(selEnd - selStart, height))

            // Waveform: one bar every 3 px, max |peak| of the buckets under it.
            val bar = 3.dp.toPx()
            val gap = 1.dp.toPx()
            val bars = max(1, (width / bar).toInt())
            for (i in 0 until bars) {
                val b0 = i * peaks.size / bars
                val b1 = max(b0 + 1, (i + 1) * peaks.size / bars)
                var amp = 0f
                for (b in b0 until min(b1, peaks.size)) amp = max(amp, max(abs(peaks.mins[b]), abs(peaks.maxs[b])))
                val x = i * bar
                val barH = max(2.dp.toPx(), amp.coerceAtMost(1f) * (height - 16.dp.toPx()))
                val inside = x + bar / 2 in selStart..selEnd
                drawRoundRect(
                    color = if (inside) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f),
                    topLeft = Offset(x, mid - barH / 2),
                    size = Size(bar - gap, barH),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }

            // Dim outside the selection.
            drawRect(colors.surface.copy(alpha = 0.35f), Offset.Zero, Size(selStart, height))
            drawRect(colors.surface.copy(alpha = 0.35f), Offset(selEnd, 0f), Size(width - selEnd, height))

            // Handles.
            val handleW = 6.dp.toPx()
            for (x in listOf(selStart, selEnd - handleW)) {
                drawRoundRect(colors.primary, Offset(x.coerceIn(0f, width - handleW), 0f), Size(handleW, height), CornerRadius(3.dp.toPx()))
            }
            drawRect(colors.primary, Offset(selStart, 0f), Size(selEnd - selStart, 2.dp.toPx()))
            drawRect(colors.primary, Offset(selStart, height - 2.dp.toPx()), Size(selEnd - selStart, 2.dp.toPx()))

            // Playhead.
            playheadMs?.let {
                val px = it.toFloat() / totalMs * width
                drawRect(colors.onSurface, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), height))
            }
        }
    }
}

/** "1:05.3" or "4.2 s"-style labels. */
fun formatMs(ms: Int): String {
    val totalTenths = (ms + 50) / 100
    val minutes = totalTenths / 600
    val seconds = (totalTenths / 10) % 60
    val tenths = totalTenths % 10
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

fun formatSeconds(ms: Int): String = "%.1f".format(ms / 1000f)
