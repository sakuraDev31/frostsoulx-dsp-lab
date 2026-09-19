package app.resonance.player.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ParamType { SLIDER, TOGGLE, CHOICE }

data class EngineParam(
    val id: String,
    val label: String,
    val group: String,
    val type: ParamType,
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float,
    val unit: String,
    val options: List<String>,
)

data class EnginePreset(val name: String, val values: Map<String, Float>)

data class EngineManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val api: Int,
    val entry: String,
    val preload: List<String>,
    val params: List<EngineParam>,
    val presets: List<EnginePreset>,
) {
    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
        private val FILE_PATTERN = Regex("[A-Za-z0-9_.+-]{1,128}")

        /** Throws IllegalArgumentException / JSONException with a readable message if invalid. */
        fun parse(text: String): EngineManifest {
            val o = JSONObject(text)
            val id = o.getString("id")
            require(ID_PATTERN.matches(id)) { "Engine id may only use letters, digits, '.', '_' and '-'" }
            val api = o.optInt("api", 1)
            require(api == 1) { "Engine needs plugin API $api, this app supports 1" }
            val entry = o.getString("entry")
            require(FILE_PATTERN.matches(entry)) { "Bad entry library name: $entry" }

            val preload = o.optJSONArray("preload")?.let { a ->
                List(a.length()) { a.getString(it) }
            } ?: emptyList()
            preload.forEach { require(FILE_PATTERN.matches(it)) { "Bad preload library name: $it" } }

            val params = parseParams(o.optJSONArray("params") ?: JSONArray())
            require(params.map { it.id }.toSet().size == params.size) { "Duplicate parameter ids in manifest" }

            val presets = parsePresets(o.optJSONArray("presets") ?: JSONArray())

            return EngineManifest(
                id = id,
                name = o.optString("name", id),
                version = o.optString("version", ""),
                description = o.optString("description", ""),
                api = api,
                entry = entry,
                preload = preload,
                params = params,
                presets = presets,
            )
        }

        private fun parseParams(arr: JSONArray): List<EngineParam> = List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            val pid = p.getString("id")
            val type = when (p.optString("type", "slider")) {
                "toggle" -> ParamType.TOGGLE
                "choice" -> ParamType.CHOICE
                else -> ParamType.SLIDER
            }
            val options = p.optJSONArray("options")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val min: Float
            val max: Float
            when (type) {
                ParamType.TOGGLE -> { min = 0f; max = 1f }
                ParamType.CHOICE -> {
                    require(options.isNotEmpty()) { "Choice '$pid' needs options" }
                    min = 0f; max = (options.size - 1).toFloat()
                }
                ParamType.SLIDER -> {
                    min = p.getDouble("min").toFloat()
                    max = p.getDouble("max").toFloat()
                    require(max > min) { "Slider '$pid' needs max > min" }
                }
            }
            val def = p.optDouble("default", min.toDouble()).toFloat().coerceIn(min, max)
            EngineParam(
                id = pid,
                label = p.optString("label", pid),
                group = p.optString("group", "General"),
                type = type,
                min = min,
                max = max,
                default = def,
                step = p.optDouble("step", 0.0).toFloat(),
                unit = p.optString("unit", ""),
                options = options,
            )
        }

        private fun parsePresets(arr: JSONArray): List<EnginePreset> = List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            val vals = p.optJSONObject("values")
            val map = LinkedHashMap<String, Float>()
            if (vals != null) {
                val keys = vals.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = vals.getDouble(k).toFloat()
                }
            }
            EnginePreset(p.optString("name", "Preset ${i + 1}"), map)
        }
    }
}

/** An engine extracted to app storage: <filesDir>/engines/<id>_<timestamp>/{manifest.json, lib/[abi]/library.so} */
data class InstalledEngine(val manifest: EngineManifest, val dir: File) {
    val entryPath: String get() = File(dir, "lib/${manifest.entry}").absolutePath
    val preloadPaths: List<String> get() = manifest.preload.map { File(dir, "lib/$it").absolutePath }
}
