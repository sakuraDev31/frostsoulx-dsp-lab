package dev.vxs.frostsoulxdsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Versioned JNI snapshot; unknown versions never masquerade as valid measurements. */
class EngineTelemetry private constructor(private val values: DoubleArray) {
    private fun at(index: Int) = values.getOrElse(index) { -1.0 }
    val valid get() = values.size == 296 && at(0) == 1.0
    val calls get() = if (valid) at(1).toLong() else 0L
    val sampleRate get() = at(2).toInt()
    val frames get() = at(3).toInt()
    val capacity get() = at(4).toInt()
    val channels get() = at(5).toInt()
    val processingMs get() = at(6)
    val cpuPercent get() = at(7)
    val bufferMs get() = at(8)
    val quantumMs get() = at(9)
    val resultCode get() = at(10).toInt()
    val activeMask get() = at(11).toInt()
    val inputClips get() = at(12).toLong()
    val outputClips get() = at(13).toLong()
    val invalidSamples get() = at(14).toLong()
    val maxDifference get() = at(15)
    val profileSequence get() = at(33).toLong()
    val prepared get() = at(34) == 1.0
    val profileFrames get() = at(36).toInt()
    val profiling get() = at(37) == 1.0
    val engineEnabled get() = at(38) == 1.0
    val ageMs get() = at(39)
    val fresh get() = valid && calls > 0 && ageMs in 0.0..750.0
    val result get() = listOf("Not prepared", "Dry bypass", "Invalid input", "Steam Audio unavailable", "Invalid output · dry fallback", "DSP processed")
        .getOrElse(resultCode) { "Unknown native result" }
    fun peak(output: Boolean, channel: Int) = at((if (output) 20 else 16) + channel)
    fun rms(output: Boolean, channel: Int) = at((if (output) 22 else 18) + channel)
    fun dc(output: Boolean, channel: Int) = at((if (output) 26 else 24) + channel)
    fun stageMs(stage: Int) = at(28 + stage)
    fun stageActive(stage: Int) = valid && (activeMask and (1 shl stage)) != 0
    val waveform: FloatArray get() = if (valid) FloatArray(at(32).toInt().coerceIn(0, 256)) { at(40 + it).toFloat() } else floatArrayOf()

    companion object {
        val EMPTY = EngineTelemetry(doubleArrayOf())
        fun decode(raw: DoubleArray) = EngineTelemetry(raw)
    }
}

/** Small Hann-windowed DFT of real mono output samples; called off the UI/audio threads. */
fun spectrumOf(samples: FloatArray): FloatArray {
    if (samples.size < 32) return floatArrayOf()
    val size = samples.size
    val window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / (size - 1)) }
    val gain = window.sum()
    return FloatArray(size / 2) { bin ->
        var real = 0.0
        var imaginary = 0.0
        for (i in samples.indices) {
            val angle = 2 * PI * (bin + 1) * i / size
            val value = samples[i] * window[i]
            real += value * cos(angle)
            imaginary -= value * sin(angle)
        }
        (20 * log10((2 * sqrt(real * real + imaginary * imaginary) / gain).coerceAtLeast(1e-6))).toFloat()
    }
}
