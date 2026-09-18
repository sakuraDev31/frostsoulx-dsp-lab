package dev.vxs.frostsoulxdsp

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DspLabApp() }
    }
}

data class LocalTrack(val id: Long, val title: String, val artist: String, val uri: Uri, val durationMs: Long)

class LabViewModel : androidx.lifecycle.ViewModel() {
    var tracks by mutableStateOf<List<LocalTrack>>(emptyList()); private set
    var selected by mutableStateOf<LocalTrack?>(null); private set
    var enabled by mutableStateOf(false); private set
    var intensity by mutableFloatStateOf(1f); private set
    var roomMix by mutableFloatStateOf(.18f); private set
    var reflection by mutableFloatStateOf(.28f); private set
    var reverb by mutableFloatStateOf(1.35f); private set
    var roomSize by mutableFloatStateOf(.5f); private set
    var dampening by mutableFloatStateOf(.5f); private set
    var width by mutableFloatStateOf(.5f); private set
    var preset by mutableIntStateOf(2); private set
    var offloadRequested by mutableStateOf(false); private set
    var engineStatus by mutableStateOf("Engine not prepared")
    var importStatus by mutableStateOf("Engine source bundle is bundled at build time")
    var diagnostics by mutableStateOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    private var player: ExoPlayer? = null
    private val processor = LabAudioProcessor()

    fun scan(context: Context) {
        val resolver = context.contentResolver
        val result = mutableListOf<LocalTrack>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID); val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST); val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) { val mediaId = c.getLong(id); result += LocalTrack(mediaId, c.getString(title) ?: "Unknown title", c.getString(artist) ?: "Unknown artist", Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.toString()), c.getLong(duration)) }
        }
        tracks = result
        val prefs = context.getSharedPreferences("dsp_lab", Context.MODE_PRIVATE)
        val saved = prefs.getStringSet("retained_uris", emptySet()) ?: emptySet()
        prefs.edit().putStringSet("retained_uris", saved + result.map { it.uri.toString() }).apply()
    }
    fun play(context: Context, track: LocalTrack) { selected = track; player?.release(); processor.enabled = enabled && !offloadRequested; player = ExoPlayer.Builder(context, LabRenderersFactory(context, processor)).build().also { it.setMediaItem(MediaItem.fromUri(track.uri)); it.prepare(); applyOffload(it, offloadRequested); it.play() }; engineStatus = "Ready · ${if (offloadRequested) "offload requested; custom DSP bypassed" else "custom processor path"}" }
    fun toggle() { enabled = !enabled; processor.enabled = enabled && !offloadRequested; NativeEngine.setEnabled(enabled); engineStatus = if (enabled && !offloadRequested) "DSP enabled · PCM processor active" else if (enabled) "DSP enabled in controls · bypassed while offload is requested" else "DSP disabled · original path requested" }
    fun updateIntensity(v: Float) { intensity = v; NativeEngine.setIntensity(v) }
    fun updateRoomMix(v: Float) { roomMix = v; NativeEngine.setRoomMix(v) }
    fun updateReflection(v: Float) { reflection = v; NativeEngine.setReflectionAmount(v) }
    fun updateReverb(v: Float) { reverb = v; NativeEngine.setReverbTime(v) }
    fun updateRoomSize(v: Float) { roomSize = v; NativeEngine.setRoomSize(v) }
    fun updateDampening(v: Float) { dampening = v; NativeEngine.setDampening(v) }
    fun updateWidth(v: Float) { width = v; NativeEngine.setWidth(v) }
    fun updatePreset(v: Int) { preset = v; NativeEngine.setRoomPreset(v) }
    fun setOffload(v: Boolean) { offloadRequested = v; processor.enabled = enabled && !v; player?.let { applyOffload(it, v) }; engineStatus = if (v) "Offload requested · custom DSP bypassed to protect the hardware path" else "Custom DSP eligible · offload disabled" }
    private fun applyOffload(exo: ExoPlayer, enabled: Boolean) {
        runCatching {
            val prefsClass = Class.forName("androidx.media3.common.AudioOffloadPreferences")
            val builderClass = Class.forName("androidx.media3.common.AudioOffloadPreferences\$Builder")
            val field = prefsClass.getField(if (enabled) "AUDIO_OFFLOAD_MODE_ENABLED" else "AUDIO_OFFLOAD_MODE_DISABLED")
            val prefsBuilder = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setAudioOffloadMode", Int::class.javaPrimitiveType).invoke(prefsBuilder, field.getInt(null))
            val selection = exo.trackSelectionParameters.buildUpon()
            selection.javaClass.methods.firstOrNull { it.name == "setAudioOffloadPreferences" && it.parameterTypes.size == 1 }?.invoke(selection, builderClass.getMethod("build").invoke(prefsBuilder))
            exo.trackSelectionParameters = selection.build()
        }
    }
    fun importBundle(context: Context, uri: Uri) { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); context.getSharedPreferences("dsp_lab", Context.MODE_PRIVATE).edit().putString("engine_bundle_uri", uri.toString()).apply(); importStatus = "Imported bundle staged: $uri · rebuild required to activate native code" }
    fun refreshDiagnostics() {
        val d = NativeEngine.diagnostics()
        if (d.size >= 40) diagnostics = doubleArrayOf(d[18], d[22], d[16], d[20], d[15], d[1], d[10])
    }
    override fun onCleared() { player?.release() }
}

