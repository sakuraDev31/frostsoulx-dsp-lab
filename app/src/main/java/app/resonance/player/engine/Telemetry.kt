package app.resonance.player.engine

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Slot indices into the native telemetry snapshot. Must stay in the same order as
 * `telemetry::Slot` in app/src/main/cpp/telemetry.h.
 */
object Slot {
    const val SAMPLE_RATE = 0
    const val FRAMES_PROCESSED = 1
    const val CALLBACK_COUNT = 2
    const val BLOCK_COUNT = 3
    const val PROC_LAST_NS = 4
    const val PROC_AVG_NS = 5
    const val PROC_MIN_NS = 6
    const val PROC_MAX_NS = 7
    const val PROC_STDDEV_NS = 8
    const val RT_RATIO_AVG = 9
    const val RT_RATIO_LAST = 10
    const val DEADLINE_MISSES = 11
    const val QUANTUM = 12
    const val HOST_BLOCK_FRAMES = 13
    const val LATENCY_FRAMES = 14
    const val IN_RMS_L = 15
    const val IN_RMS_R = 16
    const val IN_PEAK_L = 17
    const val IN_PEAK_R = 18
    const val OUT_RMS_L = 19
    const val OUT_RMS_R = 20
    const val OUT_PEAK_L = 21
    const val OUT_PEAK_R = 22
    const val OUT_HOLD_L = 23
    const val OUT_HOLD_R = 24
    const val TRUE_PEAK_L_DB = 25
    const val TRUE_PEAK_R_DB = 26
    const val CLIP_COUNT = 27
    const val HEADROOM_DB = 28
    const val CORRELATION = 29
    const val BALANCE = 30
    const val WIDTH = 31
    const val MID_RMS = 32
    const val SIDE_RMS = 33
    const val MONO_RISK = 34
    const val LUFS_MOMENTARY = 35
    const val LUFS_SHORT_TERM = 36
    const val LUFS_INTEGRATED = 37
    const val LRA = 38
    const val NAN_COUNT = 39
    const val INF_COUNT = 40
    const val ENGINE_ACTIVE = 41
    const val BYPASS = 42
    const val AGE_NS = 43
    const val PEAK_FREQ_HZ = 44
    const val PEAK_MAG_DB = 45
    const val SPECTRAL_RMS_DB = 46
    const val SPECTRAL_CENTROID_HZ = 47
    const val BENCH_ACTIVE = 48
    const val BENCH_BLOCKS = 49
    const val BENCH_AVG_NS = 50
    const val BENCH_MIN_NS = 51
    const val BENCH_MAX_NS = 52
    const val BENCH_P50_NS = 53
    const val BENCH_P95_NS = 54
    const val BENCH_P99_NS = 55
    const val BENCH_STDDEV_NS = 56
    const val BENCH_RT_RATIO = 57
    const val BENCH_DEADLINE_MISSES = 58
    const val FIFO_UNDERFLOWS = 59
    const val LOUDNESS_BLOCKS = 60
    const val TRUE_PEAK_HOLD_DB = 61
    const val RESET_COUNT = 62
    const val COUNT = 63
}

/** dB value the native side uses for "nothing measured yet". */
const val SILENCE_DB = -200.0

fun Double.isMeasured(): Boolean = this > SILENCE_DB + 1.0

fun amplitudeToDb(a: Double): Double = if (a > 1e-10) 20.0 * log10(a) else SILENCE_DB

/**
 * One immutable telemetry frame. Built on the analyzer thread from the native snapshot and
 * handed to Compose as a whole, so the UI never reads half-updated values.
 *
 * Everything in here is measured on the device. Nothing is estimated or hard-coded.
 */
