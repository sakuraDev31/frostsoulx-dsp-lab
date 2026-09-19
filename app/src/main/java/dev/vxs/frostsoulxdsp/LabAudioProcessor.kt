package dev.vxs.frostsoulxdsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class LabAudioProcessor : AudioProcessor {
    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var output = EMPTY
    private var ended = false
    var enabled = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT && inputAudioFormat.channelCount == 2
        if (!supported) return AudioProcessor.AudioFormat.NOT_SET
        format = inputAudioFormat
        NativeEngine.prepare(inputAudioFormat.sampleRate, 384)
        NativeEngine.applyState()
        return inputAudioFormat
    }
    override fun isActive() = format != AudioProcessor.AudioFormat.NOT_SET
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val count = inputBuffer.remaining()
        if (output.capacity() < count) output = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder()) else output.clear()
        output.limit(count)
        output.put(inputBuffer)
        output.flip()
        if (enabled && output.isDirect) {
            val frames = count / 8
            var offsetFrames = 0
            while (offsetFrames < frames) {
                val chunkFrames = min(384, frames - offsetFrames)
                val chunk = output.duplicate().order(ByteOrder.nativeOrder()).apply {
                    position(offsetFrames * 8)
                    limit((offsetFrames + chunkFrames) * 8)
                }.slice().order(ByteOrder.nativeOrder())
                NativeEngine.processDirect(chunk, chunkFrames)
                offsetFrames += chunkFrames
            }
        }
        output.position(0)
    }
    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer = output
    override fun isEnded() = ended && !output.hasRemaining()
    override fun flush() { output = EMPTY; ended = false }
    override fun reset() { flush(); format = AudioProcessor.AudioFormat.NOT_SET }
    companion object { private val EMPTY = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
}
