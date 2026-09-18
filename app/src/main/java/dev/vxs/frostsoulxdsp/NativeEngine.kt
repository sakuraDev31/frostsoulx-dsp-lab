package dev.vxs.frostsoulxdsp

object NativeEngine {
    private val loadResult = runCatching { System.loadLibrary("frostsoulx_dsp_lab") }
    val available = loadResult.isSuccess
    val loadError: String? = loadResult.exceptionOrNull()?.message
    external fun nativeLoadPlugin(path: String?): String
    external fun nativeStageNames(): Array<String>
    fun loadPlugin(path: String?) = if (available) nativeLoadPlugin(path) else (loadError ?: "Native host unavailable")
    fun stageNames() = if (available) nativeStageNames().toList() else emptyList()
    external fun nativePrepare(rate: Int, maxFrames: Int): Boolean
    external fun nativeSetEnabled(value: Boolean)
    external fun nativeSetIntensity(value: Float)
    external fun nativeSetRoomPreset(value: Int)
    external fun nativeSetRoomMix(value: Float)
    external fun nativeSetReflectionAmount(value: Float)
    external fun nativeSetReverbTime(value: Float)
    external fun nativeSetRoomSize(value: Float)
    external fun nativeSetDampening(value: Float)
    external fun nativeSetWidth(value: Float)
    external fun nativeSetProfiling(value: Boolean)
    external fun nativeReset()
    external fun nativeDiagnostics(): DoubleArray
    external fun nativeProcess(pcm: FloatArray): Boolean
    external fun nativeProcessDirect(buffer: java.nio.ByteBuffer, frames: Int): Boolean
    fun prepare(rate: Int, frames: Int) = available && nativePrepare(rate, frames)
    fun setEnabled(v: Boolean) { if (available) nativeSetEnabled(v) }
    fun setIntensity(v: Float) { if (available) nativeSetIntensity(v) }
    fun setRoomPreset(v: Int) { if (available) nativeSetRoomPreset(v) }
    fun setRoomMix(v: Float) { if (available) nativeSetRoomMix(v) }
    fun setReflectionAmount(v: Float) { if (available) nativeSetReflectionAmount(v) }
    fun setReverbTime(v: Float) { if (available) nativeSetReverbTime(v) }
    fun setRoomSize(v: Float) { if (available) nativeSetRoomSize(v) }
    fun setDampening(v: Float) { if (available) nativeSetDampening(v) }
    fun setWidth(v: Float) { if (available) nativeSetWidth(v) }
    fun setProfiling(v: Boolean) { if (available) nativeSetProfiling(v) }
    fun reset() { if (available) nativeReset() }
    fun diagnostics() = if (available) nativeDiagnostics() else doubleArrayOf()
    fun processDirect(buffer: java.nio.ByteBuffer, frames: Int) = available && nativeProcessDirect(buffer, frames)
}