data class AudioTelemetry(
    val sampleRate: Int = 48000,
    val framesProcessed: Long = 0,
    val callbackCount: Long = 0,
    val blockCount: Long = 0,
    val procLastNs: Double = 0.0,
    val procAvgNs: Double = 0.0,
    val procMinNs: Double = 0.0,
    val procMaxNs: Double = 0.0,
    val procStdDevNs: Double = 0.0,
    val rtRatioAvg: Double = 0.0,
    val rtRatioLast: Double = 0.0,
    val deadlineMisses: Long = 0,
    val quantum: Int = 0,
    val hostBlockFrames: Int = 0,
    val latencyFrames: Int = 0,
    val inRmsL: Double = 0.0,
    val inRmsR: Double = 0.0,
    val inPeakL: Double = 0.0,
    val inPeakR: Double = 0.0,
    val outRmsL: Double = 0.0,
    val outRmsR: Double = 0.0,
    val outPeakL: Double = 0.0,
    val outPeakR: Double = 0.0,
    val holdL: Double = 0.0,
    val holdR: Double = 0.0,
    val truePeakLDb: Double = SILENCE_DB,
    val truePeakRDb: Double = SILENCE_DB,
    val truePeakHoldDb: Double = SILENCE_DB,
    val clipCount: Long = 0,
    val headroomDb: Double = 0.0,
    val correlation: Double = 0.0,
    val balance: Double = 0.0,
    val width: Double = 0.0,
    val midRms: Double = 0.0,
    val sideRms: Double = 0.0,
    val monoRisk: Double = 0.0,
    val lufsMomentary: Double = SILENCE_DB,
    val lufsShortTerm: Double = SILENCE_DB,
    val lufsIntegrated: Double = SILENCE_DB,
    val lra: Double = SILENCE_DB,
    val loudnessBlocks: Long = 0,
    val nanCount: Long = 0,
    val infCount: Long = 0,
    val engineActive: Boolean = false,
    val bypass: Boolean = false,
    val ageNs: Double = Double.MAX_VALUE,
    val peakFreqHz: Double = 0.0,
    val peakMagDb: Double = SILENCE_DB,
    val spectralRmsDb: Double = SILENCE_DB,
    val spectralCentroidHz: Double = 0.0,
    val underruns: Long = 0,
    val benchActive: Boolean = false,
    val bench: BenchmarkResult = BenchmarkResult(),
    val available: Boolean = false,
) {
    /** Audio has flowed within the last 300 ms. Meters read as silent otherwise. */
    val live: Boolean get() = ageNs < 300_000_000.0

    /** Wall-clock duration of one engine block. Quantum 0 means "follows the host block". */
    val blockFrames: Int get() = if (quantum > 0) quantum else hostBlockFrames
    val blockBudgetUs: Double
        get() = if (sampleRate > 0 && blockFrames > 0) blockFrames * 1_000_000.0 / sampleRate else 0.0

    val latencyMs: Double get() = if (sampleRate > 0) latencyFrames * 1000.0 / sampleRate else 0.0

    val outPeakDbL: Double get() = amplitudeToDb(outPeakL)
    val outPeakDbR: Double get() = amplitudeToDb(outPeakR)
    val outRmsDbL: Double get() = amplitudeToDb(outRmsL)
    val outRmsDbR: Double get() = amplitudeToDb(outRmsR)
    val inPeakDbL: Double get() = amplitudeToDb(inPeakL)
    val inPeakDbR: Double get() = amplitudeToDb(inPeakR)
    val inRmsDbL: Double get() = amplitudeToDb(inRmsL)
    val inRmsDbR: Double get() = amplitudeToDb(inRmsR)
    val stereoRmsDb: Double get() = amplitudeToDb(max(outRmsL, outRmsR))

    /** True when the FIFO/engine is delivering audio the engine actually processed. */
    val dspRunning: Boolean get() = engineActive && !bypass && live

    companion object {
        fun from(s: DoubleArray, available: Boolean): AudioTelemetry = AudioTelemetry(
            sampleRate = s[Slot.SAMPLE_RATE].toInt(),
            framesProcessed = s[Slot.FRAMES_PROCESSED].toLong(),
            callbackCount = s[Slot.CALLBACK_COUNT].toLong(),
            blockCount = s[Slot.BLOCK_COUNT].toLong(),
            procLastNs = s[Slot.PROC_LAST_NS],
            procAvgNs = s[Slot.PROC_AVG_NS],
            procMinNs = s[Slot.PROC_MIN_NS],
            procMaxNs = s[Slot.PROC_MAX_NS],
            procStdDevNs = s[Slot.PROC_STDDEV_NS],
            rtRatioAvg = s[Slot.RT_RATIO_AVG],
            rtRatioLast = s[Slot.RT_RATIO_LAST],
            deadlineMisses = s[Slot.DEADLINE_MISSES].toLong(),
            quantum = s[Slot.QUANTUM].toInt(),
            hostBlockFrames = s[Slot.HOST_BLOCK_FRAMES].toInt(),
            latencyFrames = s[Slot.LATENCY_FRAMES].toInt(),
            inRmsL = s[Slot.IN_RMS_L], inRmsR = s[Slot.IN_RMS_R],
            inPeakL = s[Slot.IN_PEAK_L], inPeakR = s[Slot.IN_PEAK_R],
            outRmsL = s[Slot.OUT_RMS_L], outRmsR = s[Slot.OUT_RMS_R],
            outPeakL = s[Slot.OUT_PEAK_L], outPeakR = s[Slot.OUT_PEAK_R],
            holdL = s[Slot.OUT_HOLD_L], holdR = s[Slot.OUT_HOLD_R],
            truePeakLDb = s[Slot.TRUE_PEAK_L_DB],
            truePeakRDb = s[Slot.TRUE_PEAK_R_DB],
            truePeakHoldDb = s[Slot.TRUE_PEAK_HOLD_DB],
            clipCount = s[Slot.CLIP_COUNT].toLong(),
            headroomDb = s[Slot.HEADROOM_DB],
            correlation = s[Slot.CORRELATION],
            balance = s[Slot.BALANCE],
            width = s[Slot.WIDTH],
            midRms = s[Slot.MID_RMS], sideRms = s[Slot.SIDE_RMS],
            monoRisk = s[Slot.MONO_RISK],
            lufsMomentary = s[Slot.LUFS_MOMENTARY],
            lufsShortTerm = s[Slot.LUFS_SHORT_TERM],
            lufsIntegrated = s[Slot.LUFS_INTEGRATED],
            lra = s[Slot.LRA],
            loudnessBlocks = s[Slot.LOUDNESS_BLOCKS].toLong(),
            nanCount = s[Slot.NAN_COUNT].toLong(),
            infCount = s[Slot.INF_COUNT].toLong(),
            engineActive = s[Slot.ENGINE_ACTIVE] > 0.5,
            bypass = s[Slot.BYPASS] > 0.5,
            ageNs = s[Slot.AGE_NS],
            peakFreqHz = s[Slot.PEAK_FREQ_HZ],
            peakMagDb = s[Slot.PEAK_MAG_DB],
            spectralRmsDb = s[Slot.SPECTRAL_RMS_DB],
            spectralCentroidHz = s[Slot.SPECTRAL_CENTROID_HZ],
            underruns = s[Slot.FIFO_UNDERFLOWS].toLong(),
            benchActive = s[Slot.BENCH_ACTIVE] > 0.5,
            bench = BenchmarkResult(
                blocks = s[Slot.BENCH_BLOCKS].toLong(),
                avgNs = s[Slot.BENCH_AVG_NS],
                minNs = s[Slot.BENCH_MIN_NS],
                maxNs = s[Slot.BENCH_MAX_NS],
                p50Ns = s[Slot.BENCH_P50_NS],
                p95Ns = s[Slot.BENCH_P95_NS],
                p99Ns = s[Slot.BENCH_P99_NS],
                stdDevNs = s[Slot.BENCH_STDDEV_NS],
                rtRatio = s[Slot.BENCH_RT_RATIO],
                deadlineMisses = s[Slot.BENCH_DEADLINE_MISSES].toLong(),
            ),
            available = available,
        )
    }
}

