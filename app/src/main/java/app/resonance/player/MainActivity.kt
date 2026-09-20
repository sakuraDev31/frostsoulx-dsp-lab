package app.resonance.player

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resonance.player.ui.Glyph
import app.resonance.player.ui.GlyphButton
import app.resonance.player.ui.Ink
import app.resonance.player.ui.LibraryScreen
import app.resonance.player.ui.Mist
import app.resonance.player.ui.MistDim
import app.resonance.player.ui.PlayerScreen
import app.resonance.player.ui.ResonanceTheme
import app.resonance.player.ui.Slate
import app.resonance.player.ui.SlateHi
import app.resonance.player.ui.SoundScreen
import app.resonance.player.ui.Tide

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            ResonanceTheme {
                App(vm)
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Column {
                if (tab != 1) MiniPlayer(vm, onOpen = { tab = 1 })
                NavigationBar(containerColor = Slate) {
                    val colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Ink,
                        selectedTextColor = Tide,
                        indicatorColor = Tide,
                        unselectedIconColor = MistDim,
                        unselectedTextColor = MistDim,
                    )
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Library") },
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("Playing") },
                    )
                    NavigationBarItem(
                        selected = tab == 2, onClick = { tab = 2 }, colors = colors,
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Sound") },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
            when (tab) {
                0 -> LibraryScreen(vm, onOpenPlayer = { tab = 1 })
                1 -> PlayerScreen(vm)
                else -> SoundScreen(vm)
            }
        }
    }
}

@Composable
private fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val tracks by vm.tracks.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val playing by vm.playing.collectAsState()
    val track = remember(tracks, currentId) { tracks.firstOrNull { it.id.toString() == currentId } } ?: return

    Row(
        Modifier
            .fillMaxWidth()
            .background(SlateHi)
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                track.title, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = MistDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        GlyphButton(
            glyph = if (playing) Glyph.PAUSE else Glyph.PLAY,
            onClick = { vm.togglePlay() },
            boxSize = 52.dp,
        )
    }
}
