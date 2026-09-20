package app.resonance.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Left channel is Tide, right channel is Rose everywhere a channel is drawn.
val Ink = Color(0xFF090B12)
val Slate = Color(0xFF131826)
val SlateHi = Color(0xFF1B2233)
val Mist = Color(0xFFE8ECF4)
val MistDim = Color(0xFF8B94A8)
val Tide = Color(0xFF6CC8FF)
val Rose = Color(0xFFFF7E9D)

private val scheme = darkColorScheme(
    primary = Tide,
    onPrimary = Ink,
    secondary = Rose,
    onSecondary = Ink,
    background = Ink,
    onBackground = Mist,
    surface = Slate,
    onSurface = Mist,
    surfaceVariant = SlateHi,
    onSurfaceVariant = MistDim,
    outline = Color(0xFF2A3247),
)

@Composable
fun ResonanceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
