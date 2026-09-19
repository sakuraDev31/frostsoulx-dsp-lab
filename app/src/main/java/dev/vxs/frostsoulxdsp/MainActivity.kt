package dev.vxs.frostsoulxdsp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

private val Ink = Color(0xFF090909)
private val CardInk = Color(0xFF151515)
private val Raised = Color(0xFF202020)
private val Muted = Color(0xFF999999)
private val Accent = Color(0xFFE8E8E8)
private val Green = Color(0xFF9BE3B0)
private val Amber = Color(0xFFFFC978)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { DspLabApp() } }
}

data class LocalTrack(val id: Long, val title: String, val artist: String, val uri: Uri, val durationMs: Long)
data class EngineTelemetry(
    val inputRms: Float = 0f, val outputRms: Float = 0f, val inputPeak: Float = 0f, val outputPeak: Float = 0f,
    val maxDifference: Float = 0f, val changedPercent: Float = 0f, val clippingCount: Long = 0,
    val dcOffset: Float = 0f, val nanCount: Long = 0, val infCount: Long = 0, val processingUs: Long = 0,
    val processCalls: Long = 0, val sampleRate: Int = 0, val bufferFrames: Int = 0, val channels: Int = 2,
    val resultCode: Int = 0, val effectState: Int = -1
) {
    companion object {
        fun from(values: DoubleArray?): EngineTelemetry {
            val v = values ?: return EngineTelemetry()
            fun f(i: Int) = v.getOrNull(i)?.toFloat()?.takeIf { it.isFinite() } ?: 0f
            fun l(i: Int) = v.getOrNull(i)?.toLong()?.coerceAtLeast(0) ?: 0
            return EngineTelemetry(f(0), f(1), f(2), f(3), f(4), f(5), l(6), f(7), l(8), l(9), l(10), l(11), f(12).toInt(), f(13).toInt(), f(14).toInt(), f(15).toInt(), f(16).toInt())
        }
    }
}

data class StageState(val name: String, val enabled: Boolean, val detail: String)

class LabViewModel : androidx.lifecycle.ViewModel() {
    var tracks by mutableStateOf<List<LocalTrack>>(emptyList()); private set
    var selected by mutableStateOf<LocalTrack?>(null); private set
    var enabled by mutableStateOf(false); private set
    var bypass by mutableStateOf(false); private set
    var abOn by mutableStateOf(true); private set
    var intensity by mutableFloatStateOf(1f); private set
    var roomMix by mutableFloatStateOf(.18f); private set
    var reflection by mutableFloatStateOf(.28f); private set
    var reverb by mutableFloatStateOf(1.35f); private set
    var roomSize by mutableFloatStateOf(.5f); private set
    var dampening by mutableFloatStateOf(.5f); private set
    var width by mutableFloatStateOf(.5f); private set
    var preset by mutableIntStateOf(2); private set
    var offloadRequested by mutableStateOf(false); private set
    var engineStatus by mutableStateOf("Engine not prepared"); private set
    var importStatus by mutableStateOf("Bundled Steam Audio backend"); private set
    var engineBundleReady by mutableStateOf(false); private set
    var telemetry by mutableStateOf(EngineTelemetry()); private set
    var levelHistory by mutableStateOf(List(48) { 0f }); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var screen by mutableIntStateOf(0)
    var appCpuPercent by mutableFloatStateOf(0f); private set
    private var player: ExoPlayer? = null
    private val processor = LabAudioProcessor()
    private var lastCpuNs = Debug.threadCpuTimeNanos()
    private var lastWallNs = System.nanoTime()

