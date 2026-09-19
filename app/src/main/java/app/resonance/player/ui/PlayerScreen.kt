package app.resonance.player.ui

import android.net.Uri
import android.util.Size as AndroidSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import app.resonance.player.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tracks by vm.tracks.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val playing by vm.playing.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val repeat by vm.repeat.collectAsState()
    val levels = rememberLevels()

    val track = remember(tracks, currentId) { tracks.firstOrNull { it.id.toString() == currentId } }

    // Embedded cover art; null when the file has none.
    val art by produceState<ImageBitmap?>(null, track?.uri) {
        val t = track
        value = if (t == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.loadThumbnail(Uri.parse(t.uri), AndroidSize(700, 700), null).asImageBitmap()
                }.getOrNull()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val backdrop = art
        if (backdrop != null) {
            Image(backdrop, null, Modifier.fillMaxSize().alpha(0.22f), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f), Ink))
            )
        )

        if (track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Pick a song in Library", color = MistDim, fontSize = 16.sp)
            }
            return@Box
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Orb(levels, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxWidth(0.56f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(SlateHi),
                    contentAlignment = Alignment.Center,
                ) {
                    val cover = art
                    if (cover != null) {
                        Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(track.title.take(1).uppercase(), fontSize = 56.sp, color = MistDim, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                track.title, color = Mist, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = MistDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (track.path.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(track.path, color = MistDim.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(10.dp))
            // Seek only when the finger lifts: every seek flushes the audio chain.
            var dragPos by remember { mutableStateOf<Float?>(null) }
            val shownPos = dragPos ?: position.toFloat()
            Fader(
                value = shownPos,
                min = 0f,
                max = duration.coerceAtLeast(1L).toFloat(),
                step = 0f,
                defaultValue = null,
                onChange = { dragPos = it },
                onChangeFinished = {
                    dragPos?.let { vm.seekTo(it.toLong()) }
                    dragPos = null
                },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(shownPos.toLong()), color = MistDim, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(duration), color = MistDim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("Shuffle", selected = shuffle, onClick = { vm.toggleShuffle() })
                GlyphButton(Glyph.PREV, onClick = { vm.previous() })
                GlyphButton(
                    if (playing) Glyph.PAUSE else Glyph.PLAY,
                    onClick = { vm.togglePlay() },
                    boxSize = 72.dp,
                    filled = true,
                )
                GlyphButton(Glyph.NEXT, onClick = { vm.next() })
                Pill(
                    when (repeat) {
                        Player.REPEAT_MODE_ALL -> "Repeat all"
                        Player.REPEAT_MODE_ONE -> "Repeat one"
                        else -> "Repeat"
                    },
                    selected = repeat != Player.REPEAT_MODE_OFF,
                    onClick = { vm.cycleRepeat() },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Rings pulse with the overall output level; the two glows are the left (Tide) and right (Rose) channels. */
@Composable
private fun Orb(levels: State<Pair<Float, Float>>, modifier: Modifier) {
    Canvas(modifier) {
        val (l0, r0) = levels.value
        val l = viz(l0)
        val r = viz(r0)
        val level = (l + r) / 2f
        val c = center
        val base = size.minDimension * 0.30f

        for (i in 0..3) {
            val radius = base * (1.05f + 0.16f * i) + size.minDimension * 0.10f * level * (i + 1) / 2f
            drawCircle(
                color = Tide.copy(alpha = 0.20f / (i + 1)),
                radius = radius,
                center = c,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        val leftCenter = Offset(c.x - base * 1.35f, c.y)
        val leftRadius = base * (0.35f + 0.55f * l)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Tide.copy(alpha = (0.15f + 0.55f * l).coerceIn(0f, 1f)), Color.Transparent),
                center = leftCenter, radius = leftRadius,
            ),
            radius = leftRadius, center = leftCenter,
        )
        val rightCenter = Offset(c.x + base * 1.35f, c.y)
        val rightRadius = base * (0.35f + 0.55f * r)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Rose.copy(alpha = (0.15f + 0.55f * r).coerceIn(0f, 1f)), Color.Transparent),
                center = rightCenter, radius = rightRadius,
            ),
            radius = rightRadius, center = rightCenter,
        )
    }
}
