package app.resonance.player.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Audio hardware / route facts, read from AudioManager. Fields Android does not expose on a
 * given device stay null rather than being guessed.
 */
data class AudioHardware(
    val outputSampleRate: Int? = null,      // AudioManager's preferred output sample rate
    val outputFramesPerBurst: Int? = null,  // the HAL burst size, i.e. the callback buffer
    val route: String = "unknown",
    val routeDetail: String? = null,
    val encoding: String = "\u2014",
    val channels: Int = 2,
) {
    val framesPerBurstMs: Double?
        get() {
            val sr = outputSampleRate ?: return null
            val b = outputFramesPerBurst ?: return null
            return if (sr > 0) b * 1000.0 / sr else null
        }
}

object AudioHardwareReader {

    fun read(context: Context, encoding: String, channels: Int): AudioHardware {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioHardware(encoding = encoding, channels = channels)

        val sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()

        var route = "unknown"
        var detail: String? = null
        runCatching {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Pick the device the framework would actually use: wired/BT beat the speaker.
            val preferred = devices.minByOrNull { routePriority(it.type) }
            if (preferred != null) {
                route = routeName(preferred.type)
                detail = buildString {
                    val name = preferred.productName?.toString().orEmpty()
                    if (name.isNotBlank()) append(name)
                    val rates = preferred.sampleRates
                    if (rates != null && rates.isNotEmpty()) {
                        if (isNotEmpty()) append(" \u00B7 ")
                        append(rates.joinToString("/") { "$it" }.take(40))
                        append(" Hz")
                    }
                }.ifBlank { null }
            }
        }

        return AudioHardware(
            outputSampleRate = sr,
            outputFramesPerBurst = burst,
            route = route,
            routeDetail = detail,
            encoding = encoding,
            channels = channels,
        )
    }

    private fun routePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        else -> 5
    }

    private fun routeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        else -> if (Build.VERSION.SDK_INT >= 31) "Type $type" else "Other"
    }
}
