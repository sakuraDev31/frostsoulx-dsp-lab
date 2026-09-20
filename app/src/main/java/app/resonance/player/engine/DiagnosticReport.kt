package app.resonance.player.engine

import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the human-readable `.txt` diagnostic report.
 *
 * Every line is either a measured value or an explicit "not available on this device". The
 * host reference figures from the desktop engine benchmark are printed in their own clearly
 * labelled section so they can never be mistaken for phone measurements.
 */
object DiagnosticReport {

    /** Desktop reference from the reference-engine host benchmark. Not a device measurement. */
    private const val HOST_REF =
        "384-frame block, avg 304 us, worst 580 us, 3.8% realtime, 20000 blocks, 0 allocations"

    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        return "frostsoulx_dsp_report_$stamp.txt"
    }

    fun build(
        t: AudioTelemetry,
        device: DeviceMetrics,
        hardware: AudioHardware,
        engine: InstalledEngine?,
        values: Map<String, Float>,
        enabled: Boolean,
        chain: List<DspStage>,
        diagnostics: List<Diagnostic>,
        pssMb: Double?,
    ): String = buildString {
        val na = "not available"
        fun line(label: String, value: Any?) {
            append(label.padEnd(26)).append(": ").append(value?.toString() ?: na).append('\n')
        }

        appendLine("FROSTSOULX DSP DIAGNOSTIC REPORT")
        appendLine("================================")
        appendLine()
        appendLine("Generated: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
        appendLine()

        appendLine("DEVICE")
        appendLine("------")
        line("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("SoC", device.soc)
        line("ABI", device.abi)
        line("CPU cores", device.coreCount)
        line("Cores online", device.onlineCores)
        line("GPU utilisation", "N/A (no public Android API)")
        line("GPU renderer", device.gpuRenderer)
        appendLine()

        appendLine("AUDIO HARDWARE")
        appendLine("--------------")
        line("Stream sample rate", if (t.sampleRate > 0) "${t.sampleRate} Hz" else null)
        line("HAL output rate", hardware.outputSampleRate?.let { "$it Hz" })
        line("HAL frames per burst", hardware.outputFramesPerBurst)
        line("Host callback block", if (t.hostBlockFrames > 0) "${t.hostBlockFrames} frames" else null)
        line("Channels", hardware.channels)
        line("PCM format", hardware.encoding)
        line("Output route", hardware.route)
        line("Route detail", hardware.routeDetail)
        appendLine()

        appendLine("ENGINE")
        appendLine("------")
        line("Backend", if (NativeEngine.available) "libresonance_bridge (dlopen plugin ABI v1)" else "unavailable")
        line("Engine", engine?.let { "${it.manifest.name} ${it.manifest.version}".trim() } ?: "none loaded")
        line("Engine id", engine?.manifest?.id)
        line("DSP enabled", if (engine == null) "no engine" else if (enabled) "yes" else "no (bypassed)")
        line("Processing quantum", if (t.quantum > 0) "${t.quantum} frames" else "AUTO (follows host block)")
        line("Quantum FIFO latency", if (t.latencyFrames > 0)
            "${t.latencyFrames} frames (%.2f ms)".format(t.latencyMs) else "0 frames (0.00 ms)")
        line("Block wall budget", if (t.blockBudgetUs > 0) "%.0f us".format(t.blockBudgetUs) else null)
        appendLine()

        appendLine("DSP CHAIN")
        appendLine("---------")
        if (chain.isEmpty()) {
            appendLine("  (none)")
        } else {
            chain.forEachIndexed { i, s ->
                val state = when (s.state) {
                    StageState.HOST -> "host"
                    StageState.ACTIVE -> "ACTIVE"
                    StageState.BYPASSED -> "bypassed"
                }
                appendLine("  ${if (i == 0) " " else "\u2193"} ${s.name.padEnd(14)} [$state]  ${s.detail}")
            }
        }
        appendLine()

        appendLine("PERFORMANCE (measured on this device)")
        appendLine("-------------------------------------")
        line("Blocks processed", t.blockCount)
        line("Host callbacks", t.callbackCount)
        line("Frames processed", t.framesProcessed)
        line("Average DSP time", if (t.blockCount > 0) formatNs(t.procAvgNs) else null)
        line("Minimum DSP time", if (t.blockCount > 0) formatNs(t.procMinNs) else null)
        line("Worst DSP time", if (t.blockCount > 0) formatNs(t.procMaxNs) else null)
        line("DSP time std dev", if (t.blockCount > 0) formatNs(t.procStdDevNs) else null)
        line("Realtime ratio (avg)", if (t.blockCount > 0) "%.3f%%".format(t.rtRatioAvg * 100) else null)
        line("Realtime ratio (last)", if (t.blockCount > 0) "%.3f%%".format(t.rtRatioLast * 100) else null)
        line("Deadline misses", t.deadlineMisses)
        line("Sink underruns", t.underruns)
        line("Allocations in callback", "not measurable from managed code; " +
            "the native process path performs no heap allocation by construction")
        appendLine()

        appendLine("HOST REFERENCE (desktop, NOT this device)")
        appendLine("-----------------------------------------")
        appendLine("  $HOST_REF")
        appendLine("  Printed for comparison only. All other numbers in this report are")
        appendLine("  measured on the Android device above.")
        appendLine()

        appendLine("PROCESS RESOURCES")
        appendLine("-----------------")
        line("Process CPU", device.processCpuPercent?.let {
            "%.1f%% of one core (whole app process, /proc/self/stat)".format(it)
        })
        line("CPU sample window", if (device.cpuSampleWindowMs > 0) "${device.cpuSampleWindowMs} ms" else null)
        line("DSP-thread CPU", "N/A (Android does not expose reliable per-thread CPU to apps)")
        line("System load (1 min)", device.systemLoad1?.let { "%.2f".format(it) })
        line("Java heap used", "%.1f MB of %.1f MB".format(device.javaHeapUsedMb, device.javaHeapMaxMb))
        line("Native heap", "%.1f MB".format(device.nativeHeapMb))
        line("App total PSS", pssMb?.let { "%.1f MB".format(it) })
        line("Battery temperature", device.batteryTemperatureC?.let { "%.1f C".format(it) })
        line("Thermal status", device.thermalStatus)
        line("Thermal throttling", if (device.thermalStatus == null) null else device.thermalThrottling)
        appendLine()

        appendLine("INPUT (pre-engine, measured)")
        appendLine("----------------------------")
        line("RMS L", "%.5f (%s)".format(t.inRmsL, formatDb(t.inRmsDbL)))
        line("RMS R", "%.5f (%s)".format(t.inRmsR, formatDb(t.inRmsDbR)))
        line("Sample peak L", "%.5f (%s)".format(t.inPeakL, formatDb(t.inPeakDbL)))
        line("Sample peak R", "%.5f (%s)".format(t.inPeakR, formatDb(t.inPeakDbR)))
        appendLine()

        appendLine("OUTPUT (post-engine, measured)")
        appendLine("------------------------------")
        line("RMS L", "%.5f (%s)".format(t.outRmsL, formatDb(t.outRmsDbL)))
        line("RMS R", "%.5f (%s)".format(t.outRmsR, formatDb(t.outRmsDbR)))
        line("Sample peak L", "%.5f (%s)".format(t.outPeakL, formatDb(t.outPeakDbL)))
        line("Sample peak R", "%.5f (%s)".format(t.outPeakR, formatDb(t.outPeakDbR)))
        line("Peak hold L", "%.5f".format(t.holdL))
        line("Peak hold R", "%.5f".format(t.holdR))
        line("True peak L", if (t.truePeakLDb.isMeasured())
            "%.2f dBTP (4x oversampled)".format(t.truePeakLDb) else null)
        line("True peak R", if (t.truePeakRDb.isMeasured())
            "%.2f dBTP (4x oversampled)".format(t.truePeakRDb) else null)
        line("True peak hold", if (t.truePeakHoldDb.isMeasured()) "%.2f dBTP".format(t.truePeakHoldDb) else null)
        line("Clipped samples", t.clipCount)
        line("Headroom to 0 dBFS", if (t.headroomDb < 199) "%.2f dB".format(t.headroomDb) else null)
        line("NaN samples", t.nanCount)
        line("Infinite samples", t.infCount)
        appendLine()

        appendLine("LOUDNESS (BS.1770-4 K-weighted)")
        appendLine("-------------------------------")
        line("Momentary (400 ms)", if (t.lufsMomentary.isMeasured()) formatLufs(t.lufsMomentary) else null)
        line("Short term (3 s)", if (t.lufsShortTerm.isMeasured()) formatLufs(t.lufsShortTerm) else null)
        line("Integrated (gated)", if (t.lufsIntegrated.isMeasured()) formatLufs(t.lufsIntegrated) else null)
        line("Loudness range", if (t.lra.isMeasured()) "%.1f LU".format(t.lra) else
            "needs >= 60 s of short-term history")
        line("Loudness blocks", t.loudnessBlocks)
        appendLine("  Note: measured on the player's output stream, not on a decoded file, so")
        appendLine("  the integrated value covers only what has been played since the last reset.")
        appendLine()

        appendLine("STEREO / SPATIAL (measured from PCM)")
        appendLine("------------------------------------")
        line("L/R balance", "%.3f (%s)".format(t.balance, when {
            t.balance > 0.02 -> "right of centre"
            t.balance < -0.02 -> "left of centre"
            else -> "centred"
        }))
        line("Stereo width", "%.3f (0 = mono, 1 = normal, 2 = very wide)".format(t.width))
        line("Mid RMS", "%.5f".format(t.midRms))
        line("Side RMS", "%.5f".format(t.sideRms))
        line("L/R correlation", "%.3f".format(t.correlation))
        line("Mono compatibility", "%.1f%% of energy retained when summed".format((1 - t.monoRisk) * 100))
        appendLine()

        appendLine("SPECTRUM")
        appendLine("--------")
        line("Peak frequency", if (t.peakFreqHz > 0) "%.1f Hz".format(t.peakFreqHz) else null)
        line("Peak magnitude", if (t.peakMagDb.isMeasured()) "%.2f dBFS".format(t.peakMagDb) else null)
        line("Spectral RMS", if (t.spectralRmsDb.isMeasured()) "%.2f dBFS".format(t.spectralRmsDb) else null)
        line("Spectral centroid", if (t.spectralCentroidHz > 0) "%.1f Hz".format(t.spectralCentroidHz) else null)
        appendLine()

        appendLine("BENCHMARK")
        appendLine("---------")
        if (!t.bench.hasData) {
            appendLine("  No benchmark has been run in this session.")
        } else {
            val b = t.bench
            line("Blocks measured", b.blocks)
            line("Average", formatNs(b.avgNs))
            line("Minimum", formatNs(b.minNs))
            line("Worst", formatNs(b.maxNs))
            line("Median (p50)", formatNs(b.p50Ns))
            line("p95", formatNs(b.p95Ns))
            line("p99", formatNs(b.p99Ns))
            line("Std deviation", formatNs(b.stdDevNs))
            line("Realtime ratio", "%.3f%%".format(b.rtRatio * 100))
            line("Deadline misses", b.deadlineMisses)
            appendLine("  Percentiles come from a 4 us-resolution histogram accumulated on the")
            appendLine("  audio thread, so they are exact to within one bin.")
        }
        appendLine()

        appendLine("ENGINE PARAMETERS")
        appendLine("-----------------")
        if (engine == null || engine.manifest.params.isEmpty()) {
            appendLine("  (none)")
        } else {
            engine.manifest.params.forEach { p ->
                val v = values[p.id] ?: p.default
                val shown = when (p.type) {
                    ParamType.TOGGLE -> if (v >= 0.5f) "on" else "off"
                    ParamType.CHOICE -> p.options.getOrNull(v.toInt()) ?: v.toString()
                    ParamType.SLIDER -> "%.3f %s".format(v, p.unit).trim()
                }
                appendLine("  [${p.group}] ${p.label} (${p.id}) = $shown")
            }
        }
        appendLine()

        appendLine("DIAGNOSTICS")
        appendLine("-----------")
        if (diagnostics.isEmpty()) {
            appendLine("  (none)")
        } else {
            diagnostics.forEach { d ->
                appendLine("  [${d.severity}] ${d.title}")
                appendLine("      ${d.detail}")
            }
        }
        appendLine()
        appendLine("End of report.")
    }
}
