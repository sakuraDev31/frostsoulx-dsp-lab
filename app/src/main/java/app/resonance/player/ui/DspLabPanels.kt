package app.resonance.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.resonance.player.engine.AudioHardware
import app.resonance.player.engine.AudioTelemetry
import app.resonance.player.engine.DeviceMetrics
import app.resonance.player.engine.DiagnosticReport
import app.resonance.player.engine.DspChainBuilder
import app.resonance.player.engine.EngineManager
import app.resonance.player.engine.FftSize
import app.resonance.player.engine.InstalledEngine
import app.resonance.player.engine.Severity
import app.resonance.player.engine.SpectrumFrame
import app.resonance.player.engine.StageState
import app.resonance.player.engine.TelemetryHub
import app.resonance.player.engine.amplitudeToDb
import app.resonance.player.engine.buildDiagnostics
import app.resonance.player.engine.formatDb
import app.resonance.player.engine.formatNs
import app.resonance.player.engine.isMeasured
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/*
 * DSP Lab panels.
 *
 * Everything here reads the existing backend (TelemetryHub / EngineManager / DspChainBuilder /
 * buildDiagnostics / DiagnosticReport). No value is invented: when the backend has nothing
 * for a field the panel prints a dash or "N/A".
 *
 * Update policy
 *  - fast State (hub rate, 30 Hz default) is only ever read inside Canvas draw lambdas, so a new
 *    frame invalidates drawing, not composition.
 *  - slow State (collectThrottled) drives every text readout at a few Hz.
 *  - polling only runs while the screen is visible and the app is started (TelemetryLifecycle).
 *  - nothing here talks to the audio thread; the hub reads native counters on its own thread.
 */

internal val LabGood = Color(0xFF63E6A0)
internal val LabWarn = Color(0xFFFFB454)
internal val LabBad = Color(0xFFFF5D5D)
private val LabTrack = Color.White.copy(alpha = 0.07f)
private val LabGrid = Color.White.copy(alpha = 0.09f)

private val METER_TICKS = doubleArrayOf(-40.0, -20.0, -10.0, -3.0)

// ---- formatting ---------------------------------------------------------------------------

private fun num(v: Double, d: Int = 1): String = String.format(Locale.US, "%." + d + "f", v)
private fun pct(ratio: Double, d: Int = 2): String = num(ratio * 100.0, d) + "%"
private fun hzText(v: Double): String = if (v >= 1000.0) num(v / 1000.0, 2) + " kHz" else num(v, 0) + " Hz"
private fun frames(n: Int, sr: Int): String =
    if (sr > 0) "$n frames (" + num(n * 1000.0 / sr, 2) + " ms)" else "$n frames"
private fun ampDb(a: Double): String = formatDb(amplitudeToDb(a))
private fun trueDb(db: Double): String = if (db.isMeasured()) num(db, 1) + " dBTP" else "\u2013"
private fun lufs(v: Double): String = if (v.isMeasured()) num(v, 1) else "\u2013"

/** -60..0 dBFS mapped to 0..1. */
private fun dbFrac(db: Double): Float = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
private fun ampFrac(a: Double): Float = dbFrac(amplitudeToDb(a))

// ---- plumbing -----------------------------------------------------------------------------

/**
 * Samples a StateFlow every [periodMs] instead of on every emission. Used for all text readouts
 * so numbers stay legible and composition stays cheap however fast the hub publishes.
 */
@Composable
internal fun <T> StateFlow<T>.collectThrottled(periodMs: Long): State<T> {
    val source = this
    val out = remember(source) { mutableStateOf(source.value) }
    LaunchedEffect(source, periodMs) {
        while (true) {
            out.value = source.value
            delay(periodMs)
        }
    }
    return out
}

/** Polls the analyzer only while the screen is on and the app is started. */
@Composable
internal fun TelemetryLifecycle(hub: TelemetryHub) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(hub, owner) {
        var attached = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !attached) {
                attached = true
                hub.addObserver()
            } else if (event == Lifecycle.Event.ON_STOP && attached) {
                attached = false
                hub.removeObserver()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (attached) {
                attached = false
                hub.removeObserver()
            }
        }
    }
}

