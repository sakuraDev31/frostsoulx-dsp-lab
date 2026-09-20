package app.resonance.player.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.resonance.player.engine.EngineManager
import app.resonance.player.engine.NativeEngine

/** Background playback. The imported engine sits in the audio sink's processor chain. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        EngineManager.init(this)

        val sink = DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf<AudioProcessor>(EngineManager.processor))
            .build()

        // Audio-only renderer set, so the code does not depend on DefaultRenderersFactory's
        // buildAudioSink signature (it changes between Media3 versions).
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener, sink)
            )
        }

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addAnalyticsListener(sinkTelemetry)

        session = MediaSession.Builder(this, player).build()
    }

    /**
     * Real dropout telemetry from the audio sink.
     *
     * These are the only dropout facts Android/Media3 actually exposes to an app, and they
     * describe the layer *below* the DSP: `onAudioUnderrun` fires when AudioTrack ran dry
     * waiting to be fed, which can happen even when the engine never missed a deadline. The
     * quantum FIFO's own underflow counter is measured separately in native code.
     *
     * AnalyticsListener callbacks arrive on the player's application thread, never on the
     * audio callback, so these JNI calls are off the realtime path.
     */
    private val sinkTelemetry = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            if (!NativeEngine.available) return
            runCatching { NativeEngine.nativeNoteSinkUnderrun(elapsedSinceLastFeedMs) }
        }

        override fun onAudioSinkError(
            eventTime: AnalyticsListener.EventTime,
            audioSinkError: Exception,
        ) {
            if (!NativeEngine.available) return
            runCatching { NativeEngine.nativeNoteSinkError() }
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            if (!NativeEngine.available) return
            // bufferSize is in bytes; convert with the track's real format rather than
            // assuming stereo 16-bit.
            val channels = Integer.bitCount(audioTrackConfig.channelConfig).coerceAtLeast(1)
            val bytesPerSample = when (audioTrackConfig.encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
                else -> 0   // compressed / offloaded: frames are not meaningful
            }
            val bytesPerFrame = bytesPerSample * channels
            if (bytesPerFrame <= 0) return
            runCatching {
                NativeEngine.nativeSetSinkBufferFrames(audioTrackConfig.bufferSize / bytesPerFrame)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
