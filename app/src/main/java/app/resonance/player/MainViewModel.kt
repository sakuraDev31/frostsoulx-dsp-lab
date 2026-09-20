package app.resonance.player

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.resonance.player.data.LibraryStore
import app.resonance.player.data.Track
import app.resonance.player.engine.EngineManager
import app.resonance.player.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext

    private val _tracks = MutableStateFlow(LibraryStore.load(ctx))
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    val scanning = MutableStateFlow(false)
    val scanMessage = MutableStateFlow<String?>(null)

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = future.get()
            c.addListener(listener)
            controller = c
            sync(c)
        }, ContextCompat.getMainExecutor(ctx))

        viewModelScope.launch {
            while (isActive) {
                controller?.let { _position.value = it.currentPosition.coerceAtLeast(0L) }
                delay(250)
            }
        }

        // Quiet refresh on launch so new songs show up; the saved library is already on screen.
        if (hasAudioPermission()) scan()
    }

    private fun sync(p: Player) {
        _playing.value = p.isPlaying
        _currentId.value = p.currentMediaItem?.mediaId
        _duration.value = if (p.duration == C.TIME_UNSET) 0L else p.duration
        _shuffle.value = p.shuffleModeEnabled
        _repeat.value = p.repeatMode
    }

    // --- library ------------------------------------------------------------------------

    fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun scan() {
        if (scanning.value) return
        scanning.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LibraryStore.scan(ctx).also { LibraryStore.save(ctx, it) } }
            }
            result
                .onSuccess {
                    _tracks.value = it
                    scanMessage.value = "${it.size} songs found. Paths are saved on this device."
                }
                .onFailure { scanMessage.value = "Scan failed: ${it.message}" }
            scanning.value = false
        }
    }

    fun scanIfAllowed() {
        if (hasAudioPermission()) scan()
        else scanMessage.value = "Allow access to audio files to scan your music."
    }

    // --- playback -----------------------------------------------------------------------

    fun play(list: List<Track>, index: Int) {
        val c = controller ?: return
        if (index !in list.indices) return
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() { controller?.seekToNext() }
    fun previous() { controller?.seekToPrevious() }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        _position.value = ms
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // --- sound engine -------------------------------------------------------------------

    fun importEngine(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { EngineManager.importZip(uri) }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onCleared()
    }
}

private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
        )
        .build()