@Composable fun DspLabApp(vm: LabViewModel = viewModel()) {
    val context = LocalContext.current
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.scan(context) }
    val bundleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importBundle(context, it) }
    }
    LaunchedEffect(Unit) { if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.scan(context) else permissionLauncher.launch(permission) }
    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF151515), primary = Color.White, onPrimary = Color.Black)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item { Text("FrostSoulX DSP Lab", fontSize = 30.sp, fontWeight = FontWeight.Bold); Text("Native engine playground", color = Color.Gray) }
                item { LabCard { Text("Engine bundle", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text(vm.importStatus, color = Color.Gray, fontSize = 13.sp); Button(onClick = { bundleLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import engine ZIP") } } }
                item { LabCard { Text("Playback path", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = vm.offloadRequested, onCheckedChange = vm::setOffload); Text("Request hardware offload", Modifier.padding(start = 8.dp)) }; Text("Offload and custom PCM processing are mutually exclusive. The lab reports the conflict instead of silently claiming both are active.", color = Color.Gray, fontSize = 12.sp); Text(vm.engineStatus, color = Color.LightGray) } }
                item { Text("Device audio · ${vm.tracks.size} files", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                items(vm.tracks) { track -> Row(Modifier.fillMaxWidth().clickable { vm.play(context, track) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.Medium); Text(track.artist, color = Color.Gray, fontSize = 13.sp) }; Text(formatMs(track.durationMs), color = Color.Gray, fontSize = 12.sp) } }
                item { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("DSP", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Switch(checked = vm.enabled, onCheckedChange = { vm.toggle() }) }; Text("Selected: ${vm.selected?.title ?: "none"}", color = Color.Gray); SliderRow("Intensity", vm.intensity, 0f..1f, vm::updateIntensity); PresetRow(vm); SliderRow("Room mix", vm.roomMix, 0f..1f, vm::updateRoomMix); SliderRow("Reflection amount", vm.reflection, 0f..1f, vm::updateReflection); SliderRow("Reverb time (seconds)", vm.reverb, .2f..8f, vm::updateReverb); SliderRow("Room size", vm.roomSize, 0f..1f, vm::updateRoomSize); SliderRow("Dampening", vm.dampening, 0f..1f, vm::updateDampening); SliderRow("Stereo width", vm.width, 0f..1f, vm::updateWidth) } }
                item { LabCard { Text("Live diagnostics", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Button(onClick = vm::refreshDiagnostics) { Text("Refresh") }; val d = vm.diagnostics; Text("Input RMS ${"%.5f".format(Locale.US, d[0])}\nOutput RMS ${"%.5f".format(Locale.US, d[1])}\nInput peak ${"%.5f".format(Locale.US, d[2])} · Output peak ${"%.5f".format(Locale.US, d[3])}\nMax difference ${"%.5f".format(Locale.US, d[4])}\nProcess calls ${d[5].toLong()} · result code ${d[6].toInt()}", color = Color.LightGray, fontSize = 13.sp) } }
            }
        }
    }
}

@Composable private fun LabCard(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)), content = content) }
@Composable private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) { Column { Row { Text(label, Modifier.weight(1f)); Text("%.3f".format(Locale.US, value), color = Color.Gray) }; Slider(value = value, onValueChange = onChange, valueRange = range) } }
@Composable private fun PresetRow(vm: LabViewModel) { val names = listOf("Off", "Small", "Studio", "Hall", "Cathedral", "Subway"); Column { Text("Room preset", color = Color.Gray); Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { names.forEachIndexed { i, name -> FilterChip(selected = vm.preset == i, onClick = { vm.updatePreset(i) }, label = { Text(name) }) } } } }
private fun formatMs(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
