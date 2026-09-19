package app.resonance.player.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resonance.player.MainViewModel
import app.resonance.player.data.Track

@Composable
fun LibraryScreen(vm: MainViewModel, onOpenPlayer: () -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val message by vm.scanMessage.collectAsState()
    val currentId by vm.currentId.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.scanIfAllowed()
    }
    fun scanNow() {
        if (vm.hasAudioPermission()) vm.scan() else launcher.launch(permissions)
    }

    val shown = remember(tracks, query) {
        val q = query.trim()
        if (q.isEmpty()) tracks
        else tracks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Mist)
                Text(
                    message ?: if (tracks.isEmpty()) "No songs yet" else "${tracks.size} songs, saved on this device",
                    color = MistDim, fontSize = 13.sp,
                )
            }
            Pill(if (scanning) "Scanning" else "Scan music", selected = false, onClick = { if (!scanning) scanNow() })
        }
        Spacer(Modifier.height(14.dp))

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Scan the music on this phone", color = Mist, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Resonance keeps each song's file path, so your library is there next time you open the app.",
                        color = MistDim, fontSize = 14.sp,
                    )
                    Pill("Grant access and scan", selected = true, onClick = { scanNow() })
                }
            }
        } else {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search title, artist or album") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = Slate,
                    unfocusedContainerColor = Slate,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { it.id }) { t ->
                    TrackRow(t, playing = t.id.toString() == currentId) {
                        vm.play(shown, shown.indexOf(t))
                        onOpenPlayer()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (playing) Tide else SlateHi),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                t.title.take(1).uppercase(),
                color = if (playing) Ink else MistDim,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) Tide else Mist, fontSize = 15.sp,
            )
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MistDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(fmtTime(t.durationMs), color = MistDim, fontSize = 12.sp)
    }
}
