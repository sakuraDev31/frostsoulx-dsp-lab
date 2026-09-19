package app.resonance.player.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Owns the imported engines: install from zip, choose the active one, keep its parameter
 * values, and push everything into the [EngineProcessor] used by the player service.
 */
object EngineManager {
    val processor = EngineProcessor()

    private var initialized = false
    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val _installed = MutableStateFlow<List<InstalledEngine>>(emptyList())
    val installed: StateFlow<List<InstalledEngine>> = _installed.asStateFlow()

    private val _active = MutableStateFlow<InstalledEngine?>(null)
    val active: StateFlow<InstalledEngine?> = _active.asStateFlow()

    private val _values = MutableStateFlow<Map<String, Float>>(emptyMap())
    val values: StateFlow<Map<String, Float>> = _values.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val message = MutableStateFlow<String?>(null)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = app.getSharedPreferences("engine", Context.MODE_PRIVATE)
        processor.onError = { msg -> message.value = "Engine error: $msg" }
        _enabled.value = prefs.getBoolean("enabled", true)
        processor.bypass = !_enabled.value

        // Leftovers from an interrupted import.
        File(app.filesDir, "engines").listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith(".tmp")) it.deleteRecursively()
        }
        refresh()
        val activeId = prefs.getString("active", null)
        _installed.value.firstOrNull { it.manifest.id == activeId }?.let { select(it) }
    }

    @Synchronized
    fun select(engine: InstalledEngine?) {
        _active.value = engine
        prefs.edit().putString("active", engine?.manifest?.id).apply()
        val vals = if (engine != null) loadValues(engine) else emptyMap()
        _values.value = vals
        processor.setEngine(engine?.let { LoadedEngine(it.entryPath, it.preloadPaths) }, vals)
    }

    fun setParam(id: String, value: Float) {
        val e = _active.value ?: return
        val p = e.manifest.params.firstOrNull { it.id == id } ?: return
        val v = value.coerceIn(p.min, p.max)
        _values.update { it + (id to v) }
        processor.setParam(id, v)
        persistValues(e)
    }

    fun applyPreset(preset: EnginePreset) {
        for ((k, v) in preset.values) setParam(k, v)
    }

    fun resetToDefaults() {
        val e = _active.value ?: return
        for (p in e.manifest.params) setParam(p.id, p.default)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        processor.bypass = !on
        prefs.edit().putBoolean("enabled", on).apply()
    }

    fun dismissMessage() {
        message.value = null
    }

    @Synchronized
    fun remove(engine: InstalledEngine) {
        if (_active.value?.manifest?.id == engine.manifest.id) select(null)
        engine.dir.deleteRecursively()
        prefs.edit().remove("values_${engine.manifest.id}").apply()
        refresh()
    }

    /** Blocking; call from a background thread. */
    fun importZip(uri: Uri): Result<InstalledEngine> {
        val tmp = File(app.cacheDir, "import_${System.nanoTime()}.zip")
        val result = runCatching {
            val input = app.contentResolver.openInputStream(uri) ?: error("Could not open that file")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val installed = installFrom(tmp, 0)
            synchronized(this) {
                refresh()
                val fresh = _installed.value.first { it.manifest.id == installed.manifest.id }
                select(fresh)
                message.value = "Imported ${fresh.manifest.name} ${fresh.manifest.version}".trim()
                fresh
            }
        }
        tmp.delete()
        result.exceptionOrNull()?.let { message.value = "Import failed: ${it.message ?: it.javaClass.simpleName}" }
        return result
    }

    // ---------------------------------------------------------------------------------------

    private fun installFrom(zipFile: File, depth: Int): InstalledEngine {
        return ZipFile(zipFile).use { zip ->
            val entries: List<ZipEntry> = zip.entries().asSequence().filter { !it.isDirectory }.toList()

            fun nestedArchive(): ZipEntry? = entries
                .filter { it.name.endsWith(".zip", ignoreCase = true) }
                .minByOrNull { it.name.count { ch -> ch == '/' } }

            fun installNestedArchive(): InstalledEngine? {
                val nested = nestedArchive() ?: return null
                if (depth >= 3) return null
                val inner = File(app.cacheDir, "nested_${System.nanoTime()}.zip")
                return try {
                    zip.getInputStream(nested).use { i -> inner.outputStream().use { o -> i.copyTo(o) } }
                    installFrom(inner, depth + 1)
                } finally {
                    inner.delete()
                }
            }

            // manifest.json at the root, or inside a single top-level folder (shallowest wins).
            val manifestEntry = entries
                .filter { it.name.substringAfterLast('/') == "manifest.json" }
                .minByOrNull { e -> e.name.count { it == '/' } }

            if (manifestEntry == null) {
                return installNestedArchive()
                    ?: error("No manifest.json found. Select an engine ZIP or a downloaded artifact ZIP containing one.")
            }

            val prefix = manifestEntry.name.removeSuffix("manifest.json")
            val manifestText = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val manifest = EngineManifest.parse(manifestText)

            val abi = Build.SUPPORTED_ABIS.firstOrNull { a ->
                entries.any { it.name == "${prefix}lib/$a/${manifest.entry}" }
            } ?: return installNestedArchive()
                ?: error("Engine '${manifest.name}' has no ${manifest.entry} library for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})")

            val engines = File(app.filesDir, "engines").also { it.mkdirs() }
            val tmpDir = File(engines, ".tmp_${System.nanoTime()}")
            val finalDir = File(engines, "${manifest.id}_${System.currentTimeMillis()}")
            try {
                val libDir = File(tmpDir, "lib").also { it.mkdirs() }
                File(tmpDir, "manifest.json").writeText(manifestText)
                val libPrefix = "${prefix}lib/$abi/"
                for (e in entries) {
                    if (!e.name.startsWith(libPrefix)) continue
                    // Flatten to the bare file name: also rules out ../ path tricks.
                    val name = e.name.substringAfterLast('/')
                    if (name.isEmpty()) continue
                    val outFile = File(libDir, name)
                    zip.getInputStream(e).use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
                    outFile.setReadOnly()
                }
                require(File(libDir, manifest.entry).exists()) { "Missing ${manifest.entry}" }
                for (p in manifest.preload) require(File(libDir, p).exists()) { "Missing preload library $p" }
                require(tmpDir.renameTo(finalDir)) { "Could not move engine into place" }
            } catch (t: Throwable) {
                tmpDir.deleteRecursively()
                throw t
            }
            InstalledEngine(manifest, finalDir)
        }
    }

    private fun refresh() {
        val root = File(app.filesDir, "engines")
        val dirs = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && File(f, "manifest.json").exists() }
            ?.sortedBy { it.name.substringAfterLast('_').toLongOrNull() ?: 0L }
            ?: emptyList()
        val byId = LinkedHashMap<String, InstalledEngine>()
        val stale = ArrayList<File>()
        for (d in dirs) {
            val m = runCatching { EngineManifest.parse(File(d, "manifest.json").readText()) }.getOrNull()
            if (m == null) {
                stale.add(d)
                continue
            }
            byId[m.id]?.let { stale.add(it.dir) }   // older install of the same engine id
            byId[m.id] = InstalledEngine(m, d)
        }
        stale.forEach { it.deleteRecursively() }
        _installed.value = byId.values.sortedBy { it.manifest.name.lowercase() }
    }

    private fun loadValues(e: InstalledEngine): Map<String, Float> {
        val saved = prefs.getString("values_${e.manifest.id}", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return e.manifest.params.associate { p ->
            val v = saved?.optDouble(p.id, p.default.toDouble())?.toFloat() ?: p.default
            p.id to v.coerceIn(p.min, p.max)
        }
    }

    private fun persistValues(e: InstalledEngine) {
        val o = JSONObject()
        for ((k, v) in _values.value) o.put(k, v.toDouble())
        prefs.edit().putString("values_${e.manifest.id}", o.toString()).apply()
    }
}
