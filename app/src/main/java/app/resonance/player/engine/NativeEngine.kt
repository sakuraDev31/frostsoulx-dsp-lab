package app.resonance.player.engine

import java.nio.ByteBuffer

/** JNI entry points into libresonance_bridge.so (see app/src/main/cpp). */
object NativeEngine {
    /** False when the native library could not be loaded; every call below then no-ops. */
    val available: Boolean = runCatching { System.loadLibrary("resonance_bridge") }.isSuccess

    /** Loads [preload] libraries, then [entryPath], then creates an engine. Returns 0 on failure. */
    external fun nativeOpen(entryPath: String, preload: Array<String>, sampleRate: Int, channels: Int): Long
    external fun nativeSetParam(handle: Long, id: String, value: Float)

    /**
     * [buffer] must be a direct buffer holding [frames] interleaved stereo floats. Processed in
     * place: measured, run through the engine at the configured quantum, then sanitised
     * (NaN/Inf -> 0, clamped to [-1, 1]) and measured again on the way out.
     */
    external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int)

    /** Measures + sanitises a block that the engine did not touch (bypass / no engine). */
    external fun nativeMeter(buffer: ByteBuffer, frames: Int)

    external fun nativeReset(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativeLastError(): String

    // --- internal DSP processing quantum ---------------------------------------------------
    // Allocates: only call at a safe reconfiguration boundary, never mid-block.

    external fun nativeSetQuantum(handle: Long, quantum: Int, maxHostFrames: Int): Boolean

    /** Frames the engine actually receives per call; 0 when it follows the host block size. */
    external fun nativeQuantum(handle: Long): Int

    /** Extra latency in frames introduced by the quantum FIFO (0 in AUTO). */
    external fun nativeLatencyFrames(handle: Long): Int

    // --- telemetry ---------------------------------------------------------------------------

    external fun nativeConfigureTelemetry(sampleRate: Int)
    external fun nativeSetBypass(bypass: Boolean)
    external fun nativeSetEngineActive(active: Boolean)
    external fun nativeResetStats()

    /** Fills [out] with [nativeSlotCount] doubles. Returns the number written. */
    external fun nativeSnapshot(out: DoubleArray): Int

    /** Magnitude spectrum in dBFS of the newest [fftSize] frames. Returns bins written. */
    external fun nativeSpectrum(out: FloatArray, fftSize: Int): Int

    external fun nativeSlotCount(): Int

    external fun nativeBenchmarkStart(durationMs: Long)
    external fun nativeBenchmarkStop()

    // --- audio sink telemetry ----------------------------------------------------------------
    // Fed by Media3's AnalyticsListener from the player's application thread, never from the
    // audio callback. Kept apart from the quantum FIFO's own underflow counter so a sink-level
    // dropout can be told apart from a DSP-level one.

    /** One `AnalyticsListener.onAudioUnderrun`. Pass a negative value if the time is unknown. */
    external fun nativeNoteSinkUnderrun(elapsedSinceLastFeedMs: Long)

    /** One `AnalyticsListener.onAudioSinkError`. */
    external fun nativeNoteSinkError()

    /** AudioTrack buffer size in frames, from `AnalyticsListener.onAudioTrackInitialized`. */
    external fun nativeSetSinkBufferFrames(frames: Int)
}
