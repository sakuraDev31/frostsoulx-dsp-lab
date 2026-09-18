package dev.vxs.frostsoulxdsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Bounded PCM16 -> float JNI -> PCM16 adapter. All lifecycle calls run on Media3's thread. */
class LabAudioProcessor : AudioProcessor {
    private var pendingFormat = AudioProcessor.AudioFormat.NOT_SET
    private var format = AudioProcessor.AudioFormat.NOT_SET
    private var storage = EMPTY
    private var nativeBuffer = EMPTY
    private var output = EMPTY
    private var ended = false
    @Volatile var enabled = false
    @Volatile var configured = false; private set
    @Volatile var formatDescription = "Waiting for decoded PCM"; private set

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount == 2
        pendingFormat = if (supported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
        formatDescription = if (supported) "${inputAudioFormat.sampleRate} Hz · stereo PCM16 → native float" else
            "Unsupported DSP format: ${inputAudioFormat.channelCount} ch / PCM ${inputAudioFormat.encoding} · passthrough"
        return pendingFormat
    }

    override fun isActive() = pendingFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining() || output.hasRemaining()) return
        val count = minOf(inputBuffer.remaining(), MAX_FRAMES * 4)
        check(count % 4 == 0) { "Unaligned stereo PCM buffer" }
        if (storage.capacity() < count) storage = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder())
        if (nativeBuffer.capacity() < count * 2) nativeBuffer = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder())
        storage.clear().limit(count)
        nativeBuffer.clear().limit(count * 2)
        inputBuffer.order(ByteOrder.nativeOrder())
        repeat(count / 2) {
            val sample = inputBuffer.short
            storage.putShort(sample)
            nativeBuffer.putFloat(sample / 32768f)
        }
        storage.flip()
        nativeBuffer.flip()
        if (NativeEngine.available) {
            // Even dry/bypass mode is metered. Native controls are applied at block boundaries.
            NativeEngine.setEnabled(enabled)
            val processed = NativeEngine.processDirect(nativeBuffer, count / 4)
            if (processed) {
                storage.clear().limit(count)
                repeat(count / 2) {
                    val sample = nativeBuffer.float
                    storage.putShort((sample.coerceIn(-1f, 32767f / 32768f) * 32768f).roundToInt().toShort())
                }
                storage.flip()
            }
        }
        output = storage
    }

    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer = output.also { output = EMPTY }
    override fun isEnded() = ended && !output.hasRemaining()
    override fun flush() {
        output = EMPTY
        ended = false
        format = pendingFormat
        configured = format != AudioProcessor.AudioFormat.NOT_SET
        if (configured && NativeEngine.available) NativeEngine.prepare(format.sampleRate, MAX_FRAMES)
    }
    override fun reset() {
        pendingFormat = AudioProcessor.AudioFormat.NOT_SET
        flush()
        storage = EMPTY
        nativeBuffer = EMPTY
        formatDescription = "Waiting for decoded PCM"
    }
    companion object {
        private const val MAX_FRAMES = 16384
        private val EMPTY = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