// ---- small building blocks ----------------------------------------------------------------

@Composable
internal fun LabCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Slate).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Mist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Mist) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MistDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MistDim, fontSize = 12.sp)
}

// ---- levels -------------------------------------------------------------------------------

@Composable
internal fun LevelsCard(fast: State<AudioTelemetry>, slow: State<AudioTelemetry>) {
    val t = slow.value
    val (statusText, statusColor) = when {
        !t.available -> "NO NATIVE BRIDGE" to LabBad
        !t.live -> "IDLE" to MistDim
        t.dspRunning -> "DSP RUNNING" to LabGood
        else -> "PASS-THROUGH" to Tide
    }
    val on = t.live
    LabCard("Levels", trailing = { Chip(statusText, statusColor) }) {
        MeterBar(fast, left = true)
        MeterBar(fast, left = false)
        Hint("Bar = RMS, coloured tick = peak, white tick = peak hold. Scale -60 to 0 dBFS, red zone above -1 dB.")

        StatRow("Output RMS (L / R)", if (on) ampDb(t.outRmsL) + " / " + ampDb(t.outRmsR) else "\u2013")
        StatRow("Output peak (L / R)", if (on) ampDb(t.outPeakL) + " / " + ampDb(t.outPeakR) else "\u2013")
        StatRow("Peak hold (L / R)", ampDb(t.holdL) + " / " + ampDb(t.holdR))
        StatRow("True peak (L / R)", trueDb(t.truePeakLDb) + " / " + trueDb(t.truePeakRDb))
        StatRow(
            "True-peak hold", trueDb(t.truePeakHoldDb),
            if (t.truePeakHoldDb.isMeasured() && t.truePeakHoldDb > -0.1) LabWarn else Mist,
        )
        StatRow("Input RMS (L / R)", if (on) ampDb(t.inRmsL) + " / " + ampDb(t.inRmsR) else "\u2013")
        StatRow("Input peak (L / R)", if (on) ampDb(t.inPeakL) + " / " + ampDb(t.inPeakR) else "\u2013")

        val headroomText = if (t.headroomDb < 199.0) num(t.headroomDb, 2) + " dB" else "\u2013"
        val headroomColor = when {
            t.headroomDb >= 199.0 -> Mist
            t.headroomDb < 0.5 -> LabBad
            t.headroomDb < 3.0 -> LabWarn
            else -> LabGood
        }
        StatRow("Headroom to 0 dBFS", headroomText, headroomColor)
        StatRow(
            "Clipped samples", t.clipCount.toString(),
            if (t.clipCount > 0) LabBad else LabGood,
        )
        StatRow(
            "Loudness M / S / I",
            lufs(t.lufsMomentary) + " / " + lufs(t.lufsShortTerm) + " / " + lufs(t.lufsIntegrated) + " LUFS",
        )
    }
}

@Composable
private fun MeterBar(fast: State<AudioTelemetry>, left: Boolean) {
    val color = if (left) Tide else Rose
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (left) "L" else "R", color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.weight(1f).height(16.dp)) {
            val t = fast.value
            // A stopped stream reads as silent rather than freezing on its last frame.
            val live = t.live
            val rms = if (!live) 0.0 else if (left) t.outRmsL else t.outRmsR
            val peak = if (!live) 0.0 else if (left) t.outPeakL else t.outPeakR
            val hold = if (left) t.holdL else t.holdR
            val w = size.width
            val h = size.height
            val radius = CornerRadius(h / 2f, h / 2f)

            drawRoundRect(LabTrack, Offset.Zero, size, radius)
            val rmsW = w * ampFrac(rms)
            if (rmsW > 0f) drawRoundRect(color.copy(alpha = 0.6f), Offset.Zero, Size(rmsW, h), radius)

            val clipX = w * dbFrac(-1.0)
            drawRect(LabBad.copy(alpha = 0.35f), Offset(clipX, 0f), Size(w - clipX, h))
            for (db in METER_TICKS) {
                val x = w * dbFrac(db)
                drawLine(LabGrid, Offset(x, 0f), Offset(x, h), 1f)
            }
            val px = w * ampFrac(peak)
            if (px > 0f) drawRect(color, Offset((px - 2.dp.toPx()).coerceAtLeast(0f), 0f), Size(2.dp.toPx(), h))
            val hx = w * ampFrac(hold)
            if (hx > 0f) drawRect(Mist, Offset((hx - 1.5.dp.toPx()).coerceAtLeast(0f), 0f), Size(1.5.dp.toPx(), h))
        }
    }
}

