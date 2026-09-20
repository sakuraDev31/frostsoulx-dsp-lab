package app.resonance.player.data

data class Track(
    val id: Long,
    val uri: String,      // content:// URI used for playback (works under scoped storage)
    val path: String,     // file path reported by MediaStore, saved so the library survives restarts
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
)
