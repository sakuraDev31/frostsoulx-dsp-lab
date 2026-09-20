package app.resonance.player.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/** What the processor should load. Paths are absolute and live in app-private storage. */
data class LoadedEngine(val entryPath: String, val preloadPaths: List<String>)

/**
 * Media3 audio processor that runs the imported engine on the player's audio thread.
 *
 * - Stereo PCM16 / float only; anything else is left untouched (processor reports inactive).
 * - Engine loading, parameter changes and processing all happen on the audio thread, so an
 *   engine plugin never has to be thread safe.
 * - All measurement happens in native code inside the same pass that sanitises the output
 *   (see telemetry.cpp). Nothing here allocates, locks, logs or touches Compose per block.
 * - The UI talks to it only through [setEngine], [setParam], [bypass], [requestQuantum] and
 *   the level getters; everything else it needs comes from [TelemetryHub].
 */
@OptIn(UnstableApi::class)
class EngineProcessor : BaseAudioProcessor() {

    private class Request(val seq: Int, val engine: LoadedEngine?, val params: Map<String, Float>)

    @Volatile private var request = Request(0, null, emptyMap())
    private val pending = ConcurrentHashMap<String, Float>()

    @Volatile var bypass: Boolean = false
        set(value) {
            field = value
            if (NativeEngine.available) runCatching { NativeEngine.nativeSetBypass(value) }
        }

    @Volatile var onError: ((String) -> Unit)? = null

    /**
     * Requested internal DSP processing quantum in frames; 0 means AUTO (the engine sees the
     * host's own block size, adding no latency). Applied at the next safe reconfiguration
     * boundary, never mid-stream, because installing it allocates the FIFO.
     */
    @Volatile private var requestedQuantum: Int = 0
    @Volatile private var quantumSeq: Int = 0
    private var appliedQuantumSeq: Int = -1

    /** Set from the UI. Takes effect on the next engine (re)configuration. */
    fun requestQuantum(frames: Int) {
        requestedQuantum = frames.coerceIn(0, 4096)
        quantumSeq++
    }

    // Audio-thread state.
    private var appliedSeq = 0
    private var handle = 0L
    private var sampleRate = 0
    private var formatDirty = false
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var scratchFloats: FloatBuffer = scratch.asFloatBuffer()
    private var scratchFrames = 0

    /** PCM encoding of the stream currently flowing, for the hardware card. */
    @Volatile var encodingName: String = "\u2014"
        private set

    // Output level for the UI (0..1). Reads as 0 when audio has stopped flowing.
    @Volatile private var rawL = 0f
    @Volatile private var rawR = 0f
    @Volatile private var lastMeterNanos = 0L
    val levelL: Float get() = if (fresh()) rawL else 0f
    val levelR: Float get() = if (fresh()) rawR else 0f
    private fun fresh() = System.nanoTime() - lastMeterNanos < 250_000_000L

    /** Called from the UI thread. [params] is the full parameter state for the new engine. */
    fun setEngine(engine: LoadedEngine?, params: Map<String, Float>) {
        pending.clear()
        request = Request(request.seq + 1, engine, params)
    }