// ---- spectrum -----------------------------------------------------------------------------

private const val F_MIN = 20.0
private const val F_MAX = 20000.0
private const val SPEC_TOP_DB = 0.0
private const val SPEC_FLOOR_DB = -100.0

private fun freqFrac(f: Double): Float = ((ln(f) - ln(F_MIN)) / (ln(F_MAX) - ln(F_MIN))).toFloat().coerceIn(0f, 1f)

@Composable
internal fun SpectrumCard(hub: TelemetryHub, slow: State<AudioTelemetry>) {
    val frame = hub.spectrum.collectAsState()
    val fftSize by hub.fftSize.collectAsState()
    val updateHz by hub.updateHz.collectAsState()
    val t = slow.value

    LabCard(
        "Spectrum",
        trailing = { Chip("FFT " + fftSize.label, Tide) },
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("FFT size", color = MistDim, fontSize = 12.sp)
            FftSize.values().forEach { s ->
                Pill(s.label, selected = s == fftSize, onClick = { hub.setFftSize(s) })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Refresh", color = MistDim, fontSize = 12.sp)
            listOf(10, 20, 30).forEach { hz ->
                Pill("$hz Hz", selected = hz == updateHz, onClick = { hub.setUpdateHz(hz) })
            }
        }

        Canvas(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)).background(LabTrack)) {
            drawSpectrum(frame.value, t.live)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            val w = maxWidth
            listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k").forEach { (f, label) ->
                val x = w * freqFrac(f) - 8.dp
                Text(
                    label, color = MistDim, fontSize = 10.sp,
                    modifier = Modifier.offset(x = if (x < 0.dp) 0.dp else x),
                )
            }
            Text("20k", color = MistDim, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
        }

        if (t.sampleRate > 0) {
            StatRow(
                "Resolution",
                num(t.sampleRate.toDouble() / fftSize.bins, 1) + " Hz/bin \u00B7 " +
                    num(fftSize.bins * 1000.0 / t.sampleRate, 1) + " ms window",
            )
        }
        StatRow("Peak", if (t.live && t.peakFreqHz > 0) hzText(t.peakFreqHz) + " @ " + formatDb(t.peakMagDb) else "\u2013")
        StatRow("Spectral centroid", if (t.live && t.spectralCentroidHz > 0) hzText(t.spectralCentroidHz) else "\u2013")
        StatRow("Spectral RMS", if (t.live && t.spectralRmsDb.isMeasured()) formatDb(t.spectralRmsDb) else "\u2013")
    }
}

