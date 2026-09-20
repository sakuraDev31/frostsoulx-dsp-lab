package app.resonance.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resonance.player.MainViewModel
import app.resonance.player.engine.AudioTelemetry
import app.resonance.player.engine.EngineManager
import app.resonance.player.engine.EngineParam
import app.resonance.player.engine.InstalledEngine
import app.resonance.player.engine.ParamType
import app.resonance.player.engine.TelemetryHub
import app.resonance.player.engine.amplitudeToDb
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

private enum class DspPanel(val label: String) {
    METERS("Meters"),
    ENGINE("Engine"),
    PERFORMANCE("Perf"),
    SYSTEM("System"),
}

@Composable
fun SoundScreen(vm: MainViewModel) {
    val installed by EngineManager.installed.collectAsState()
    val active by EngineManager.active.collectAsState()
    val values by EngineManager.values.collectAsState()
    val enabled by EngineManager.enabled.collectAsState()
    val message by EngineManager.message.collectAsState()
    val hub = EngineManager.telemetry
    TelemetryLifecycle(hub)
    // `fast` is only read inside Canvas draw scopes, `slow` drives every text readout.
    val fast = hub.telemetry.collectAsState()
    val slow = hub.telemetry.collectThrottled(250)
    val exporter = rememberReportExporter(hub)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importEngine(uri)
    }

    var panel by rememberSaveable { mutableStateOf(DspPanel.METERS) }

    Column(Modifier.fillMaxSize()) {
        DspStatusBar(active = active, enabled = enabled, fast = fast)
        DspPanelSwitcher(panel = panel, onSelect = { panel = it })
        message?.let { msg -> DspMessageBanner(msg) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                DspPanel.METERS -> MetersPanel(hub, fast, slow)
                DspPanel.ENGINE -> EnginePanel(
                    active = active,
                    installed = installed,
                    values = values,
                    enabled = enabled,
                    slow = slow,
                    onImport = { picker.launch(ZIP_TYPES) },
                )
                DspPanel.PERFORMANCE -> PerformancePanel(hub, slow, active != null)
                DspPanel.SYSTEM -> SystemPanel(hub, slow, active != null, exporter)
            }
        }
    }
}

// ---- chrome: status bar, panel switcher, message banner -----------------------------------