    fun scan(context: Context) {
        val result = mutableListOf<LocalTrack>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) { val mediaId = c.getLong(id); result += LocalTrack(mediaId, c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist", Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.toString()), c.getLong(duration)) }
        }
        tracks = result
        val prefs = context.getSharedPreferences("dsp_lab", Context.MODE_PRIVATE); val saved = prefs.getStringSet("retained_uris", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("retained_uris", saved + result.map { it.uri.toString() }).apply()
    }

    fun play(context: Context, track: LocalTrack) {
        selected = track; player?.release(); processor.enabled = effectiveProcessing()
        player = ExoPlayer.Builder(context, LabRenderersFactory(context, processor)).build().also { exo ->
            exo.setMediaItem(MediaItem.fromUri(track.uri)); exo.prepare(); applyOffload(exo, offloadRequested); exo.play()
        }
        engineStatus = if (offloadRequested) "Hardware offload requested · custom processor bypassed" else "PCM processor path · waiting for decoded format"
    }
    fun toggle() { enabled = !enabled; syncProcessing(); engineStatus = if (effectiveProcessing()) "DSP active · custom PCM path" else "DSP bypassed · original decoded path" }
    fun toggleBypass() { bypass = !bypass; syncProcessing(); engineStatus = if (bypass) "A/B B · bypass active" else "A/B A · processed path active" }
    fun toggleAB() { abOn = !abOn; bypass = !abOn; syncProcessing(); engineStatus = if (abOn) "A/B A · processed path active" else "A/B B · bypass active" }
    private fun effectiveProcessing() = enabled && !bypass && !offloadRequested
    private fun syncProcessing() { processor.enabled = effectiveProcessing(); NativeEngine.setEnabled(effectiveProcessing()) }
    fun updateIntensity(v: Float) { intensity = v; NativeEngine.setIntensity(v) }
    fun updateRoomMix(v: Float) { roomMix = v; NativeEngine.setRoomMix(v) }
    fun updateReflection(v: Float) { reflection = v; NativeEngine.setReflectionAmount(v) }
    fun updateReverb(v: Float) { reverb = v; NativeEngine.setReverbTime(v) }
    fun updateRoomSize(v: Float) { roomSize = v; NativeEngine.setRoomSize(v) }
    fun updateDampening(v: Float) { dampening = v; NativeEngine.setDampening(v) }
    fun updateWidth(v: Float) { width = v; NativeEngine.setWidth(v) }
    fun updatePreset(v: Int) { preset = v; NativeEngine.setRoomPreset(v) }
    fun setOffload(v: Boolean) { offloadRequested = v; syncProcessing(); player?.let { applyOffload(it, v) }; engineStatus = if (v) "Offload requested · custom processor bypassed" else "Custom PCM path available" }
    private fun applyOffload(exo: ExoPlayer, enabled: Boolean) { runCatching {
        val prefsClass = Class.forName("androidx.media3.common.AudioOffloadPreferences"); val builderClass = Class.forName("androidx.media3.common.AudioOffloadPreferences\$Builder")
        val field = prefsClass.getField(if (enabled) "AUDIO_OFFLOAD_MODE_ENABLED" else "AUDIO_OFFLOAD_MODE_DISABLED"); val builder = builderClass.getDeclaredConstructor().newInstance()
        builderClass.getMethod("setAudioOffloadMode", Int::class.javaPrimitiveType).invoke(builder, field.getInt(null)); val selection = exo.trackSelectionParameters.buildUpon()
        selection.javaClass.methods.firstOrNull { it.name == "setAudioOffloadPreferences" && it.parameterTypes.size == 1 }?.invoke(selection, builderClass.getMethod("build").invoke(builder)); exo.trackSelectionParameters = selection.build()
    } }
    fun importBundle(context: Context, uri: Uri) {
        val entries = mutableListOf<String>()
        val valid = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) { if (!entry.isDirectory) entries += entry.name; entry = zip.nextEntry }
            } }
            entries.any { it.endsWith("immersive_audio_engine.h") } && entries.any { it.endsWith("immersive_audio_engine.cpp") }
        }.getOrDefault(false)
        if (!valid) { importStatus = "Invalid bundle · engine header/source not found"; engineBundleReady = false; return }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.getSharedPreferences("dsp_lab", Context.MODE_PRIVATE).edit().putString("engine_bundle_uri", uri.toString()).apply()
        engineBundleReady = true
        importStatus = "Bundle validated · rebuild required to activate this source"
    }
    fun refresh() { telemetry = EngineTelemetry.from(NativeEngine.diagnostics()); levelHistory = (levelHistory + telemetry.outputRms.coerceIn(0f, 1f)).takeLast(48); updateCpu() }
    fun tick() { player?.let { isPlaying = it.isPlaying; positionMs = it.currentPosition.coerceAtLeast(0L); durationMs = it.duration.coerceAtLeast(0L) }; refresh() }
    fun togglePlayback() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun resume() { player?.play() }
    private fun updateCpu() { val now = System.nanoTime(); val cpu = Debug.threadCpuTimeNanos(); val wallDelta = now - lastWallNs; appCpuPercent = if (wallDelta > 0) ((cpu - lastCpuNs).toDouble() / wallDelta * 100.0).toFloat().coerceIn(0f, 100f) else 0f; lastCpuNs = cpu; lastWallNs = now }
    fun stages(): List<StageState> = listOf(StageState("PCM input", telemetry.sampleRate > 0, if (telemetry.sampleRate > 0) "${telemetry.channels} channels" else "Waiting for format"), StageState("Input sanitization", effectiveProcessing(), "Finite/clamped samples"), StageState("Steam Audio HRTF", effectiveProcessing(), if (telemetry.effectState >= 0) "Effect state ${telemetry.effectState}" else "Not instrumented"), StageState("Room reflections / reverb", effectiveProcessing() && preset != 0 && roomMix > 0f, if (preset == 0 || roomMix <= 0f) "Off" else "Preset ${presetLabel(preset)}"), StageState("Output limiter", effectiveProcessing(), "Safety ceiling 0.98"))
    override fun onCleared() { player?.release() }
}