private fun DrawScope.drawSpectrum(frame: SpectrumFrame?, live: Boolean) {
    val w = size.width
    val h = size.height
    val span = SPEC_TOP_DB - SPEC_FLOOR_DB

    for (db in intArrayOf(-20, -40, -60, -80)) {
        val y = (h * ((SPEC_TOP_DB - db) / span)).toFloat()
        drawLine(LabGrid, Offset(0f, y), Offset(w, y), 1f)
    }
    for (f in doubleArrayOf(100.0, 1000.0, 10000.0)) {
        val x = w * freqFrac(f)
        drawLine(LabGrid, Offset(x, 0f), Offset(x, h), 1f)
    }
    if (frame == null || !live) return

    val mags = frame.magsDb
    val n = mags.size
    if (n < 4 || frame.fftSize <= 0 || frame.sampleRate <= 0) return
    val binHz = frame.sampleRate.toDouble() / frame.fftSize
    val nyquist = frame.sampleRate / 2.0

    val cols = (w / 2f).toInt().coerceIn(60, 420)
    val logMin = ln(F_MIN)
    val logMax = ln(F_MAX)
    val line = Path()
    val fill = Path()
    var firstX = 0f
    var lastX = 0f
    var started = false

    for (c in 0 until cols) {
        val f0 = exp(logMin + (logMax - logMin) * c / cols)
        val f1 = exp(logMin + (logMax - logMin) * (c + 1) / cols)
        if (f0 >= nyquist) break

        val b0 = (f0 / binHz).toInt().coerceIn(1, n - 1)
        val b1 = (f1 / binHz).toInt().coerceIn(b0, n - 1)
        val db: Float
        if (b1 > b0) {
            // Several bins land in this column: show the loudest, so narrow peaks stay visible.
            var m = mags[b0]
            for (b in b0 + 1..b1) if (mags[b] > m) m = mags[b]
            db = m
        } else {
            // Column narrower than a bin (low end): interpolate instead of drawing stair steps.
            val pos = (f0 + f1) * 0.5 / binHz
            val i = pos.toInt().coerceIn(0, n - 2)
            val fr = (pos - i).toFloat().coerceIn(0f, 1f)
            db = mags[i] + (mags[i + 1] - mags[i]) * fr
        }
        val norm = ((SPEC_TOP_DB - db) / span).toFloat()
        val y = h * (if (norm.isNaN()) 1f else norm.coerceIn(0f, 1f))
        val x = w * (c + 0.5f) / cols
        if (!started) {
            started = true
            firstX = x
            line.moveTo(x, y)
            fill.moveTo(x, h)
            fill.lineTo(x, y)
        } else {
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        lastX = x
    }
    if (!started) return
    fill.lineTo(lastX, h)
    fill.close()
    drawPath(fill, Tide.copy(alpha = 0.18f))
    drawPath(line, Tide, style = Stroke(width = 1.5.dp.toPx()))
}

// ---- stereo field -------------------------------------------------------------------------

@Composable
internal fun StereoCard(fast: State<AudioTelemetry>, slow: State<AudioTelemetry>) {
    val t = slow.value
    val on = t.live
    val mono = if (on) (1.0 - t.monoRisk) * 100.0 else Double.NaN

    LabCard(
        "Stereo field",
        trailing = {
            if (on) Chip("MONO SAFE " + num(mono, 0) + "%", if (mono >= 90.0) LabGood else if (mono >= 70.0) LabWarn else LabBad)
        },
    ) {
        BarRow(
            label = "Phase correlation",
            valueText = if (on) num(t.correlation, 2) else "\u2013",
            fast = fast, min = -1.0, max = 1.0, origin = 0.0, marker = 0.0,
            read = { it.correlation },
            color = { if (it >= 0.0) LabGood else if (it > -0.3) LabWarn else LabBad },
            leftHint = "-1 out of phase", rightHint = "+1 mono / in phase",
        )
        BarRow(
            label = "Balance",
            valueText = if (!on) "\u2013" else when {
                t.balance > 0.02 -> "R " + num(t.balance * 100.0, 0) + "%"
                t.balance < -0.02 -> "L " + num(-t.balance * 100.0, 0) + "%"
                else -> "Centred"
            },
            fast = fast, min = -1.0, max = 1.0, origin = 0.0, marker = 0.0,
            read = { it.balance },
            color = { if (it < 0.0) Tide else Rose },
            leftHint = "L", rightHint = "R",
        )
        BarRow(
            label = "Width",
            valueText = if (on) num(t.width, 2) else "\u2013",
            fast = fast, min = 0.0, max = 2.0, origin = 0.0, marker = 1.0,
            read = { it.width },
            color = { Tide },
            leftHint = "0 mono", rightHint = "1 normal  \u00B7  2 very wide",
        )
        LevelBarRow("Mid", fast, Tide) { it.midRms }
        LevelBarRow("Side", fast, Rose) { it.sideRms }
        StatRow("Mid / Side RMS", if (on) ampDb(t.midRms) + " / " + ampDb(t.sideRms) else "\u2013")
        val sideMinusMid = amplitudeToDb(t.sideRms) - amplitudeToDb(t.midRms)
        StatRow(
            "Side relative to Mid",
            if (on && t.midRms > 1e-6 && t.sideRms > 1e-6) num(sideMinusMid, 1) + " dB" else "\u2013",
        )
    }
}

@Composable
private fun BarRow(
    label: String,
    valueText: String,
    fast: State<AudioTelemetry>,
    min: Double,
    max: Double,
    origin: Double,
    marker: Double,
    read: (AudioTelemetry) -> Double,
    color: (Double) -> Color,
    leftHint: String,
    rightHint: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Mist, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Tide, fontSize = 13.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val t = fast.value
            // Not playing: park the bar at its origin instead of showing a stale reading.
            val v = if (t.live) read(t).coerceIn(min, max) else origin
            val span = max - min
            val ox = (size.width * ((origin - min) / span)).toFloat()
            val vx = (size.width * ((v - min) / span)).toFloat()
            val mx = (size.width * ((marker - min) / span)).toFloat()
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(LabTrack, Offset.Zero, size, radius)
            val left = if (vx < ox) vx else ox
            val width = abs(vx - ox)
            if (width > 0.5f) drawRoundRect(color(v), Offset(left, 0f), Size(width, size.height), radius)
            drawRect(Mist.copy(alpha = 0.5f), Offset(mx - 0.5.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
        }
        Row {
            Text(leftHint, color = MistDim, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(rightHint, color = MistDim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun LevelBarRow(label: String, fast: State<AudioTelemetry>, color: Color, read: (AudioTelemetry) -> Double) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MistDim, fontSize = 12.sp, modifier = Modifier.size(width = 34.dp, height = 16.dp))
        Canvas(Modifier.weight(1f).height(8.dp)) {
            val t = fast.value
            val a = if (t.live) read(t) else 0.0
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(LabTrack, Offset.Zero, size, radius)
            val w = size.width * ampFrac(a)
            if (w > 0f) drawRoundRect(color.copy(alpha = 0.75f), Offset.Zero, Size(w, size.height), radius)
        }
    }
}

// ---- DSP chain ----------------------------------------------------------------------------

@Composable
internal fun ChainCard(
    engine: InstalledEngine?,
    values: Map<String, Float>,
    enabled: Boolean,
    slow: State<AudioTelemetry>,
) {
    val t = slow.value
    val stages = remember(engine, values, enabled, t) { DspChainBuilder.build(engine, values, enabled, t) }
    LabCard("DSP chain", trailing = { Chip(stages.size.toString() + " stages", MistDim) }) {
        Column {
            stages.forEachIndexed { i, s ->
                val first = i == 0
                val last = i == stages.lastIndex
                val dot = when (s.state) {
                    StageState.HOST -> MistDim
                    StageState.ACTIVE -> LabGood
                    StageState.BYPASSED -> LabWarn
                }
                val stateText = when (s.state) {
                    StageState.HOST -> "HOST"
                    StageState.ACTIVE -> "ACTIVE"
                    StageState.BYPASSED -> "BYPASSED"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val x = 6.dp.toPx()
                            val cy = size.height / 2f
                            val rail = Color.White.copy(alpha = 0.14f)
                            if (!first) drawLine(rail, Offset(x, 0f), Offset(x, cy), 2.dp.toPx())
                            if (!last) drawLine(rail, Offset(x, cy), Offset(x, size.height), 2.dp.toPx())
                            drawCircle(dot, 4.5.dp.toPx(), Offset(x, cy))
                        }
                        .padding(start = 22.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (s.detail.isNotBlank()) Text(s.detail, color = MistDim, fontSize = 12.sp)
                    }
                    Chip(stateText, dot)
                }
            }
        }
        Hint("Stages come from the loaded engine's manifest parameters plus the fixed host path. An engine with no reverb parameters has no Room stage.")
    }
}

// ---- timing -------------------------------------------------------------------------------

@Composable
internal fun TimingCard(slow: State<AudioTelemetry>) {
    val t = slow.value
    val has = t.blockCount > 0
    LabCard("DSP timing") {
        RatioBar(if (has) t.rtRatioAvg else 0.0)
        StatRow(
            "Realtime ratio (avg / last)",
            if (has) pct(t.rtRatioAvg) + " / " + pct(t.rtRatioLast) else "\u2013",
            when {
                !has -> Mist
                t.rtRatioAvg > 0.7 -> LabBad
                t.rtRatioAvg > 0.4 -> LabWarn
                else -> LabGood
            },
        )
        StatRow("DSP time, average", if (has) formatNs(t.procAvgNs) else "\u2013")
        StatRow("DSP time, last", if (has) formatNs(t.procLastNs) else "\u2013")
        StatRow("DSP time, minimum", if (has) formatNs(t.procMinNs) else "\u2013")
        StatRow("DSP time, worst", if (has) formatNs(t.procMaxNs) else "\u2013")
        StatRow("Std deviation", if (has) formatNs(t.procStdDevNs) else "\u2013")
        StatRow("Block wall budget", if (t.blockBudgetUs > 0) num(t.blockBudgetUs, 0) + " \u00B5s" else "\u2013")
        if (t.bench.hasData) {
            StatRow(
                "p50 / p95 / p99 (last benchmark)",
                formatNs(t.bench.p50Ns) + " / " + formatNs(t.bench.p95Ns) + " / " + formatNs(t.bench.p99Ns),
            )
        } else {
            Hint("Percentiles (p50 / p95 / p99) are collected by the benchmark below; the live counters only track average, min, max and spread.")
        }
    }
}

@Composable
private fun RatioBar(ratio: Double) {
    val color = when {
        ratio > 0.7 -> LabBad
        ratio > 0.4 -> LabWarn
        else -> LabGood
    }
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(LabTrack, Offset.Zero, size, radius)
        var w = size.width * ratio.toFloat().coerceIn(0f, 1f)
        if (ratio > 0.0 && w < 3.dp.toPx()) w = 3.dp.toPx()
        if (w > 0f) drawRoundRect(color, Offset.Zero, Size(w, size.height), radius)
    }
}

@Composable
internal fun QuantumCard(engineLoaded: Boolean, slow: State<AudioTelemetry>) {
    val t = slow.value
    val quantum by EngineManager.quantum.collectAsState()
    LabCard("Quantum, latency & dropouts") {
        if (engineLoaded) {
            Text("Processing quantum", color = Mist, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineManager.QUANTUM_OPTIONS.forEach { q ->
                    Pill(if (q == 0) "AUTO" else q.toString(), selected = quantum == q, onClick = { EngineManager.setQuantum(q) })
                }
            }
            Hint("AUTO lets the engine follow the host block with no added latency. A fixed quantum re-blocks through a FIFO and adds latency.")
        }
        StatRow("Active quantum", if (t.quantum > 0) t.quantum.toString() + " frames" else "AUTO")
        StatRow("Host block", if (t.hostBlockFrames > 0) frames(t.hostBlockFrames, t.sampleRate) else "\u2013")
        StatRow("Latency added by FIFO", if (t.sampleRate > 0) frames(t.latencyFrames, t.sampleRate) else "\u2013")
        StatRow("Host callbacks", t.callbackCount.toString())
        StatRow("Engine blocks", t.blockCount.toString())

        val missPct = if (t.blockCount > 0) 100.0 * t.deadlineMisses / t.blockCount else 0.0
        StatRow(
            "Deadline misses",
            t.deadlineMisses.toString() + " of " + t.blockCount + " (" + num(missPct, 2) + "%)",
            when {
                t.deadlineMisses == 0L -> LabGood
                missPct > 1.0 -> LabBad
                else -> LabWarn
            },
        )
        StatRow(
            "FIFO underflow (DSP path)",
            if (t.fifoUnderflowFrames > 0) frames(t.fifoUnderflowFrames.toInt(), t.sampleRate) else "0 frames",
            if (t.fifoUnderflowFrames > 0) LabBad else LabGood,
        )
        StatRow(
            "Sink underruns (Media3)",
            t.sinkUnderruns.toString() + if (t.sinkUnderruns > 0 && t.sinkUnderrunMs >= 0) " \u00B7 last gap " + num(t.sinkUnderrunMs, 0) + " ms" else "",
            if (t.sinkUnderruns > 0) LabWarn else LabGood,
        )
        StatRow("Sink errors", t.sinkErrors.toString(), if (t.sinkErrors > 0) LabBad else LabGood)
        StatRow(
            "AudioTrack buffer",
            if (t.sinkBufferFrames > 0) frames(t.sinkBufferFrames, t.sampleRate) else "not reported yet",
        )
        StatRow(
            "NaN / Inf samples",
            t.nanCount.toString() + " / " + t.infCount,
            if (t.nanCount > 0 || t.infCount > 0) LabBad else LabGood,
        )
    }
}

// ---- benchmark ----------------------------------------------------------------------------

@Composable
internal fun BenchmarkCard(hub: TelemetryHub, slow: State<AudioTelemetry>) {
    val t = slow.value
    val bench by hub.benchmark.collectThrottled(200)
    val b = t.bench
    LabCard(
        "Benchmark",
        trailing = {
            if (bench.running) {
                Chip("RUNNING", LabWarn)
            } else if (bench.completed) {
                Chip("DONE " + bench.durationSec + " s", LabGood)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(10, 30, 60).forEach { sec ->
                Pill(
                    "$sec s",
                    selected = bench.running && bench.durationSec == sec,
                    onClick = { if (!bench.running) hub.startBenchmark(sec) },
                )
            }
            if (bench.running) Pill("Stop", selected = false, onClick = { hub.stopBenchmark() })
        }
        if (bench.running) {
            val total = bench.durationSec * 1000f
            val done = if (total > 0f) (1f - bench.remainingMs / total).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(LabTrack, Offset.Zero, size, radius)
                if (done > 0f) drawRoundRect(LabWarn, Offset.Zero, Size(size.width * done, size.height), radius)
            }
            Hint(num(bench.remainingMs / 1000.0, 1) + " s left")
        } else if (!t.live) {
            Hint("Start playback first: the benchmark measures real engine blocks, so it needs audio flowing through a loaded, enabled engine.")
        }

        if (b.hasData) {
            StatRow("Blocks measured", b.blocks.toString())
            StatRow("Average", formatNs(b.avgNs))
            StatRow("Minimum", formatNs(b.minNs))
            StatRow("Median (p50)", formatNs(b.p50Ns))
            StatRow("p95", formatNs(b.p95Ns))
            StatRow("p99", formatNs(b.p99Ns))
            StatRow("Worst", formatNs(b.maxNs))
            StatRow("Std deviation", formatNs(b.stdDevNs))
            StatRow("Realtime ratio", pct(b.rtRatio, 3))
            StatRow(
                "Deadline misses", b.deadlineMisses.toString(),
                if (b.deadlineMisses > 0) LabWarn else LabGood,
            )
        } else if (bench.completed && !bench.running) {
            Hint("No blocks were measured. Play audio through a loaded engine that is switched on while the benchmark runs.")
        }
    }
}

// ---- device -------------------------------------------------------------------------------

@Composable
internal fun DeviceCard(hub: TelemetryHub, slow: State<AudioTelemetry>) {
    val d by hub.device.collectThrottled(500)
    val hw by hub.hardware.collectThrottled(1000)
    val t = slow.value
    LabCard("Device & audio hardware") {
        DeviceRows(d, hw, t)
    }
}

@Composable
private fun DeviceRows(d: DeviceMetrics, hw: AudioHardware, t: AudioTelemetry) {
    StatRow(
        "App CPU",
        d.processCpuPercent?.let { num(it, 1) + "% of one core" } ?: "\u2013",
    )
    Hint("Whole app process from /proc/self/stat. Android does not expose per-thread CPU for the audio thread, so this is not the DSP cost.")
    StatRow(
        "CPU cores",
        (d.onlineCores?.toString() ?: "?") + " online of " + d.coreCount + " \u00B7 " + d.abi,
    )
    StatRow("System load (1 min)", d.systemLoad1?.let { num(it, 2) } ?: "\u2013")
    StatRow("Java heap", num(d.javaHeapUsedMb, 1) + " / " + num(d.javaHeapMaxMb, 0) + " MB")
    StatRow("Native heap", num(d.nativeHeapMb, 1) + " MB")
    StatRow("Thermal status", d.thermalStatus ?: "not reported", if (d.thermalThrottling) LabWarn else Mist)
    StatRow("Battery temperature", d.batteryTemperatureC?.let { num(it, 1) + " \u00B0C" } ?: "\u2013")
    StatRow("GPU utilisation", d.gpuPercent?.let { num(it, 0) + "%" } ?: "N/A")
    if (d.gpuPercent == null) Hint("Android has no public GPU utilisation API, so none is shown.")
    d.gpuRenderer?.let { StatRow("GPU renderer", it) }
    StatRow("SoC", d.soc)

    StatRow("Stream sample rate", if (t.sampleRate > 0) t.sampleRate.toString() + " Hz" else "\u2013")
    StatRow("HAL output rate", hw.outputSampleRate?.let { it.toString() + " Hz" } ?: "\u2013")
    StatRow(
        "HAL frames per burst",
        hw.outputFramesPerBurst?.let { it.toString() + (hw.framesPerBurstMs?.let { ms -> " (" + num(ms, 2) + " ms)" } ?: "") } ?: "\u2013",
    )
    StatRow("PCM format", hw.encoding)
    StatRow("Channels", hw.channels.toString())
    StatRow("Output route", hw.route)
    hw.routeDetail?.let { StatRow("Route detail", it) }
}

// ---- diagnostics + export -----------------------------------------------------------------

@Composable
internal fun DiagnosticsCard(
    hub: TelemetryHub,
    slow: State<AudioTelemetry>,
    engineLoaded: Boolean,
    exporter: ReportExporter,
) {
    val t = slow.value
    val d by hub.device.collectThrottled(500)
    val diagnostics = remember(t, d, engineLoaded) { buildDiagnostics(t, d, engineLoaded) }
    LabCard("Diagnostics") {
        diagnostics.forEach { item ->
            val c = when (item.severity) {
                Severity.ERROR -> LabBad
                Severity.WARNING -> LabWarn
                Severity.INFO -> Tide
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.08f)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(item.detail, color = MistDim, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Export report (.txt)", selected = true, onClick = exporter.export)
            Pill("Reset stats", selected = false, onClick = { hub.resetStats() })
        }
        exporter.status.value?.let { Hint(it) }
    }
}

/** Save-report action plus the outcome of the last attempt, shown under the buttons. */
internal class ReportExporter(val export: () -> Unit, val status: State<String?>)

/**
 * Returns a function that snapshots every panel's data into a DiagnosticReport and asks the
 * system file picker where to save it. Nothing is polled for this: the values are read from the
 * hub's StateFlows at the moment of the tap. Total PSS is read once here because it is far too
 * slow to poll.
 */
@Composable
internal fun rememberReportExporter(hub: TelemetryHub): ReportExporter {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<String>(1) }
    val status = remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val out = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                val text2 = result.fold(
                    onSuccess = { "Report saved" },
                    onFailure = { "Could not save the report: " + (it.message ?: it.javaClass.simpleName) },
                )
                withContext(Dispatchers.Main) { status.value = text2 }
            }
        }
    }

    return remember(hub, launcher) {
        val export: () -> Unit = {
            status.value = null
            scope.launch {
                val pss = withContext(Dispatchers.Default) { hub.deviceSampler.samplePss() }
                val t = hub.telemetry.value
                val device = hub.device.value
                val engine = EngineManager.active.value
                val values = EngineManager.values.value
                val enabled = EngineManager.enabled.value
                val chain = DspChainBuilder.build(engine, values, enabled, t)
                val diagnostics = buildDiagnostics(t, device, engine != null)
                pending[0] = DiagnosticReport.build(
                    t = t,
                    device = device,
                    hardware = hub.hardware.value,
                    engine = engine,
                    values = values,
                    enabled = enabled,
                    chain = chain,
                    diagnostics = diagnostics,
                    pssMb = pss,
                )
                launcher.launch(DiagnosticReport.fileName())
            }
            Unit
        }
        ReportExporter(export, status)
    }
}
