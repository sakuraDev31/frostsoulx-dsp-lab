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
                item { LabCard { Text("Playback path", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = vm.offloadRequested, onCheckedChange = { vm.setOffload(context, it) }); Text("Request hardware offload", Modifier.padding(start = 8.dp)) }; Text("Offload and custom PCM processing are mutually exclusive. The lab reports the conflict instead of silently claiming both are active.", color = Color.Gray, fontSize = 12.sp); Text(vm.engineStatus, color = Color.LightGray) } }
                item { Text("Device audio · ${vm.tracks.size} files", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                items(vm.tracks) { track -> Row(Modifier.fillMaxWidth().clickable { vm.play(context, track) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.Medium); Text(track.artist, color = Color.Gray, fontSize = 13.sp) }; Text(formatMs(track.durationMs), color = Color.Gray, fontSize = 12.sp) } }
                item { LabCard { Row(verticalAlignment = Alignment.CenterVertically) { Text("DSP", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Switch(checked = vm.enabled, onCheckedChange = { vm.setEnabled(!vm.enabled) }) }; Text("Selected: ${vm.selected?.title ?: "none"}", color = Color.Gray); SliderRow("Intensity", vm.intensity, 0f..1f, vm::updateIntensity); PresetRow(vm); SliderRow("Room mix", vm.roomMix, 0f..1f, vm::updateRoomMix); SliderRow("Reflection amount", vm.reflection, 0f..1f, vm::updateReflection); SliderRow("Reverb time (seconds)", vm.reverb, .2f..8f, vm::updateReverb); SliderRow("Room size", vm.roomSize, 0f..1f, vm::updateRoomSize); SliderRow("Dampening", vm.dampening, 0f..1f, vm::updateDampening); SliderRow("Stereo width", vm.width, 0f..1f, vm::updateWidth) } }
                item { LabCard { Text("Live diagnostics", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Button(onClick = vm::resetDiagnostics) { Text("Refresh") }; val d = doubleArrayOf(vm.telemetry.rms(false, 0), vm.telemetry.rms(true, 0), vm.telemetry.peak(false, 0), vm.telemetry.peak(true, 0), vm.telemetry.maxDifference, vm.telemetry.calls.toDouble(), vm.telemetry.resultCode.toDouble()); Text("Input RMS ${"%.5f".format(Locale.US, d[0])}\nOutput RMS ${"%.5f".format(Locale.US, d[1])}\nInput peak ${"%.5f".format(Locale.US, d[2])} · Output peak ${"%.5f".format(Locale.US, d[3])}\nMax difference ${"%.5f".format(Locale.US, d[4])}\nProcess calls ${d[5].toLong()} · result code ${d[6].toInt()}", color = Color.LightGray, fontSize = 13.sp) } }
            }
        }
    }
}

@Composable private fun LabCard(content: @Composable ColumnScope.() -> Unit) { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)), content = content) }
@Composable private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) { Column { Row { Text(label, Modifier.weight(1f)); Text("%.3f".format(Locale.US, value), color = Color.Gray) }; Slider(value = value, onValueChange = onChange, valueRange = range) } }
@Composable private fun PresetRow(vm: LabViewModel) { val names = listOf("Off", "Small", "Studio", "Hall", "Cathedral", "Subway"); Column { Text("Room preset", color = Color.Gray); Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { names.forEachIndexed { i, name -> FilterChip(selected = vm.preset == i, onClick = { vm.updatePreset(i) }, label = { Text(name) }) } } } }
private fun formatMs(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