    /** Called from the UI thread; applied before the next audio buffer. */
    fun setParam(id: String, value: Float) {
        pending[id] = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.channelCount == 2 &&
            (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT)
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate != sampleRate) formatDirty = true
        sampleRate = inputAudioFormat.sampleRate
        encodingName = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) "Float32 PCM" else "16-bit PCM"
        if (NativeEngine.available) runCatching { NativeEngine.nativeConfigureTelemetry(sampleRate) }
        return inputAudioFormat
    }

    override fun onFlush() {
        // Seek / track change: clear tails but keep the engine instance (re-creating is expensive).
        if (handle != 0L) NativeEngine.nativeReset(handle)
        rawL = 0f
        rawR = 0f
    }

    override fun onReset() {
        closeHandle()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        syncEngine()
        val out = replaceOutputBuffer(size)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val frames = size / (if (isFloat) 8 else 4)
        if (frames <= 0) {
            out.flip()
            return
        }

        // Everything runs through the native float scratch buffer so input measurement,
        // processing and output measurement share one representation.
        ensureScratch(frames)
        val f = scratchFloats
        if (isFloat) {
            for (i in 0 until frames * 2) f.put(i, inputBuffer.getFloat())
        } else {
            for (i in 0 until frames * 2) f.put(i, inputBuffer.getShort() / 32768f)
        }
        inputBuffer.position(inputBuffer.limit())

        val h = handle
        if (h != 0L && !bypass) {
            NativeEngine.nativeProcess(h, scratch, frames)
        } else if (NativeEngine.available) {
            NativeEngine.nativeMeter(scratch, frames)
        }

        var pl = 0f
        var pr = 0f
        if (isFloat) {
            for (i in 0 until frames) {
                val l = f.get(2 * i)
                val r = f.get(2 * i + 1)
                out.putFloat(l)
                out.putFloat(r)
                val al = if (l < 0f) -l else l
                val ar = if (r < 0f) -r else r
                if (al > pl) pl = al
                if (ar > pr) pr = ar
            }
        } else {
            for (i in 0 until frames) {
                val l = f.get(2 * i)
                val r = f.get(2 * i + 1)
                out.putShort((l * 32767f).toInt().toShort())
                out.putShort((r * 32767f).toInt().toShort())
                val al = if (l < 0f) -l else l
                val ar = if (r < 0f) -r else r
                if (al > pl) pl = al
                if (ar > pr) pr = ar
            }
        }
        publishMeter(pl, pr)
        out.flip()
    }

    private fun syncEngine() {
        val r = request
        val qSeq = quantumSeq
        val engineChanged = r.seq != appliedSeq || formatDirty
        if (engineChanged) {
            closeHandle()
            appliedSeq = r.seq
            formatDirty = false
            appliedQuantumSeq = -1
            val e = r.engine
            if (e != null) {
                try {
                    val h = NativeEngine.nativeOpen(e.entryPath, e.preloadPaths.toTypedArray(), sampleRate, 2)
                    if (h == 0L) {
                        onError?.invoke(NativeEngine.nativeLastError())
                    } else {
                        handle = h
                        for ((k, v) in r.params) NativeEngine.nativeSetParam(h, k, v)
                    }
                } catch (t: Throwable) {
                    onError?.invoke("Native bridge unavailable: ${t.message}")
                }
            } else {
                runCatching { NativeEngine.nativeSetEngineActive(false) }
            }
        }

        // A quantum change reallocates the FIFO, so it is applied here (between buffers, with
        // the engine's tails flushed) and never in the middle of a block.
        if (handle != 0L && qSeq != appliedQuantumSeq) {
            appliedQuantumSeq = qSeq
            val q = requestedQuantum
            val maxHost = maxOf(scratchFrames, 4096)
            val ok = runCatching { NativeEngine.nativeSetQuantum(handle, q, maxHost) }.getOrDefault(false)
            if (!ok && q > 0) onError?.invoke("Could not allocate a $q-frame DSP quantum; staying on AUTO")
            NativeEngine.nativeReset(handle)
        }

        if (handle != 0L && pending.isNotEmpty()) {
            for (k in pending.keys.toList()) {
                val v = pending.remove(k) ?: continue
                NativeEngine.nativeSetParam(handle, k, v)
            }
        }
    }

    private fun closeHandle() {
        if (handle != 0L) {
            NativeEngine.nativeClose(handle)
            handle = 0L
        }
    }

    private fun ensureScratch(frames: Int) {
        if (scratchFrames >= frames) return
        // Grows only; steady-state playback never reallocates after the first few blocks.
        val target = maxOf(frames, scratchFrames * 2, 2048)
        scratch = ByteBuffer.allocateDirect(target * 8).order(ByteOrder.nativeOrder())
        scratchFloats = scratch.asFloatBuffer()
        scratchFrames = target
        // The FIFO was sized for the old maximum; re-arm it at the next sync.
        appliedQuantumSeq = -1
    }

    private fun publishMeter(pl: Float, pr: Float) {
        rawL = if (pl > rawL * 0.85f) pl else rawL * 0.85f
        rawR = if (pr > rawR * 0.85f) pr else rawR * 0.85f
        lastMeterNanos = System.nanoTime()
    }
}