@Composable fun DspLabApp(vm: LabViewModel = viewModel()) {
    val context = LocalContext.current; val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.scan(context) }
    val bundleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importBundle(context, uri) } }
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.scan(context) else permissionLauncher.launch(permission) }
    LaunchedEffect(Unit) { while (true) { vm.tick(); kotlinx.coroutines.delay(350) } }
    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = CardInk, primary = Accent, onPrimary = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Ink) { Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) { when (vm.screen) { 0 -> TuningScreen(vm, context, bundleLauncher); 1 -> LibraryScreen(vm, context); else -> DiagnosticsScreen(vm) }
                Spacer(Modifier.height(if (vm.selected != null) 92.dp else 64.dp)); BottomNav(vm)
            }
            if (vm.selected != null) MiniPlayer(vm)
        } }
    }
}

@Composable private fun LibraryScreen(vm: LabViewModel, context: Context) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Song library", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Local audio files", color = Muted, fontSize = 13.sp) }; Text("${vm.tracks.size} tracks", color = Muted, fontSize = 12.sp) } }
        item { ActionPill("Scan device audio", "Refresh MediaStore index") { vm.scan(context) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Device audio", fontWeight = FontWeight.SemiBold, fontSize = 21.sp, modifier = Modifier.weight(1f)); Text("Tap a file to play", color = Muted, fontSize = 12.sp) } }
        if (vm.tracks.isEmpty()) item { EmptyState() }
        items(vm.tracks, key = { it.id }) { track -> TrackCard(track, vm.selected?.id == track.id) { vm.play(context, track) } }
    }
}

@Composable private fun TuningScreen(vm: LabViewModel, context: Context, bundleLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>) {
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("DSP tuning", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Control the live native engine", color = Muted, fontSize = 13.sp) }; StatusPill(vm) } }
        item { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardInk).padding(4.dp)) { listOf("Main tuning", "Advanced").forEachIndexed { index, label -> Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (advanced == (index == 1)) Raised else Color.Transparent).clickable { advanced = index == 1 }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } } } }
        item { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (advanced) "Advanced engine controls" else "Processing", fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(vm.engineStatus, color = Muted, fontSize = 12.sp) }; Switch(checked = vm.enabled, onCheckedChange = { vm.toggle() }) }; Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = vm.abOn, onClick = vm::toggleAB, label = { Text("A · Processed") }); FilterChip(selected = !vm.abOn, onClick = vm::toggleAB, label = { Text("B · Bypass") }) }; Text("Hardware offload is ${if (vm.offloadRequested) "ON and bypasses custom DSP" else "OFF; decoded PCM can reach the engine"}.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } }
        if (!advanced) {
            item { LabCard { Text("Main algorithm", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); SliderRow("Spatial intensity", vm.intensity, 0f..1f, vm::updateIntensity); PresetRow(vm); SliderRow("Room mix", vm.roomMix, 0f..1f, vm::updateRoomMix); SliderRow("Reflection amount", vm.reflection, 0f..1f, vm::updateReflection) } }
        } else {
            item { LabCard { Text("Deep-level controls", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); SliderRow("Reverb time", vm.reverb, .2f..8f, vm::updateReverb); SliderRow("Room size", vm.roomSize, 0f..1f, vm::updateRoomSize); SliderRow("Dampening", vm.dampening, 0f..1f, vm::updateDampening); SliderRow("Stereo width", vm.width, 0f..1f, vm::updateWidth) } }
            item { LabCard { Text("Engine bundle", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text(vm.importStatus, color = if (vm.engineBundleReady) Green else Muted, fontSize = 12.sp); Button(onClick = { bundleLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Validate engine ZIP") }; Text("A validated source bundle is activated on the next APK build; Android cannot compile C++ source or hot-swap a loaded native library at runtime.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } }
            item { LabCard { Text("Output path", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = vm.offloadRequested, onCheckedChange = vm::setOffload); Text("Request hardware offload", Modifier.padding(start = 8.dp), fontSize = 13.sp) } } }
        }
        item { ActionPill("Open live diagnostics", "Meters, callback load, chain state") { vm.screen = 2 } }
    }
}

@Composable private fun DiagnosticsScreen(vm: LabViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Diagnostics", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Live native engine telemetry", color = Muted, fontSize = 13.sp) }; Text(if (vm.abOn) "A" else "B", fontWeight = FontWeight.Bold, fontSize = 20.sp) } }
        item { TelemetryHero(vm) }
        item { MeterCard(vm.telemetry) }
        item { ScopeCard(vm.levelHistory) }
        item { ChainCard(vm) }
        item { MetricsGrid(vm) }
    }
}