@Composable
private fun DspStatusBar(active: InstalledEngine?, enabled: Boolean, fast: State<AudioTelemetry>) {
    val (statusText, statusColor) = when {
        active == null -> "NO ENGINE" to MistDim
        enabled -> "ACTIVE" to LabGood
        else -> "BYPASSED" to LabWarn
    }
    Row(
        Modifier.fillMaxWidth().background(Slate).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("DSP Lab", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                active?.manifest?.name ?: "No engine loaded",
                color = MistDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        MiniMeter(fast)
        StatusChip(statusText, statusColor)
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Text(
        text,
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Compact always-visible L/R output meter, same -60..0 dBFS mapping as MeterBar in DspLabPanels.kt. */
@Composable
private fun MiniMeter(fast: State<AudioTelemetry>) {
    Canvas(Modifier.width(48.dp).height(14.dp)) {
        val t = fast.value
        fun frac(db: Double) = ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
        val l = if (t.live) frac(amplitudeToDb(t.outRmsL)) else 0f
        val r = if (t.live) frac(amplitudeToDb(t.outRmsR)) else 0f
        val barH = (size.height - 2.dp.toPx()) / 2f
        val radius = CornerRadius(barH / 2f, barH / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.08f), Offset.Zero, Size(size.width, barH), radius)
        if (l > 0f) drawRoundRect(Tide, Offset.Zero, Size(size.width * l, barH), radius)
        val y2 = barH + 2.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = 0.08f), Offset(0f, y2), Size(size.width, barH), radius)
        if (r > 0f) drawRoundRect(Rose, Offset(0f, y2), Size(size.width * r, barH), radius)
    }
}

/** Hardware-selector-style segmented control instead of a scrolling tab row. */
@Composable
private fun DspPanelSwitcher(panel: DspPanel, onSelect: (DspPanel) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SlateHi),
    ) {
        DspPanel.values().forEach { p ->
            val selected = p == panel
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Tide else Color.Transparent)
                    .clickable { onSelect(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(p.label, color = if (selected) Ink else MistDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DspMessageBanner(msg: String) {
    Text(
        msg,
        color = Mist, fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SlateHi)
            .clickable { EngineManager.dismissMessage() }
            .padding(14.dp),
    )
}

// ---- panels ---------------------------------------------------------------------------------

@Composable
private fun MetersPanel(hub: TelemetryHub, fast: State<AudioTelemetry>, slow: State<AudioTelemetry>) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        item(key = "levels") { LevelsCard(fast, slow) }
        item(key = "spectrum") { SpectrumCard(hub, slow) }
        item(key = "stereo") { StereoCard(fast, slow) }
    }
}

@Composable
private fun EnginePanel(
    active: InstalledEngine?,
    installed: List<InstalledEngine>,
    values: Map<String, Float>,
    enabled: Boolean,
    slow: State<AudioTelemetry>,
    onImport: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        item { EngineCard(active = active, installed = installed, enabled = enabled, onImport = onImport) }

        val eng = active
        if (eng == null) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Slate).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Import a sound engine", color = Mist, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "An engine is a zip with manifest.json and lib/<abi>/*.so. Once it loads, every control it " +
                            "declares shows up here and works on the music you play.",
                        color = MistDim, fontSize = 14.sp,
                    )
                }
            }
        } else {
            if (eng.manifest.presets.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        eng.manifest.presets.forEach { preset ->
                            val selected = preset.values.all { (k, v) -> abs((values[k] ?: Float.NaN) - v) < 0.001f }
                            Pill(preset.name, selected = selected, onClick = { EngineManager.applyPreset(preset) })
                        }
                    }
                }
            }

            item(key = "chain") { ChainCard(eng, values, enabled, slow) }

            eng.manifest.params.groupBy { it.group }.forEach { (group, params) ->
                item(key = "group_$group") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Slate).padding(16.dp)) {
                        Text(group, color = Mist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        params.forEach { p ->
                            Spacer(Modifier.height(14.dp))
                            ParamRow(p, values[p.id] ?: p.default)
                        }
                    }
                }
            }

            item { Pill("Reset to defaults", selected = false, onClick = { EngineManager.resetToDefaults() }) }
        }
    }
}

@Composable
private fun PerformancePanel(hub: TelemetryHub, slow: State<AudioTelemetry>, engineLoaded: Boolean) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        item(key = "timing") { TimingCard(slow) }
        item(key = "quantum") { QuantumCard(engineLoaded, slow) }
        item(key = "benchmark") { BenchmarkCard(hub, slow) }
    }
}

@Composable
private fun SystemPanel(hub: TelemetryHub, slow: State<AudioTelemetry>, engineLoaded: Boolean, exporter: ReportExporter) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp),
    ) {
        item(key = "device") { DeviceCard(hub, slow) }
        item(key = "diagnostics") { DiagnosticsCard(hub, slow, engineLoaded, exporter) }
    }
}

// ---- engine card + param row (unchanged from the previous version) ------------------------

@Composable
private fun EngineCard(
    active: InstalledEngine?,
    installed: List<InstalledEngine>,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Slate).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (active != null) {
            Column {
                Text(active.manifest.name, color = Mist, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (active.manifest.version.isNotBlank()) {
                    Text("Version ${active.manifest.version}", color = MistDim, fontSize = 12.sp)
                }
                if (active.manifest.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(active.manifest.description, color = MistDim, fontSize = 13.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (active == null) "Import engine zip" else "Import another", selected = active == null, onClick = onImport)
            if (active != null) {
                Pill(if (enabled) "Engine on" else "Bypassed", selected = enabled, onClick = { EngineManager.setEnabled(!enabled) })
            }
        }
        if (installed.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEach { e ->
                    Pill(e.manifest.name, selected = e.manifest.id == active?.manifest?.id, onClick = { EngineManager.select(e) })
                }
            }
        }
        if (active != null) {
            Text(
                "Remove this engine",
                color = Rose, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { EngineManager.remove(active) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ParamRow(p: EngineParam, value: Float) {
    when (p.type) {
        ParamType.SLIDER -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = Mist, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(fmtValue(p, value), color = Tide, fontSize = 13.sp)
            }
            Fader(
                value = value, min = p.min, max = p.max, step = p.step, defaultValue = p.default,
                onChange = { EngineManager.setParam(p.id, it) },
            )
        }
        ParamType.TOGGLE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, color = Mist, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = value >= 0.5f,
                onCheckedChange = { EngineManager.setParam(p.id, if (it) 1f else 0f) },
                colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Tide),
            )
        }
        ParamType.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.label, color = Mist, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.options.forEachIndexed { i, option ->
                    Pill(option, selected = value.roundToInt() == i, onClick = { EngineManager.setParam(p.id, i.toFloat()) })
                }
            }
        }
    }
}

private fun fmtValue(p: EngineParam, v: Float): String {
    val decimals = when {
        p.step >= 1f -> 0
        p.step >= 0.1f -> 1
        else -> 2
    }
    val sign = if (p.min < 0f && v > 0f) "+" else ""
    val s = sign + String.format(Locale.US, "%." + decimals + "f", v)
    return if (p.unit.isBlank()) s else "$s ${p.unit}"
}
