@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.vxs.frostsoulxdsp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DspLabApp() }
    }
}

data class LocalTrack(val id: Long, val title: String, val artist: String, val uri: Uri, val durationMs: Long)
internal val Ice = Color(0xFF73E1D4)
internal val Muted = Color(0xFF9AAAC1)
internal val Panel = Color(0xFF141E2B)
internal val Amber = Color(0xFFF3C47B)
internal val Violet = Color(0xFFB9A3FF)

@Composable fun DspLabApp(vm: LabViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val activeTab by rememberUpdatedState(tab)
    val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.scan(context) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.openAudio(context, uri) } }
    val bundlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importBundle(context, uri) } }
    val exportActive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> vm.export(context, uri, false) } }
    val exportStaged = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let { uri -> vm.export(context, uri, true) } }
    LaunchedEffect(Unit) {
        vm.initialize(context)
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.scan(context)
    }
    LaunchedEffect(lifecycle, vm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { while (true) { vm.refreshDiagnostics(activeTab == 1); delay(if (activeTab == 1) 150L else 400L) } }
            finally { vm.setProfiling(false) }
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Ice, onPrimary = Color(0xFF003B35), secondary = Violet,
        background = Color(0xFF0A111C), surface = Panel, surfaceVariant = Color(0xFF1C2939),
        onSurface = Color(0xFFE6EDF7), onSurfaceVariant = Muted, outline = Color(0xFF34445B)
    )) {
        Scaffold(
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SignalMark(Modifier.size(30.dp))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("FrostSoulX", fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
                        Text("NATIVE DSP LABORATORY", color = Muted, fontSize = 9.sp, letterSpacing = 1.6.sp)
                    }
                    StatusPill(if (vm.busy) "LOADING" else if (NativeEngine.available) "C++ / v1" else "UNAVAILABLE", if (NativeEngine.available) Ice else Amber)
                }
            },
            bottomBar = {
                Column {
                    if (vm.selected != null) MiniPlayer(vm, { tab = 1 })
                    NavigationBar(containerColor = Color(0xFF0E1723), tonalElevation = 0.dp, modifier = Modifier.height(76.dp)) {
                        listOf("Library", "Diagnostics", "Engine").forEachIndexed { index, label ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { NavMark(index, tab == index) }, label = { Text(label, fontSize = 11.sp) })
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> LibraryScreen(vm, scan = {
                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) vm.scan(context)
                        else permissionLauncher.launch(permission)
                    }, open = { audioPicker.launch(arrayOf("audio/*")) }, diagnostics = { tab = 1 })
                    1 -> DiagnosticScreen(vm)
                    else -> EngineScreen(vm, { bundlePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        { exportActive.launch("frostsoulx-engine.zip") }, { exportStaged.launch("frostsoulx-staged-engine.zip") })
                }
            }
        }
    }
}

@Composable private fun LibraryScreen(vm: LabViewModel, scan: () -> Unit, open: () -> Unit, diagnostics: () -> Unit) {
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(vm.tracks, search) { vm.tracks.filter { it.title.contains(search, true) || it.artist.contains(search, true) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { SectionTitle("Audio library", "Your local reference material") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = open, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Open file") }
                OutlinedButton(onClick = scan, enabled = !vm.scanning, modifier = Modifier.weight(1f)) { Text(if (vm.scanning) "Scanning…" else "Scan device") }
            }
        }
        item { OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("Search title or artist") },
            singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) }
        item {
            LabCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.enabled && !vm.offloadRequested) "Processed reference" else "Original reference", fontWeight = FontWeight.SemiBold)
                        Caption(if (vm.offloadRequested) "Offload requested · DSP bypassed" else "PCM → native engine → output")
                    }
                    TextButton(onClick = diagnostics) { Text("Inspect") }
                }
            }
        }
        item { Row { Caption("${filtered.size} FILES", Modifier.weight(1f)); Caption("LOCAL / ON DEVICE") } }
        if (filtered.isEmpty()) item { LabCard { Text(if (search.isNotEmpty()) "No matching files" else "Choose your first reference", fontWeight = FontWeight.SemiBold); Caption(vm.libraryMessage) } }
        items(filtered, key = { it.uri.toString() }) { track ->
            val selected = track.uri == vm.selected?.uri
            Surface(shape = RoundedCornerShape(14.dp), color = if (selected) Color(0xFF1B343D) else Panel,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.play(context, track) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(42.dp).background(if (selected) Ice.copy(alpha = .12f) else Color(0xFF223044), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        SignalMark(Modifier.size(23.dp), if (selected) Ice else Muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
                    }
                    Text(if (track.durationMs > 0) formatMs(track.durationMs) else "FILE", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (selected) Ice else Muted)
                }
            }
        }
        item { Caption("Audio stays on your device. The lab uses no network service.") }
    }
}