@Composable private fun TelemetryHero(vm: LabViewModel) { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Engine state", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Text(vm.engineStatus, color = if (vm.effectiveActive()) Green else Muted, fontSize = 13.sp) }; Button(onClick = vm::toggleAB, colors = ButtonDefaults.buttonColors(containerColor = Raised)) { Text(if (vm.abOn) "A / processed" else "B / bypass") } }; Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SmallMetric("DSP load", "${vm.telemetry.loadPercent().one()}%"); SmallMetric("Callback", "${vm.telemetry.processingUs} µs"); SmallMetric("Latency", vm.telemetry.latencyText()) } } }

@Composable private fun MeterCard(t: EngineTelemetry) { LabCard { Text("Live levels", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { LevelMeter("L", t.outputPeak); LevelMeter("R", t.outputPeak); Column(Modifier.weight(1f)) { TelemetryLine("Input RMS", t.inputRms.decimal()); TelemetryLine("Output RMS", t.outputRms.decimal()); TelemetryLine("DC offset", t.dcOffset.decimal()); TelemetryLine("Clipping", "${t.clippingCount}") } } } }

@Composable private fun LevelMeter(label: String, value: Float) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.width(18.dp).height(92.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF292929)), contentAlignment = Alignment.BottomCenter) { Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(if (value > .96f) Color(0xFFE47E7E) else Green)) }; Text(label, color = Muted, fontSize = 11.sp) } }

@Composable private fun ScopeCard(history: List<Float>) { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("Output level history", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f)); Text("real samples", color = Green, fontSize = 11.sp) }; Canvas(Modifier.fillMaxWidth().height(86.dp).padding(top = 12.dp)) { val path = Path(); history.forEachIndexed { i, v -> val x = if (history.size <= 1) 0f else size.width * i / (history.size - 1); val y = size.height * (1f - v.coerceIn(0f, 1f)); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, Color.White, style = Stroke(2f)) } } }

@Composable private fun ChainCard(vm: LabViewModel) { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("Processing chain", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f)); Text("Total: ${vm.telemetry.processingUs} µs", color = Muted, fontSize = 11.sp) }; vm.stages().forEach { stage -> Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(if (stage.enabled) Green else Color(0xFF555555))); Text(stage.name, Modifier.padding(start = 10.dp).weight(1f), fontSize = 13.sp); Text(if (stage.enabled) stage.detail else "Bypassed", color = Muted, fontSize = 11.sp) } }; Text("Per-stage timing is not exposed by the current native API; only total callback time is shown.", color = Amber, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp)) } }

