package app.resonance.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resonance.player.MainViewModel
import app.resonance.player.engine.EngineManager
import app.resonance.player.engine.EngineParam
import app.resonance.player.engine.InstalledEngine
import app.resonance.player.engine.ParamType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

@Composable
fun SoundScreen(vm: MainViewModel) {
    val installed by EngineManager.installed.collectAsState()
    val active by EngineManager.active.collectAsState()
    val values by EngineManager.values.collectAsState()
    val enabled by EngineManager.enabled.collectAsState()
    val message by EngineManager.message.collectAsState()
    val levels = rememberLevels()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importEngine(uri)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
    ) {
        item {
            Column {
                Text("Sound", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Mist)
                Text(
                    when {
                        active == null -> "No engine loaded"
                        enabled -> "Engine on"
                        else -> "Engine bypassed"
                    },
                    color = MistDim, fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                LevelMeters(levels)
            }
        }

        item {
            EngineCard(
                active = active,
                installed = installed,
                enabled = enabled,
                onImport = { picker.launch(ZIP_TYPES) },
            )
        }

        message?.let { msg ->
            item {
                Text(
                    msg,
                    color = Mist, fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SlateHi)
                        .clickable { EngineManager.dismissMessage() }
                        .padding(14.dp),
                )
            }
        }

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

            item {
                Pill("Reset to defaults", selected = false, onClick = { EngineManager.resetToDefaults() })
            }
        }
    }
}

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
