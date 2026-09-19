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
import kotlin.math.abs

/** What the processor should load. Paths are absolute and live in app-private storage. */
data class LoadedEngine(val entryPath: String, val preloadPaths: List<String>)

/**
 * Media3 audio processor that runs the imported engine on the player's audio thread.
 *
 * - Stereo PCM16 / float only; anything else is left untouched (processor reports inactive).
 * - Engine loading, parameter changes and processing all happen on the audio thread, so an
 *   engine plugin never has to be thread safe.
 * - The UI talks to it only through [setEngine], [setParam], [bypass] and the level getters.
 */
@OptIn(UnstableApi::class)
class EngineProcessor : BaseAudioProcessor() {

    private class Request(val seq: Int, val engine: LoadedEngine?, val params: Map<String, Float>)

    @Volatile private var request = Request(0, null, emptyMap())
    private val pending = ConcurrentHashMap<String, Float>()

    @Volatile var bypass: Boolean = false
    @Volatile var onError: ((String) -> Unit)? = null

    // Audio-thread state.
    private var appliedSeq = 0
    private var handle = 0L
    private var sampleRate = 0
    private var formatDirty = false
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var scratchFloats: FloatBuffer = scratch.asFloatBuffer()

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
        val h = handle
        when {
            h == 0L || bypass -> meterAndCopy(inputBuffer, out, isFloat, frames)
            isFloat -> processFloat(h, inputBuffer, out, frames)
            else -> process16(h, inputBuffer, out, frames)
        }
        out.flip()
    }

    private fun syncEngine() {
        val r = request
        if (r.seq != appliedSeq || formatDirty) {
            closeHandle()
            appliedSeq = r.seq
            formatDirty = false
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
            }
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
        val bytes = frames * 8
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            scratchFloats = scratch.asFloatBuffer()
        }
    }

    private fun process16(h: Long, input: ByteBuffer, out: ByteBuffer, frames: Int) {
        ensureScratch(frames)
        val f = scratchFloats
        for (i in 0 until frames * 2) f.put(i, input.getShort() / 32768f)
        NativeEngine.nativeProcess(h, scratch, frames)
        var pl = 0f
        var pr = 0f
        for (i in 0 until frames) {
            val l = clean(f.get(2 * i))
            val r = clean(f.get(2 * i + 1))
            out.putShort((l * 32767f).toInt().toShort())
            out.putShort((r * 32767f).toInt().toShort())
            if (abs(l) > pl) pl = abs(l)
            if (abs(r) > pr) pr = abs(r)
        }
        input.position(input.limit())
        publishMeter(pl, pr)
    }

    private fun processFloat(h: Long, input: ByteBuffer, out: ByteBuffer, frames: Int) {
        ensureScratch(frames)
        val f = scratchFloats
        for (i in 0 until frames * 2) f.put(i, input.getFloat())
        NativeEngine.nativeProcess(h, scratch, frames)
        var pl = 0f
        var pr = 0f
        for (i in 0 until frames) {
            val l = clean(f.get(2 * i))
            val r = clean(f.get(2 * i + 1))
            out.putFloat(l)
            out.putFloat(r)
            if (abs(l) > pl) pl = abs(l)
            if (abs(r) > pr) pr = abs(r)
        }
        input.position(input.limit())
        publishMeter(pl, pr)
    }

    /** No engine (or bypassed): pass audio through unchanged, but still feed the level meters. */
    private fun meterAndCopy(input: ByteBuffer, out: ByteBuffer, isFloat: Boolean, frames: Int) {
        var pl = 0f
        var pr = 0f
        var pos = input.position()
        for (i in 0 until frames) {
            val l: Float
            val r: Float
            if (isFloat) {
                l = abs(input.getFloat(pos)); r = abs(input.getFloat(pos + 4)); pos += 8
            } else {
                l = abs(input.getShort(pos).toInt()) / 32768f
                r = abs(input.getShort(pos + 2).toInt()) / 32768f
                pos += 4
            }
            if (l > pl) pl = l
            if (r > pr) pr = r
        }
        out.put(input)
        publishMeter(pl.coerceAtMost(1f), pr.coerceAtMost(1f))
    }

    private fun publishMeter(pl: Float, pr: Float) {
        rawL = if (pl > rawL * 0.85f) pl else rawL * 0.85f
        rawR = if (pr > rawR * 0.85f) pr else rawR * 0.85f
        lastMeterNanos = System.nanoTime()
    }

    private fun clean(v: Float): Float = when {
        v != v -> 0f
        v > 1f -> 1f
        v < -1f -> -1f
        else -> v
    }
}