data class BenchmarkResult(
    val blocks: Long = 0,
    val avgNs: Double = 0.0,
    val minNs: Double = 0.0,
    val maxNs: Double = 0.0,
    val p50Ns: Double = 0.0,
    val p95Ns: Double = 0.0,
    val p99Ns: Double = 0.0,
    val stdDevNs: Double = 0.0,
    val rtRatio: Double = 0.0,
    val deadlineMisses: Long = 0,
) {
    val hasData: Boolean get() = blocks > 0
}

/** Diagnostic severity, in escalation order. */
enum class Severity { INFO, WARNING, ERROR }

data class Diagnostic(val severity: Severity, val title: String, val detail: String)

/**
 * Derives diagnostics from measured values only. Every entry below is backed by a counter or
 * a measurement; nothing fires speculatively.
 */
fun buildDiagnostics(t: AudioTelemetry, device: DeviceMetrics, engineLoaded: Boolean): List<Diagnostic> {
    val out = ArrayList<Diagnostic>(8)

    if (!t.available) {
        out += Diagnostic(Severity.ERROR, "Native bridge unavailable",
            "libresonance_bridge.so did not load. No telemetry or DSP is possible.")
        return out
    }
    if (!engineLoaded) {
        out += Diagnostic(Severity.INFO, "No engine loaded",
            "Audio passes through untouched. Import an engine zip to enable DSP.")
    } else if (t.bypass) {
        out += Diagnostic(Severity.INFO, "Engine bypassed",
            "The engine is loaded but switched out of the signal path.")
    }
    if (!t.live) {
        out += Diagnostic(Severity.INFO, "No audio flowing",
            "Meters and timing freeze until playback starts.")
    }

    if (t.nanCount > 0) {
        out += Diagnostic(Severity.ERROR, "NaN samples from engine",
            "${t.nanCount} non-finite sample(s) were replaced with silence.")
    }
    if (t.infCount > 0) {
        out += Diagnostic(Severity.ERROR, "Infinite samples from engine",
            "${t.infCount} sample(s) were infinite and replaced with silence.")
    }
    if (t.clipCount > 0) {
        out += Diagnostic(
            if (t.clipCount > 1000) Severity.ERROR else Severity.WARNING,
            "Output clipped",
            "${t.clipCount} sample(s) hit full scale and were clamped. Lower the output gain.",
        )
    }
    if (t.truePeakHoldDb.isMeasured() && t.truePeakHoldDb > -0.1) {
        out += Diagnostic(Severity.WARNING, "True peak above -0.1 dBTP",
            "Inter-sample peaks reached ${"%.2f".format(t.truePeakHoldDb)} dBTP; " +
                "lossy re-encoding of this output may distort.")
    }
    if (t.deadlineMisses > 0 && t.blockCount > 0) {
        val pct = 100.0 * t.deadlineMisses / t.blockCount
        out += Diagnostic(
            if (pct > 1.0) Severity.ERROR else Severity.WARNING,
            "Processing deadline missed",
            "${t.deadlineMisses} of ${t.blockCount} blocks (${"%.2f".format(pct)}%) took longer " +
                "than their ${"%.2f".format(t.blockBudgetUs / 1000.0)} ms budget.",
        )
    }
    if (t.rtRatioAvg > 0.7) {
        out += Diagnostic(Severity.ERROR, "Realtime budget nearly exhausted",
            "Average load is ${"%.1f".format(t.rtRatioAvg * 100)}% of realtime. Dropouts are likely.")
    } else if (t.rtRatioAvg > 0.4) {
        out += Diagnostic(Severity.WARNING, "High realtime load",
            "Average load is ${"%.1f".format(t.rtRatioAvg * 100)}% of realtime.")
    }
    if (t.blockCount > 100 && t.procAvgNs > 0 && t.procMaxNs > t.procAvgNs * 20) {
        out += Diagnostic(Severity.WARNING, "Abnormal processing time spike",
            "Worst block (${formatNs(t.procMaxNs)}) was ${"%.0f".format(t.procMaxNs / t.procAvgNs)}x " +
                "the average (${formatNs(t.procAvgNs)}). Usually a scheduler preemption.")
    }
    if (t.underruns > 0) {
        out += Diagnostic(Severity.WARNING, "Audio sink underrun",
            "${t.underruns} dropout(s) reported by the audio sink.")
    }
    if (t.live && abs(t.balance) > 0.35) {
        out += Diagnostic(Severity.INFO, "Channel imbalance",
            "Output is skewed ${if (t.balance > 0) "right" else "left"} " +
                "(${"%.0f".format(abs(t.balance) * 100)}%).")
    }
    if (t.live && t.correlation < -0.3) {
        out += Diagnostic(Severity.WARNING, "Negative phase correlation",
            "Correlation is ${"%.2f".format(t.correlation)}; " +
                "${"%.0f".format(t.monoRisk * 100)}% of the energy is lost in mono.")
    }
    device.thermalStatus?.let { status ->
        if (device.thermalThrottling) {
            out += Diagnostic(Severity.WARNING, "Thermal throttling",
                "Android reports thermal status '$status'. Processing times will rise.")
        }
    }

    if (out.none { it.severity != Severity.INFO } && t.dspRunning) {
        out += Diagnostic(Severity.INFO, "Nominal",
            "No clipping, no non-finite samples, no missed deadlines.")
    }
    return out
}

fun formatNs(ns: Double): String = when {
    ns <= 0 -> "-"
    ns < 1_000 -> "${ns.toInt()} ns"
    ns < 1_000_000 -> "%.0f \u00B5s".format(ns / 1000.0)
    else -> "%.2f ms".format(ns / 1_000_000.0)
}

fun formatDb(db: Double, decimals: Int = 1): String =
    if (!db.isMeasured()) "\u2013\u221E dB" else "%.${decimals}f dB".format(db)

fun formatLufs(v: Double): String = if (!v.isMeasured()) "\u2014" else "%.1f LUFS".format(v)