@Composable private fun MetricsGrid(vm: LabViewModel) { LabCard { Text("Engine metrics", fontWeight = FontWeight.SemiBold, fontSize = 17.sp); Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { TelemetryLine("Sample rate", "${vm.telemetry.sampleRate} Hz"); TelemetryLine("Buffer", "${vm.telemetry.bufferFrames} frames"); TelemetryLine("Channels", vm.telemetry.channels.toString()); TelemetryLine("Callbacks", vm.telemetry.processCalls.toString()) }; Column(Modifier.weight(1f)) { TelemetryLine("Changed", "${vm.telemetry.changedPercent.one()}%"); TelemetryLine("Max diff", vm.telemetry.maxDifference.decimal()); TelemetryLine("NaN / Inf", "${vm.telemetry.nanCount} / ${vm.telemetry.infCount}"); TelemetryLine("Result", resultLabel(vm.telemetry.resultCode)) } }; Text("Spectrum and waveform taps are not available in the current engine/JNI contract; this screen does not fabricate them.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp)) } }

@Composable private fun TrackCard(track: LocalTrack, selected: Boolean, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (selected) Raised else CardInk), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF303030)), contentAlignment = Alignment.Center) { Text("♪", fontSize = 24.sp, color = Color.White) }; Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1); Text("Local file", color = Color(0xFF707070), fontSize = 10.sp) }; Text(formatMs(track.durationMs), color = Muted, fontSize = 12.sp) } } }

@Composable private fun ControlCard(vm: LabViewModel) { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("DSP controls", fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Text(if (vm.bypass) "Bypass active" else "Tune the live processor", color = Muted, fontSize = 12.sp) }; Switch(checked = vm.enabled, onCheckedChange = { vm.toggle() }) }; Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = vm.abOn, onClick = vm::toggleAB, label = { Text("A") }); FilterChip(selected = !vm.abOn, onClick = vm::toggleAB, label = { Text("B") }); FilterChip(selected = vm.bypass, onClick = vm::toggleBypass, label = { Text("Bypass") }) }; SliderRow("Intensity", vm.intensity, 0f..1f, vm::updateIntensity); PresetRow(vm); SliderRow("Room mix", vm.roomMix, 0f..1f, vm::updateRoomMix); SliderRow("Reflection", vm.reflection, 0f..1f, vm::updateReflection); SliderRow("Reverb time", vm.reverb, .2f..8f, vm::updateReverb); SliderRow("Room size", vm.roomSize, 0f..1f, vm::updateRoomSize); SliderRow("Dampening", vm.dampening, 0f..1f, vm::updateDampening); SliderRow("Stereo width", vm.width, 0f..1f, vm::updateWidth) } }

@Composable private fun MiniPlayer(vm: LabViewModel) { Surface(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(68.dp), shape = RoundedCornerShape(20.dp), color = Color(0xFF242424), tonalElevation = 2.dp) { Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) { Text("♪", color = Color.White, fontSize = 20.sp) }; Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(vm.selected?.title ?: "No track", maxLines = 1, fontWeight = FontWeight.SemiBold); Text(if (vm.isPlaying) "Playing · ${formatMs(vm.positionMs)}" else "Paused", color = Muted, fontSize = 11.sp) }; TextButton(onClick = { if (vm.isPlaying) vm.togglePlayback() else vm.resume() }) { Text(if (vm.isPlaying) "Pause" else "Play") } } } }

@Composable private fun BottomNav(vm: LabViewModel) { NavigationBar(containerColor = Ink, tonalElevation = 0.dp) { NavigationBarItem(selected = vm.screen == 0, onClick = { vm.screen = 0 }, icon = { Text("DSP") }, label = { Text("Tune") }); NavigationBarItem(selected = vm.screen == 1, onClick = { vm.screen = 1 }, icon = { Text("LIB") }, label = { Text("Library") }); NavigationBarItem(selected = vm.screen == 2, onClick = { vm.screen = 2 }, icon = { Text("OSC") }, label = { Text("Live") }) } }
@Composable private fun StatusPill(vm: LabViewModel) { Surface(shape = RoundedCornerShape(50), color = if (vm.enabled && !vm.bypass) Color(0xFF183323) else Raised) { Text(if (vm.enabled && !vm.bypass) "DSP ON" else "BYPASS", color = if (vm.enabled && !vm.bypass) Green else Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) } }
@Composable private fun ActionPill(title: String, subtitle: String, onClick: () -> Unit) { Surface(Modifier.clickable(onClick = onClick), shape = RoundedCornerShape(14.dp), color = CardInk) { Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) { Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 10.sp) } } }
@Composable private fun EmptyState() { LabCard { Text("No indexed audio", fontWeight = FontWeight.SemiBold); Text("Grant audio access and scan the device library.", color = Muted, fontSize = 12.sp) } }
@Composable private fun SmallMetric(label: String, value: String) { Surface(shape = RoundedCornerShape(10.dp), color = Raised) { Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { Text(label, color = Muted, fontSize = 10.sp); Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun TelemetryLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium) } }
@Composable private fun LabCard(content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardInk), shape = RoundedCornerShape(20.dp), content = { Column(Modifier.padding(16.dp), content = content) }) }
@Composable private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) { Column(Modifier.padding(top = 7.dp)) { Row { Text(label, Modifier.weight(1f), fontSize = 12.sp); Text(value.decimal(), color = Muted, fontSize = 11.sp) }; Slider(value = value, onValueChange = onChange, valueRange = range) } }
@Composable private fun PresetRow(vm: LabViewModel) { val names = listOf("Off", "Small", "Studio", "Hall", "Cathedral", "Subway"); Column(Modifier.padding(top = 8.dp)) { Text("Room preset", color = Muted, fontSize = 11.sp); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { names.forEachIndexed { i, name -> FilterChip(selected = vm.preset == i, onClick = { vm.updatePreset(i) }, label = { Text(name, fontSize = 11.sp) }) } } } }

