package dev.vxs.frostsoulxdsp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.log10

@Composable internal fun DiagnosticScreen(vm: LabViewModel) {
    val context = LocalContext.current
    val d = vm.telemetry
    val live = vm.playing && d.fresh && !vm.offloadRequested && !vm.busy
    var scrub by remember { mutableStateOf<Float?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle("Playback diagnostics", "Native measurements · not simulated") }
            StatusPill(if (live) "LIVE" else "IDLE", if (live) Ice else Muted)
        } }
        item { LabCard {
            Text(vm.selected?.title ?: "No reference selected", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Caption(vm.selected?.artist ?: "Open an audio file from the Library")
            Slider(value = scrub ?: vm.positionMs.toFloat().coerceIn(0f, vm.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { vm.seek(it.toLong()) }; scrub = null },
                valueRange = 0f..vm.durationMs.coerceAtLeast(1).toFloat(), enabled = vm.durationMs > 0 && !vm.busy,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Seek playback position" })
            Row { Caption(formatMs((scrub?.toLong() ?: vm.positionMs)), Modifier.weight(1f)); Caption(formatMs(vm.durationMs)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.adjacent(context, -1) }, enabled = !vm.busy && vm.tracks.indexOf(vm.selected) > 0) { Text("Previous") }
                PlayButton(vm.playing, vm.selected != null && !vm.busy) { vm.transport(context) }
                TextButton(onClick = { vm.adjacent(context, 1) }, enabled = !vm.busy && vm.selected != null && vm.tracks.indexOf(vm.selected) < vm.tracks.lastIndex) { Text("Next") }
            }
            ComparisonControls(vm)
            Caption(if (vm.offloadRequested) "Offload requested · native DSP bypassed; no live PCM diagnostics"
                else if (live) "${d.result} · ${vm.formatDescription}" else "${vm.engineStatus} · waiting for fresh PCM")
            if (!NativeEngine.available || (d.valid && !d.prepared && d.calls > 0)) Text("Engine unavailable. Original audio is preserved.", color = Amber, fontSize = 12.sp)
        } }
        item { LabCard {
            Row { Text("Signal levels", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Caption("dBFS · peak / RMS") }
            MeterPair("INPUT", d, false, live)
            MeterPair("OUTPUT", d, true, live)
            Caption("Native float PCM, before final PCM16 conversion. Peak bars with RMS fill; silence is −∞ dBFS.")
        } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricRow("DSP wall time", measured(d.processingMs, live, "ms"), "DSP thread CPU", measured(d.cpuPercent, live, "%"))
                MetricRow("PCM span estimate", measured(d.bufferMs, live, "ms"), "Engine quantum", measured(d.quantumMs, live, "ms"))
                MetricRow("Block / capacity", if (live) "${d.frames} / ${d.capacity}" else "—", "Rate / channels", if (live) "${d.sampleRate} Hz / ${d.channels}" else "—")
            }
        }
        item { Caption("CPU = native adapter thread CPU time ÷ PCM duration (one-core budget), not whole-device CPU. DSP time is wall-clock time inside process(). PCM span is frames ÷ sample rate, not measured output latency. AudioTrack, DAC and end-to-end latency: unavailable.") }
        item { LabCard {
            Row { Text("Output monitor", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); StatusPill("MONO SUM", Violet) }
            Caption("WAVEFORM · latest ${if (live) d.waveform.size else 0} consecutive samples")
            Waveform(if (live) d.waveform else floatArrayOf())
            Caption("SPECTRUM · Hann window · linear frequency / dBFS")
            Spectrum(if (live) vm.spectrum else floatArrayOf())
            Row { Caption("0 Hz", Modifier.weight(1f)); Caption(if (live) "${d.sampleRate / 2} Hz" else "Nyquist") }
            Caption(if (live) "Short ${d.waveform.size}-sample snapshot; coarse frequency resolution. Not a full-track waveform or calibrated spectral analyzer." else "No fresh PCM. Visualization is intentionally empty.")
        } }
        item { LabCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Processing chain", fontWeight = FontWeight.SemiBold); Caption("Reported by the loaded engine") }
                Switch(checked = vm.profiling, onCheckedChange = vm::setProfiling, enabled = !vm.offloadRequested && !vm.busy,
                    modifier = Modifier.semantics { contentDescription = "Enable sampled per-stage timing" })
            }
            Caption("Stage profiling · sampled 1 in 32 engine calls. Adds timing overhead; leave off for clean listening.")
            Caption("Media3 decode → PCM16 / float bridge")
            val named = vm.stageNames.withIndex().filter { it.value.isNotBlank() }
            if (named.isEmpty()) Caption("Stage descriptions/timings unavailable until PCM arrives, or this imported engine does not expose stage telemetry.")
            named.forEach { (index, name) ->
                Row(Modifier.fillMaxWidth().background(Color(0xFF1B293A), RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Caption(if (!live) "No live state" else if (d.stageActive(index)) "Enabled / executed" else "Bypassed / not executed")
                    }
                    Text(if (live && vm.profiling && d.profiling && d.profileSequence > 0 && d.stageActive(index)) measured(d.stageMs(index), true, "ms", 3) else "—",
                        color = if (live && d.stageActive(index)) Ice else Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Caption("Float / PCM16 bridge → Media3 output")
            if (vm.profiling && d.profileSequence > 0 && live) Caption("Last profiled native call: ${d.profileFrames} frames · window ${d.profileSequence}. Stage sums exclude untimed glue and include timer overhead.")
        } }
        item { LabCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Integrity & counters", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton(onClick = vm::resetDiagnostics) { Text("Reset") } }
            Detail("Input / output clipped samples", if (d.valid && !vm.offloadRequested) "${d.inputClips} / ${d.outputClips}" else "—")
            Detail("Non-finite samples", if (d.valid && !vm.offloadRequested) d.invalidSamples.toString() else "—")
            Detail("Native adapter calls", if (d.valid && !vm.offloadRequested) d.calls.toString() else "—")
            Detail("Input DC · L / R", dcPair(d, false, live))
            Detail("Output DC · L / R", dcPair(d, true, live))
            Detail("Max |output − input|", measured(d.maxDifference, live, "", 5))
            Detail("Native result", if (live) "${d.resultCode} · ${d.result}" else "—")
            Caption("Clip count = samples with |x| ≥ 1, accumulated since prepare/reset. DC is the signed block mean, not a long-term estimate. Counters may retain the last session while paused; live values blank after 750 ms without PCM.")
        } }
    }
}