@Composable private fun MiniPlayer(vm: LabViewModel, open: () -> Unit) {
    val context = LocalContext.current
    Surface(color = Color(0xFF1B2A3C), tonalElevation = 0.dp) {
        Column {
            LinearProgressIndicator(progress = { if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = Ice, trackColor = Color(0xFF2A3A50))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = open).padding(vertical = 8.dp)) {
                    Text(vm.selected?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Caption("${if (vm.playing) "PLAYING" else "PAUSED"}  ·  ${formatMs(vm.positionMs)}  ·  ${if (vm.enabled && !vm.offloadRequested) "B / DSP" else "A / DRY"}")
                }
                IconButton(onClick = { vm.setEnabled(!vm.enabled) }, enabled = !vm.offloadRequested && !vm.busy,
                    modifier = Modifier.semantics { contentDescription = "Toggle original A and processed B" }) {
                    Text(if (vm.enabled) "B" else "A", color = Ice, fontWeight = FontWeight.Bold)
                }
                PlayButton(vm.playing, !vm.busy) { vm.transport(context) }
            }
        }
    }
}

@Composable private fun EngineScreen(vm: LabViewModel, importZip: () -> Unit, export: () -> Unit, exportStaged: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Load native engine?") },
        text = { Text("Only load binaries you compiled or trust. A checksum verifies file integrity, not safety. Native code runs inside this app and can crash it. Playback will stop; the previous engine is retained if loading fails.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.activate(context) }) { Text("Trust & activate") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Engine workbench", "Compile C++. Import ZIP. Listen. Repeat.") }
        item { LabCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Native module", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); StatusPill("ABI 1", Ice) }
            Text(vm.engineLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ice, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Caption(vm.importStatus)
            if (!NativeEngine.available) Text(NativeEngine.loadError ?: "Native host unavailable", color = Amber, fontSize = 12.sp)
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = importZip, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Import ZIP") }
                OutlinedButton(onClick = export, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Export active") }
            }
            if (vm.stagedKind == "Compiled") Button(onClick = { confirm = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Activate compiled engine") }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { vm.unload(context) }, enabled = !vm.busy) { Text("Unload / use built-in") }
                TextButton(onClick = exportStaged, enabled = !vm.busy && vm.stagedKind != null) { Text("Export staged") }
            }
            Caption("One host install, then native-only updates. Source ZIPs need C++ compilation first. Steam Audio/runtime dependencies must match the installed host.")
        } }
        item { LabCard {
            Text("Playback route", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Request hardware offload", fontSize = 14.sp); Caption("Separate from exporting an engine ZIP") }
                Switch(checked = vm.offloadRequested, onCheckedChange = { vm.setOffload(context, it) }, enabled = !vm.busy)
            }
            Caption(if (vm.offloadRequested) "Hardware activation is device/codec dependent, not confirmed by this preference. DSP is bypassed; native telemetry is hidden."
                else "Offload disabled. Decoded stereo PCM runs through the diagnostic processor. Other channel layouts pass through unchanged.")
        } }
        item { LabCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Spatial engine", fontWeight = FontWeight.SemiBold); Caption("Original A / processed B") }
                Switch(checked = vm.enabled, onCheckedChange = vm::setEnabled, enabled = !vm.offloadRequested && !vm.busy)
            }
            ComparisonControls(vm)
            SliderRow("Spatial intensity", vm.intensity, 0f..1f, vm::updateIntensity)
            Caption("ROOM PRESET")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Off", "Small", "Studio", "Hall", "Cathedral", "Subway").forEachIndexed { i, name ->
                    FilterChip(selected = vm.preset == i, onClick = { vm.updatePreset(i) }, label = { Text(name) })
                }
            }
            SliderRow("Room mix", vm.roomMix, 0f..1f, vm::updateRoomMix)
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) { Text(if (advanced) "Hide room tuning" else "Fine-tune room & stereo") }
            if (advanced) {
                SliderRow("Early reflections", vm.reflection, 0f..1f, vm::updateReflection)
                SliderRow("Reverb time · seconds", vm.reverb, .2f..8f, vm::updateReverb)
                SliderRow("Room size", vm.roomSize, 0f..1f, vm::updateRoomSize)
                SliderRow("Dampening", vm.dampening, 0f..1f, vm::updateDampening)
                SliderRow("Stereo width", vm.width, 0f..1f, vm::updateWidth)
            }
            Caption("Controls apply at native block boundaries. A is original PCM; B is processed, not loudness-matched. Bypass retains your tuning.")
        } }
    }
}