private fun Float.decimal() = "%.4f".format(Locale.US, this)
private fun Float.one() = "%.1f".format(Locale.US, this)
private fun formatMs(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
private fun presetLabel(v: Int) = listOf("Off", "Small", "Studio", "Hall", "Cathedral", "Subway").getOrElse(v) { "Unknown" }
private fun resultLabel(v: Int) = listOf("Not prepared", "Disabled", "Invalid input", "Steam Audio unavailable", "Invalid output", "Processed").getOrElse(v) { "Code $v" }
private fun EngineTelemetry.latencyText() = if (sampleRate > 0) "${(bufferFrames * 1000f / sampleRate).one()} ms" else "—"
private fun EngineTelemetry.loadPercent() = if (sampleRate > 0 && bufferFrames > 0) processingUs * 100f / (bufferFrames * 1_000_000f / sampleRate) else 0f

object NativeEngine {
    init { System.loadLibrary("frostsoulx_dsp_lab") }
    private var enabledState = false; private var intensityState = 1f; private var presetState = 2; private var roomMixState = .18f; private var reflectionState = .28f; private var reverbState = 1.35f; private var roomSizeState = .5f; private var dampeningState = .5f; private var widthState = .5f
    external fun nativePrepare(rate: Int, maxFrames: Int): Boolean; external fun nativeSetEnabled(value: Boolean); external fun nativeSetIntensity(value: Float); external fun nativeSetRoomPreset(value: Int); external fun nativeSetRoomMix(value: Float); external fun nativeSetReflectionAmount(value: Float); external fun nativeSetReverbTime(value: Float); external fun nativeSetRoomSize(value: Float); external fun nativeSetDampening(value: Float); external fun nativeSetWidth(value: Float); external fun nativeDiagnostics(): DoubleArray; external fun nativeProcess(pcm: FloatArray): Boolean; external fun nativeProcessDirect(buffer: java.nio.ByteBuffer, frames: Int): Boolean
    fun prepare(rate: Int, frames: Int) = nativePrepare(rate, frames).also { applyState() }
    fun applyState() { nativeSetEnabled(enabledState); nativeSetIntensity(intensityState); nativeSetRoomPreset(presetState); nativeSetRoomMix(roomMixState); nativeSetReflectionAmount(reflectionState); nativeSetReverbTime(reverbState); nativeSetRoomSize(roomSizeState); nativeSetDampening(dampeningState); nativeSetWidth(widthState) }
    fun setEnabled(v: Boolean) { enabledState = v; nativeSetEnabled(v) }; fun setIntensity(v: Float) { intensityState = v; nativeSetIntensity(v) }; fun setRoomPreset(v: Int) { presetState = v; nativeSetRoomPreset(v) }; fun setRoomMix(v: Float) { roomMixState = v; nativeSetRoomMix(v) }; fun setReflectionAmount(v: Float) { reflectionState = v; nativeSetReflectionAmount(v) }; fun setReverbTime(v: Float) { reverbState = v; nativeSetReverbTime(v) }; fun setRoomSize(v: Float) { roomSizeState = v; nativeSetRoomSize(v) }; fun setDampening(v: Float) { dampeningState = v; nativeSetDampening(v) }; fun setWidth(v: Float) { widthState = v; nativeSetWidth(v) }; fun diagnostics() = nativeDiagnostics(); fun processDirect(buffer: java.nio.ByteBuffer, frames: Int) = nativeProcessDirect(buffer, frames)
}

private fun LabViewModel.effectiveActive() = enabled && !bypass && !offloadRequested
