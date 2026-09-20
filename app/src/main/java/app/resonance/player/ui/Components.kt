package app.resonance.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resonance.player.engine.EngineManager
import kotlin.math.sqrt

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Meters read linear peaks; a square root makes quiet music visible. */
fun viz(x: Float): Float = sqrt(x.coerceIn(0f, 1f))

/**
 * Output levels of the engine, refreshed every frame. Read the returned State inside a draw
 * scope (Canvas) so only the drawing is invalidated, not the whole screen.
 */
@Composable
fun rememberLevels(): State<Pair<Float, Float>> {
    val state = remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(Unit) {
        var l = 0f
        var r = 0f
        while (true) {
            withFrame()
            val p = EngineManager.processor
            l = maxOf(p.levelL, l * 0.9f)
            r = maxOf(p.levelR, r * 0.9f)
            state.value = l to r
        }
    }
    return state
}

private suspend fun withFrame() {
    androidx.compose.runtime.withFrameNanos { }
}

/** Thin fader: drag or tap to set, double-tap to reset to [defaultValue]. */
@Composable
fun Fader(
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    defaultValue: Float?,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
    accent: Color = Tide,
) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val reset by rememberUpdatedState(defaultValue)

    fun valueAt(x: Float, width: Float): Float {
        val frac = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)
        var v = min + frac * (max - min)
        if (step > 0f) v = min + Math.round((v - min) / step) * step
        return v.coerceIn(min, max)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(min, max, step) {
                detectTapGestures(
                    onDoubleTap = { reset?.let { d -> change(d); finished?.invoke() } },
                    onTap = { o ->
                        change(valueAt(o.x, size.width.toFloat()))
                        finished?.invoke()
                    },
                )
            }
            .pointerInput(min, max, step) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> change(valueAt(o.x, size.width.toFloat())) },
                    onDragEnd = { finished?.invoke() },
                    onDragCancel = { finished?.invoke() },
                    onHorizontalDrag = { c, _ ->
                        c.consume()
                        change(valueAt(c.position.x, size.width.toFloat()))
                    },
                )
            }
    ) {
        val track = Color.White.copy(alpha = 0.16f)
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = 2.dp.toPx()
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val x = size.width * frac
            // Bipolar controls fill from the centre (zero), the rest fill from the left.
            val zeroX = if (min < 0f && max > 0f) size.width * (-min / (max - min)) else 0f
            drawLine(track, Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
            drawLine(accent, Offset(zeroX, cy), Offset(x, cy), stroke, StrokeCap.Round)
            if (zeroX > 0f) {
                drawLine(track.copy(alpha = 0.5f), Offset(zeroX, cy - 6.dp.toPx()), Offset(zeroX, cy + 6.dp.toPx()), 1.dp.toPx())
            }
            drawCircle(accent.copy(alpha = 0.22f), 11.dp.toPx(), Offset(x, cy))
            drawCircle(accent, 5.dp.toPx(), Offset(x, cy))
        }
    }
}

enum class Glyph { PLAY, PAUSE, NEXT, PREV }

/** Transport button with the icon drawn in code, so no icon library is needed. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 56.dp,
    tint: Color = Mist,
    filled: Boolean = false,
) {
    Box(
        modifier
            .size(boxSize)
            .clip(CircleShape)
            .background(if (filled) Tide else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val c = if (filled) Ink else tint
        Canvas(Modifier.size(boxSize * 0.4f)) {
            val w = size.width
            val h = size.height
            when (glyph) {
                Glyph.PLAY -> drawPath(
                    Path().apply { moveTo(w * 0.15f, 0f); lineTo(w, h / 2f); lineTo(w * 0.15f, h); close() }, c
                )
                Glyph.PAUSE -> {
                    drawRect(c, Offset(w * 0.12f, 0f), Size(w * 0.28f, h))
                    drawRect(c, Offset(w * 0.60f, 0f), Size(w * 0.28f, h))
                }
                Glyph.NEXT -> {
                    drawPath(Path().apply { moveTo(0f, 0f); lineTo(w * 0.75f, h / 2f); lineTo(0f, h); close() }, c)
                    drawRect(c, Offset(w * 0.82f, 0f), Size(w * 0.18f, h))
                }
                Glyph.PREV -> {
                    drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.25f, h / 2f); lineTo(w, h); close() }, c)
                    drawRect(c, Offset(0f, 0f), Size(w * 0.18f, h))
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Tide else SlateHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Ink else Mist,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Two thin bars, left channel (Tide) above right channel (Rose). */
@Composable
fun LevelMeters(levels: State<Pair<Float, Float>>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val (l, r) = levels.value
        val h = 5.dp.toPx()
        val gap = 5.dp.toPx()
        val radius = CornerRadius(h / 2f, h / 2f)
        val back = Color.White.copy(alpha = 0.07f)
        drawRoundRect(back, Offset(0f, 0f), Size(size.width, h), radius)
        drawRoundRect(Tide, Offset(0f, 0f), Size(size.width * viz(l), h), radius)
        drawRoundRect(back, Offset(0f, h + gap), Size(size.width, h), radius)
        drawRoundRect(Rose, Offset(0f, h + gap), Size(size.width * viz(r), h), radius)
    }
}
