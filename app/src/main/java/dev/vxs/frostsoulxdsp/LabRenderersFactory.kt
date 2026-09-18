package dev.vxs.frostsoulxdsp

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class LabRenderersFactory(context: Context, val processor: LabAudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            // Media3 skips custom processors on its float-output branch.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            .setAudioProcessors(arrayOf(processor))
            .build()
}
