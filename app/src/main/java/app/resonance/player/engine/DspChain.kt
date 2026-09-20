package app.resonance.player.engine

import kotlin.math.roundToInt

/**
 * A stage in the signal path shown by the DSP Lab.
 *
 * Stages are never invented: they are either fixed points of the host path (input, PCM
 * conversion, quantum FIFO, sanitiser, output) or derived from parameters the *currently
 * loaded engine* actually declares in its manifest. An engine with no reverb parameters
 * simply has no Room stage.
 */
data class DspStage(
    val name: String,
    val detail: String,
    val state: StageState,
    val paramIds: List<String> = emptyList(),
)

enum class StageState {
    /** Fixed part of the host path, always running. */
    HOST,

    /** Engine module present and switched on. */
    ACTIVE,

    /** Engine module present but switched off / at a neutral setting. */
    BYPASSED,
}

/**
 * Recognises the well-known module families by parameter id / label / group. Anything that
 * does not match a family is grouped into a single generic "DSP" stage, so an unknown engine
 * still shows a truthful chain instead of a fake one.
 */
object DspChainBuilder {

    private data class Family(
        val stage: String,
        val keywords: List<String>,
        val enableHints: List<String> = emptyList(),
    )

    private val families = listOf(
        Family("Tone", listOf("bass", "treble", "shelf", "eq", "tilt", "tone", "mid_gain")),
        Family("Convolution", listOf("convolution", "convolve", "ir_", "impulse", "hrtf", "hrir"),
            listOf("convolution", "convolve", "ir_enable", "hrtf")),
        Family("Spatial", listOf("spatial", "hoa", "ambisonic", "vbap", "binaural", "crossfeed",
            "azimuth", "elevation", "position", "distance", "panner")),
        Family("Width", listOf("width", "stereo", "mid", "side", "mono")),
        Family("Room", listOf("room", "reverb", "damp", "reflection", "decay", "wet", "early", "late")),
        Family("Limiter", listOf("limit", "ceiling", "headroom", "clip", "compress", "makeup")),
        Family("Output", listOf("gain", "volume", "output", "trim", "level")),
    )

    /** Order the stages are drawn in, whatever order the manifest declares them. */
    private val order = listOf("Tone", "Convolution", "Spatial", "Width", "Room", "Limiter", "Output")

    fun build(
        engine: InstalledEngine?,
        values: Map<String, Float>,
        enabled: Boolean,
        telemetry: AudioTelemetry,
    ): List<DspStage> {
        val stages = ArrayList<DspStage>(10)

        stages += DspStage(
            "Input",
            if (telemetry.live) "${telemetry.sampleRate} Hz \u00B7 stereo" else "idle",
            StageState.HOST,
        )
        stages += DspStage(
            "PCM",
            "float32 interleaved \u00B7 ${telemetry.hostBlockFrames} frame host block",
            StageState.HOST,
        )

        if (engine == null) {
            stages += DspStage("Engine", "none loaded", StageState.BYPASSED)
        } else {
            if (telemetry.quantum > 0) {
                stages += DspStage(
                    "Quantum FIFO",
                    "${telemetry.quantum} frames \u00B7 +${"%.1f".format(telemetry.latencyMs)} ms",
                    StageState.ACTIVE,
                )
            } else {
                stages += DspStage("Quantum", "AUTO \u00B7 follows host block \u00B7 no added latency", StageState.HOST)
            }

            val claimed = HashSet<String>()
            for (familyName in order) {
                val family = families.first { it.stage == familyName }
                val params = engine.manifest.params.filter { p ->
                    p.id !in claimed && matches(p, family)
                }
                if (params.isEmpty()) continue
                params.forEach { claimed += it.id }
                val active = enabled && isActive(params, values)
                stages += DspStage(
                    name = familyName,
                    detail = describe(params, values),
                    state = if (active) StageState.ACTIVE else StageState.BYPASSED,
                    paramIds = params.map { it.id },
                )
            }

            val rest = engine.manifest.params.filter { it.id !in claimed }
            if (rest.isNotEmpty()) {
                stages += DspStage(
                    "DSP",
                    "${rest.size} parameter${if (rest.size == 1) "" else "s"}",
                    if (enabled) StageState.ACTIVE else StageState.BYPASSED,
                    rest.map { it.id },
                )
            }
            if (engine.manifest.params.isEmpty()) {
                stages += DspStage(
                    "Engine",
                    engine.manifest.name,
                    if (enabled) StageState.ACTIVE else StageState.BYPASSED,
                )
            }
        }

        stages += DspStage("Sanitiser", "NaN/Inf \u2192 0 \u00B7 clamp \u00B1 1.0", StageState.HOST)
        stages += DspStage(
            "Output",
            if (telemetry.live) "peak ${formatDb(maxOf(telemetry.outPeakDbL, telemetry.outPeakDbR))}" else "idle",
            StageState.HOST,
        )
        return stages
    }

    private fun matches(p: EngineParam, family: Family): Boolean {
        val hay = "${p.id} ${p.label} ${p.group}".lowercase()
        return family.keywords.any { hay.contains(it) }
    }

    /**
     * A module counts as active when at least one of its parameters is doing something:
     * a toggle that is on, a choice off its first option, or a slider away from neutral.
     */
    private fun isActive(params: List<EngineParam>, values: Map<String, Float>): Boolean =
        params.any { p ->
            val v = values[p.id] ?: p.default
            when (p.type) {
                ParamType.TOGGLE -> v >= 0.5f
                ParamType.CHOICE -> v.roundToInt() != 0
                ParamType.SLIDER -> {
                    // Neutral is 0 for bipolar controls, the minimum for unipolar ones.
                    val neutral = if (p.min < 0f && p.max > 0f) 0f else p.min
                    kotlin.math.abs(v - neutral) > (p.max - p.min) * 0.005f
                }
            }
        }

    private fun describe(params: List<EngineParam>, values: Map<String, Float>): String =
        params.take(3).joinToString(" \u00B7 ") { p ->
            val v = values[p.id] ?: p.default
            when (p.type) {
                ParamType.TOGGLE -> "${p.label} ${if (v >= 0.5f) "on" else "off"}"
                ParamType.CHOICE -> "${p.label} ${p.options.getOrNull(v.roundToInt()) ?: "?"}"
                ParamType.SLIDER -> "${p.label} ${"%.1f".format(v)}${if (p.unit.isBlank()) "" else " ${p.unit}"}"
            }
        } + if (params.size > 3) " \u00B7 +${params.size - 3}" else ""
}
