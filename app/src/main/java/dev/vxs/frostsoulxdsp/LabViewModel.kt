package dev.vxs.frostsoulxdsp

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LabViewModel : ViewModel() {
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
    var engineStatus by mutableStateOf("Select a local audio file"); private set
    var importStatus by mutableStateOf("Built-in engine · C ABI v1"); private set
    var engineLabel by mutableStateOf("Built-in engine"); private set
    var telemetry by mutableStateOf(EngineTelemetry.EMPTY); private set
    var spectrum by mutableStateOf(floatArrayOf()); private set
    var stageNames by mutableStateOf<List<String>>(emptyList()); private set
    var playing by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var libraryMessage by mutableStateOf("Scan your device or open an audio file"); private set
    var profiling by mutableStateOf(false); private set
    var formatDescription by mutableStateOf("Waiting for decoded PCM"); private set
    var stagedKind by mutableStateOf<String?>(null); private set
    private var initialized = false
    private var player: ExoPlayer? = null
    private val processor = LabAudioProcessor()
    private var lastSpectrumCall = -1L

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        bundleAction(context) { EngineBundles.restore(it) }
    }

    fun scan(context: Context) {
        if (scanning) return
        scanning = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                tracks = withContext(Dispatchers.IO) {
                    val result = mutableListOf<LocalTrack>()
                    val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
                    app.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            result += LocalTrack(id, c.getString(1) ?: "Untitled audio", c.getString(2) ?: "Unknown artist",
                                Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()), c.getLong(3))
                        }
                    }
                    app.getSharedPreferences("dsp_lab", Context.MODE_PRIVATE).edit()
                        .putStringSet("retained_uris", result.map { it.uri.toString() }.toSet()).apply()
                    result
                }
                libraryMessage = if (tracks.isEmpty()) "No indexed music found. Use Open file to choose audio." else "${tracks.size} local files"
            } catch (error: Exception) { libraryMessage = "Scan unavailable: ${error.message}. Use Open file instead." }
            finally { scanning = false }
        }
    }

    fun openAudio(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val app = context.applicationContext
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } }.getOrNull() ?: "Selected audio"
            }
            val track = LocalTrack(uri.toString().hashCode().toLong(), name, "Document audio", uri, 0)
            if (tracks.none { it.uri == uri }) tracks = listOf(track) + tracks
            play(app, track)
        }
    }

    fun play(context: Context, track: LocalTrack, position: Long = 0, resume: Boolean = true) {
        if (busy) return
        releasePlayer()
        selected = track; positionMs = position; durationMs = track.durationMs
        telemetry = EngineTelemetry.EMPTY; spectrum = floatArrayOf(); lastSpectrumCall = -1
        processor.enabled = enabled && !offloadRequested
        player = ExoPlayer.Builder(context.applicationContext, LabRenderersFactory(context.applicationContext, processor)).build().also { exo ->
            exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            exo.setHandleAudioBecomingNoisy(true)
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    engineStatus = when (playbackState) {
                        Player.STATE_BUFFERING -> "Buffering local audio"
                        Player.STATE_ENDED -> "Playback complete"
                        Player.STATE_READY -> "Ready"
                        else -> "Stopped"
                    }
                }
                override fun onPlayerError(error: PlaybackException) { engineStatus = "Playback error: ${error.errorCodeName}" }
            })
            applyOffload(exo, offloadRequested)
            exo.setMediaItem(MediaItem.fromUri(track.uri))
            exo.seekTo(position); exo.prepare(); exo.playWhenReady = resume
        }
    }
    fun transport(context: Context) {
        val exo = player
        if (exo == null) selected?.let { play(context, it, positionMs) }
        else if (exo.playWhenReady) exo.pause() else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }
    fun seek(ms: Long) { player?.seekTo(ms.coerceIn(0, durationMs.coerceAtLeast(0))); NativeEngine.reset() }
    fun pause() { player?.pause() }
    fun adjacent(context: Context, delta: Int) {
        val index = tracks.indexOfFirst { it.uri == selected?.uri }
        tracks.getOrNull(index + delta)?.let { play(context, it) }
    }
    private fun releasePlayer() {
        player?.let { positionMs = it.currentPosition; it.release() }
        player = null; playing = false
    }
    fun setEnabled(value: Boolean) {
        enabled = value
        processor.enabled = value && !offloadRequested
        NativeEngine.setEnabled(processor.enabled)
    }
    fun updateIntensity(v: Float) { intensity = v; NativeEngine.setIntensity(v) }
    fun updateRoomMix(v: Float) { roomMix = v; NativeEngine.setRoomMix(v) }
    fun updateReflection(v: Float) { reflection = v; NativeEngine.setReflectionAmount(v) }
    fun updateReverb(v: Float) { reverb = v; NativeEngine.setReverbTime(v) }
    fun updateRoomSize(v: Float) { roomSize = v; NativeEngine.setRoomSize(v) }
    fun updateDampening(v: Float) { dampening = v; NativeEngine.setDampening(v) }
    fun updateWidth(v: Float) { width = v; NativeEngine.setWidth(v) }
    fun updatePreset(v: Int) { preset = v; NativeEngine.setRoomPreset(v) }
    fun setProfiling(value: Boolean) { profiling = value; NativeEngine.setProfiling(value) }
    fun setOffload(context: Context, value: Boolean) {
        if (busy || value == offloadRequested) return
        val resume = playing
        val pos = player?.currentPosition ?: positionMs
        offloadRequested = value
        processor.enabled = enabled && !value
        NativeEngine.setEnabled(processor.enabled)
        // Recreate the sink; do not assume an already-configured AudioTrack changes path.
        selected?.let { play(context, it, pos, resume) }
    }
    private fun applyOffload(exo: ExoPlayer, enabled: Boolean) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(AudioOffloadPreferences.Builder().setAudioOffloadMode(
                if (enabled) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            ).build()).build()
    }
    suspend fun refreshDiagnostics(visualize: Boolean) {
        player?.let { positionMs = it.currentPosition; durationMs = it.duration.takeIf { d -> d > 0 } ?: selected?.durationMs ?: 0 }
        formatDescription = processor.formatDescription
        val snapshot = withContext(Dispatchers.Default) { EngineTelemetry.decode(NativeEngine.diagnostics()) }
        telemetry = snapshot
        stageNames = NativeEngine.stageNames()
        if (visualize && snapshot.fresh && snapshot.calls != lastSpectrumCall && playing && !offloadRequested) {
            spectrum = withContext(Dispatchers.Default) { spectrumOf(snapshot.waveform) }
            lastSpectrumCall = snapshot.calls
        } else if (!snapshot.fresh || !playing || offloadRequested) spectrum = floatArrayOf()
    }
    fun resetDiagnostics() { NativeEngine.reset(); telemetry = EngineTelemetry.EMPTY; spectrum = floatArrayOf() }

    fun importBundle(context: Context, uri: Uri) {
        if (busy) return
        val app = context.applicationContext
        busy = true
        viewModelScope.launch {
            try {
                stagedKind = withContext(Dispatchers.IO) { EngineBundles.stage(app, uri) }
                importStatus = if (stagedKind == "Compiled") "Compiled ZIP validated. Activate after confirming you trust its native code."
                    else "Source ZIP staged. Compile only the C++ engine, then import the compiled ZIP."
            } catch (error: Exception) { importStatus = "Import rejected: ${error.message}"; stagedKind = null }
            finally { busy = false }
        }
    }
    fun activate(context: Context) = bundleAction(context) { EngineBundles.activateStaged(it) }
    fun unload(context: Context) = bundleAction(context) { EngineBundles.unload(it) }
    private fun bundleAction(context: Context, action: (Context) -> String) {
        if (busy) return
        busy = true
        releasePlayer() // joins/releases Media3 before any dlclose, never hot-swap a callback
        telemetry = EngineTelemetry.EMPTY; spectrum = floatArrayOf(); stageNames = emptyList()
        val app = context.applicationContext
        viewModelScope.launch {
            try { importStatus = withContext(Dispatchers.IO) { action(app) } }
            catch (error: Exception) { importStatus = "Engine operation failed: ${error.message}" }
            finally { engineLabel = EngineBundles.activeLabel(app); busy = false }
        }
    }
    fun export(context: Context, uri: Uri, staged: Boolean) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { if (staged) EngineBundles.exportStaged(app, uri) else EngineBundles.exportActive(app, uri) }
                importStatus = if (staged) "Staged ZIP exported" else "Active engine SDK exported"
            } catch (error: Exception) { importStatus = "Export failed: ${error.message}" }
            finally { busy = false }
        }
    }
    override fun onCleared() { releasePlayer() }
}
