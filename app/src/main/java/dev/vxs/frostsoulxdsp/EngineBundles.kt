package dev.vxs.frostsoulxdsp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Bounded ZIP I/O. Only explicitly trusted, ABI-validated compiled bundles can execute. */
object EngineBundles {
    private const val ZIP_LIMIT = 128L * 1024 * 1024
    private const val EXPANDED_LIMIT = 256L * 1024 * 1024
    private val abis = listOf("arm64-v8a", "x86_64")
    fun staged(context: Context) = File(context.filesDir, "staged-engine.zip")

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Engine ZIP exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return total
    }

    fun stage(context: Context, uri: Uri): String {
        val temp = File.createTempFile("engine-", ".zip", context.filesDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { copyBounded(input, it, ZIP_LIMIT) }
            } ?: error("Cannot read the selected document")
            val kind = validate(temp)
            Files.move(temp.toPath(), staged(context).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return kind
        } finally { temp.delete() }
    }

    private fun safePath(name: String): Boolean = name.isNotEmpty() && !name.startsWith("/") &&
        '\\' !in name && name.split('/').none { it == ".." || it == "." }

    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            require(total <= EXPANDED_LIMIT) { "Expanded ZIP too large" }
            hash.update(buffer, 0, n)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validate(file: File): String = ZipFile(file).use { zip ->
        require(zip.size() in 1..2048) { "Invalid ZIP entry count" }
        val entries = zip.entries().toList()
        require(entries.all { safePath(it.name) }) { "Unsafe ZIP path" }
        require(entries.map { it.name.trimEnd('/') }.distinct().size == entries.size) { "Duplicate ZIP entries" }
        require(entries.all { it.size >= 0 } && entries.sumOf { it.size } <= EXPANDED_LIMIT) { "Expanded ZIP exceeds 256 MiB" }
        val files = entries.filterNot { it.isDirectory }
        require(files.count { it.name.substringAfterLast('/') == "immersive_audio_engine.h" } == 1) { "Missing or ambiguous public engine header" }
        val sources = files.count { it.name.substringAfterLast('/') == "immersive_audio_engine.cpp" }
        require(sources <= 1) { "Ambiguous engine source" }
        val manifests = files.filter { it.name.substringAfterLast('/') == "engine-manifest.json" }
        require(manifests.size <= 1) { "Ambiguous manifest" }
        val hashes = mutableMapOf<String, String>()
        // Stream every file to validate CRC and contents without keeping a ZIP in memory.
        files.forEach { entry -> hashes[entry.name] = zip.getInputStream(entry).use(::digest) }
        if (manifests.isNotEmpty()) {
            val entry = manifests.single()
            require(entry.size <= 1024 * 1024) { "Manifest too large" }
            val manifest = zip.getInputStream(entry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optInt("format") == 1) { "Unsupported engine manifest" }
            val checksums = manifest.optJSONObject("sha256") ?: error("Missing bundle checksums")
            val prefix = entry.name.substringBeforeLast('/', "").let { if (it.isEmpty()) it else "$it/" }
            if (sources == 0) {
                require(manifest.optInt("engine_api") == 1) { "Requires C plugin ABI v1; rebuild only the engine with build-engine-bundle.sh" }
                require(files.filter { it != entry }.all { checksums.has(it.name.removePrefix(prefix)) }) { "Every compiled bundle file must have a checksum" }
                require(manifest.optString("kind") == "compiled") { "Invalid compiled manifest" }
            }
            checksums.keys().forEach { path ->
                require(safePath(path) && hashes[prefix + path] == checksums.getString(path)) { "Checksum mismatch: $path" }
            }
        }
        if (sources == 1) "Source" else {
            require(manifests.isNotEmpty()) { "Compiled ZIP requires a versioned manifest" }
            val available = abis.filter { abi -> files.any { it.name.endsWith("lib/$abi/libfrostsoulx_engine.so") } }
            require(available.isNotEmpty()) { "ZIP has no supported native ABI" }
            available.forEach { abi ->
                listOf("libfrostsoulx_engine.so", "libphonon.so").forEach { name ->
                    val matches = files.filter { it.name.endsWith("lib/$abi/$name") }
                    require(matches.size == 1) { "Missing or ambiguous $abi/$name" }
                    val header = ByteArray(20)
                    zip.getInputStream(matches.single()).use { java.io.DataInputStream(it).readFully(header) }
                    val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
                    require(header.take(6) == listOf<Byte>(127, 69, 76, 70, 2, 1) && header[16] == 3.toByte() &&
                        machine == if (abi == "arm64-v8a") 183 else 62) { "Wrong ELF ABI: $abi/$name" }
                }
            }
            "Compiled"
        }
    }

    fun exportStaged(context: Context, uri: Uri) {
        require(staged(context).isFile) { "No staged ZIP to export" }
        writeDocument(context, uri) { output -> staged(context).inputStream().use { copyBounded(it, output, ZIP_LIMIT) } }
    }

    /** Export exactly the loaded generation, or the built-in APK engine. */
    fun exportActive(context: Context, uri: Uri) {
        activeDirectory(context)?.let { active ->
            writeDocument(context, uri) { out -> File(active, "bundle.zip").inputStream().use { copyBounded(it, out, ZIP_LIMIT) } }
            return
        }
        val temp = File.createTempFile("engine-sdk-", ".zip", context.cacheDir)
        try {
            val hashes = JSONObject()
            val found = mutableSetOf<String>()
            ZipOutputStream(temp.outputStream().buffered()).use { out ->
                fun entry(name: String, input: InputStream) {
                    out.putNextEntry(ZipEntry(name))
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(32 * 1024)
                    input.use {
                        var total = 0L
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= ZIP_LIMIT) { "Native library too large" }
                            hash.update(buffer, 0, count)
                            out.write(buffer, 0, count)
                        }
                    }
                    out.closeEntry()
                    hashes.put(name, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                }
                for (name in listOf("include/frostsoulx/immersive_audio_engine.h", "include/engine_api.h", "CMakeLists.txt", "LICENSE.md")) {
                    entry(name, context.assets.open("engine-sdk/$name"))
                }
                val apks = listOf(context.applicationInfo.sourceDir) + (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
                apks.forEach { apk -> ZipFile(apk).use { zip ->
                    abis.forEach { abi ->
                        listOf("libfrostsoulx_engine.so", "libphonon.so", "libc++_shared.so").forEach { library ->
                            val name = "lib/$abi/$library"
                            zip.getEntry(name)?.let { if (found.add(name)) entry(name, zip.getInputStream(it)) }
                        }
                    }
                } }
                val included = abis.filter { "lib/$it/libfrostsoulx_engine.so" in found && "lib/$it/libphonon.so" in found }
                require(included.isNotEmpty()) { "APK has no exportable native engine" }
                val manifest = JSONObject().put("format", 1).put("kind", "compiled").put("abis", JSONArray(included))
                    .put("engine_api", 1).put("activation", "runtime-plugin").put("sha256", hashes)
                out.putNextEntry(ZipEntry("engine-manifest.json"))
                out.write(manifest.toString(2).toByteArray())
                out.closeEntry()
            }
            require(temp.length() <= ZIP_LIMIT) { "Export exceeds 128 MiB" }
            writeDocument(context, uri) { output -> temp.inputStream().use { copyBounded(it, output, ZIP_LIMIT) } }
        } finally { temp.delete() }
    }

    private fun preferences(context: Context) = context.getSharedPreferences("engine_loader", Context.MODE_PRIVATE)
    private fun root(context: Context) = File(context.noBackupFilesDir, "engines").also { it.mkdirs() }
    private fun activeDirectory(context: Context): File? {
        val name = preferences(context).getString("active", null) ?: return null
        require(name.matches(Regex("engine-[a-zA-Z0-9-]+"))) { "Invalid active generation" }
        return File(root(context), name).takeIf { it.isDirectory }
    }
    fun activeLabel(context: Context) = activeDirectory(context)?.name ?: "Built-in engine"

    // Compare dependency content, not filenames. Android reuses already-loaded SONAMEs;
    // silently accepting a different Steam Audio or C++ runtime would test the wrong code.
    private fun installedDigest(context: Context, name: String): String? {
        val apks = listOf(context.applicationInfo.sourceDir) + (context.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
        for (apk in apks) ZipFile(apk).use { zip ->
            zip.getEntry(name)?.let { return zip.getInputStream(it).use(::digest) }
        }
        return null
    }

    /** Player MUST be released first. Failure leaves the old native engine and disk pointer intact. */
    fun activateStaged(context: Context): String {
        require(validate(staged(context)) == "Compiled") {
            "Source ZIP staged. Compile it with tools/build-engine-bundle.sh, then import the compiled ZIP. No APK rebuild needed."
        }
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { it in abis } ?: error("Unsupported device ABI")
        val generation = File(root(context), "engine-${java.util.UUID.randomUUID()}")
        check(generation.mkdir()) { "Cannot create private engine directory" }
        var activated = false
        try {
            ZipFile(staged(context)).use { zip ->
                val entries = zip.entries().toList().filterNot { it.isDirectory }
                fun match(name: String) = entries.singleOrNull { it.name.endsWith("lib/$abi/$name") }
                val engine = match("libfrostsoulx_engine.so") ?: error("ZIP does not contain $abi")
                for (name in listOf("libphonon.so", "libc++_shared.so")) {
                    val dep = match(name)
                    if (name == "libphonon.so") require(dep != null) { "Missing Steam Audio dependency" }
                    if (dep != null) require(zip.getInputStream(dep).use(::digest) == installedDigest(context, "lib/$abi/$name")) {
                        "$name differs from the installed host. Keep its SDK/runtime unchanged for engine-only swaps; changing it requires a host APK update."
                    }
                }
                val library = File(generation, "libfrostsoulx_engine.so")
                library.outputStream().use { out ->
                    // Android dynamic-code-loading policy: make the inode read-only while
                    // the already-open descriptor writes the validated bytes.
                    check(library.setReadOnly()) { "Cannot protect native library" }
                    zip.getInputStream(engine).use { copyBounded(it, out, EXPANDED_LIMIT) }
                }
            }
            staged(context).inputStream().use { input -> File(generation, "bundle.zip").outputStream().use { copyBounded(input, it, ZIP_LIMIT) } }
            val prefs = preferences(context)
            check(prefs.edit().putBoolean("loading", true).commit()) { "Cannot save recovery marker" }
            val error = NativeEngine.loadPlugin(File(generation, "libfrostsoulx_engine.so").absolutePath)
            if (error.isNotEmpty()) {
                prefs.edit().putBoolean("loading", false).commit()
                error(error)
            }
            val old = activeDirectory(context)
            // A disk failure after native activation must roll back the native instance too.
            if (!prefs.edit().putString("active", generation.name).putBoolean("loading", false).commit()) {
                NativeEngine.loadPlugin(old?.let { File(it, "libfrostsoulx_engine.so").absolutePath })
                error("Could not persist engine selection; activation rolled back")
            }
            activated = true
            old?.deleteRecursively()
            return "Loaded $abi · C ABI v1 · ${generation.name.takeLast(8)}"
        } finally { if (!activated) generation.deleteRecursively() }
    }

    fun restore(context: Context): String {
        val prefs = preferences(context)
        if (prefs.getBoolean("loading", false)) {
            prefs.edit().remove("active").putBoolean("loading", false).commit()
            return "Previous load was interrupted. Built-in engine restored; re-import only trusted binaries."
        }
        val active = activeDirectory(context) ?: return "Built-in engine · C ABI v1"
        prefs.edit().putBoolean("loading", true).commit()
        val error = NativeEngine.loadPlugin(File(active, "libfrostsoulx_engine.so").absolutePath)
        if (error.isNotEmpty()) prefs.edit().remove("active").commit()
        prefs.edit().putBoolean("loading", false).commit()
        return if (error.isEmpty()) "Restored ${active.name.takeLast(8)}" else "Built-in fallback: $error"
    }

    fun unload(context: Context): String {
        val error = NativeEngine.loadPlugin(null)
        require(error.isEmpty()) { error }
        val previous = activeDirectory(context)
        check(preferences(context).edit().remove("active").putBoolean("loading", false).commit()) { "Could not persist built-in selection" }
        previous?.deleteRecursively()
        return "Imported engine unloaded · built-in engine restored"
    }

    private fun writeDocument(context: Context, uri: Uri, write: (OutputStream) -> Unit) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use(write) ?: error("Cannot write ZIP document")
        } catch (error: Exception) {
            // Do not leave a corrupt ZIP that looks like a successful export.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw error
        }
    }
}