@Composable private fun MeterPair(label: String, d: EngineTelemetry, output: Boolean, live: Boolean) {
    Caption(label)
    repeat(2) { ch ->
        val peak = if (live) d.peak(output, ch) else -1.0
        val rms = if (live) d.rms(output, ch) else -1.0
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ch == 0) "L" else "R", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(18.dp))
                Canvas(Modifier.weight(1f).height(10.dp).semantics { contentDescription = "$label ${if (ch == 0) "left" else "right"}, peak ${dbLabel(peak)}, RMS ${dbLabel(rms)} dBFS" }) {
                    drawRect(Color(0xFF283548))
                    if (live) {
                        val rmsX = levelFraction(rms) * size.width
                        val peakX = (levelFraction(peak) * size.width).coerceIn(0f, size.width - 1)
                        drawRect(if (rms >= 1) Amber else Ice.copy(alpha = .65f), size = Size(rmsX, size.height))
                        drawLine(if (peak >= 1) Color(0xFFFF817D) else Ice, Offset(peakX, 0f), Offset(peakX, size.height), 2.dp.toPx())
                    }
                }
                Text("${dbLabel(peak)} / ${dbLabel(rms)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Muted, modifier = Modifier.width(106.dp).padding(start = 8.dp))
            }
        }
    }
}
@Composable private fun MetricRow(left: String, a: String, right: String, b: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric(left, a, Modifier.weight(1f)); Metric(right, b, Modifier.weight(1f))
    }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    LabCard(modifier) { Caption(label); Text(value, color = Ice, fontSize = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
@Composable private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 11.sp, color = Muted)
        Text(value, Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable private fun Waveform(samples: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics { contentDescription = "Native output mono waveform, ${samples.size} samples" }) {
        repeat(3) { i -> val y = size.height * (i + 1) / 4; drawLine(Color(0xFF2A374B), Offset(0f, y), Offset(size.width, y), 1f) }
        if (samples.size > 1) {
            val path = Path()
            samples.forEachIndexed { i, sample ->
                val x = i * size.width / (samples.size - 1)
                val y = size.height * (.5f - sample.coerceIn(-1f, 1f) * .46f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Ice, style = Stroke(1.5.dp.toPx()))
        }
    }
}
@Composable private fun Spectrum(bins: FloatArray) {
    Canvas(Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "Spectrum of native output; ${bins.size} measured bins" }) {
        repeat(3) { i -> val y = size.height * (i + 1) / 4; drawLine(Color(0xFF2A374B), Offset(0f, y), Offset(size.width, y), 1f) }
        if (bins.isNotEmpty()) {
            val step = size.width / bins.size
            bins.forEachIndexed { i, value ->
                val h = ((value.coerceIn(-90f, 0f) + 90f) / 90f) * size.height
                drawRect(Violet.copy(alpha = .8f), Offset(i * step, size.height - h), Size((step - 1f).coerceAtLeast(.5f), h))
            }
        }
    }
}
private fun levelFraction(value: Double) = if (value <= 0) 0f else ((20 * log10(value) + 60) / 60).coerceIn(0.0, 1.0).toFloat()
private fun dbLabel(value: Double) = when { value < 0 || !value.isFinite() -> "—"; value == 0.0 -> "−∞"; else -> "%.1f".format(Locale.US, 20 * log10(value)) }
private fun measured(value: Double, live: Boolean, unit: String, digits: Int = 2) = if (!live || value < 0 || !value.isFinite()) "—" else ("%.${digits}f".format(Locale.US, value) + " $unit").trim()
private fun dcPair(d: EngineTelemetry, output: Boolean, live: Boolean) = if (live) "%+.4f / %+.4f".format(Locale.US, d.dc(output, 0), d.dc(output, 1)) else "—"
