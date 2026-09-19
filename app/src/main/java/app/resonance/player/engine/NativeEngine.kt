package app.resonance.player.engine

import java.nio.ByteBuffer

/** JNI entry points into libresonance_bridge.so (see app/src/main/cpp). */
object NativeEngine {
    init {
        System.loadLibrary("resonance_bridge")
    }

    /** Loads [preload] libraries, then [entryPath], then creates an engine. Returns 0 on failure. */
    external fun nativeOpen(entryPath: String, preload: Array<String>, sampleRate: Int, channels: Int): Long
    external fun nativeSetParam(handle: Long, id: String, value: Float)

    /** [buffer] must be a direct buffer holding [frames] interleaved stereo floats. Processed in place. */
    external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int)
    external fun nativeReset(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativeLastError(): String
}