@Composable internal fun ComparisonControls(vm: LabViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(selected = !vm.enabled || vm.offloadRequested, onClick = { vm.setEnabled(false) }, label = { Text("A · Original") }, enabled = !vm.busy, modifier = Modifier.weight(1f))
        FilterChip(selected = vm.enabled && !vm.offloadRequested, onClick = { vm.setEnabled(true) }, label = { Text("B · Processed") }, enabled = !vm.offloadRequested && !vm.busy, modifier = Modifier.weight(1f))
    }
}
@Composable internal fun LabCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Panel) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable internal fun Caption(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = Muted, fontSize = 11.sp, lineHeight = 16.sp) }
@Composable internal fun SectionTitle(title: String, subtitle: String) { Column(Modifier.padding(bottom = 4.dp)) {
    Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp)
    Text(subtitle, color = Muted, fontSize = 12.sp)
} }
@Composable internal fun StatusPill(text: String, color: Color) {
    Text(text, Modifier.background(color.copy(alpha = .10f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
}
@Composable internal fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row { Text(label, Modifier.weight(1f), fontSize = 13.sp); Text("%.2f".format(Locale.US, value), fontFamily = FontFamily.Monospace, color = Ice, fontSize = 12.sp) }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}
@Composable internal fun PlayButton(playing: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = if (playing) "Pause" else "Play" }) {
        Canvas(Modifier.size(20.dp)) {
            val color = Color(0xFF003B35)
            if (playing) {
                drawRect(color, Offset(2f, 0f), Size(size.width * .28f, size.height))
                drawRect(color, Offset(size.width * .64f, 0f), Size(size.width * .28f, size.height))
            } else drawPath(Path().apply { moveTo(size.width * .2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .2f, size.height); close() }, color)
        }
    }
}
@Composable private fun SignalMark(modifier: Modifier, color: Color = Ice) {
    Canvas(modifier) {
        listOf(.35f, .72f, 1f, .55f, .3f).forEachIndexed { i, h ->
            val x = size.width * (i + .5f) / 5
            drawLine(color, Offset(x, size.height * (1 - h) / 2), Offset(x, size.height * (1 + h) / 2), size.width / 12)
        }
    }
}
@Composable private fun NavMark(index: Int, selected: Boolean) {
    val color = if (selected) Ice else Muted
    Canvas(Modifier.size(20.dp)) {
        when (index) {
            0 -> repeat(3) { i -> drawLine(color, Offset(2f, size.height * (i + .5f) / 3), Offset(size.width - 2f, size.height * (i + .5f) / 3), 2.dp.toPx()) }
            1 -> drawPath(Path().apply { moveTo(0f, size.height * .5f); lineTo(size.width * .25f, size.height * .5f); lineTo(size.width * .4f, 0f); lineTo(size.width * .6f, size.height); lineTo(size.width * .75f, size.height * .5f); lineTo(size.width, size.height * .5f) }, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            else -> repeat(3) { i -> val x = size.width * (i + .5f) / 3; drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx()); drawCircle(color, 2.5.dp.toPx(), Offset(x, size.height * if (i == 1) .3f else .7f)) }
        }
    }
}
internal fun formatMs(ms: Long) = "%d:%02d".format(Locale.US, ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)
