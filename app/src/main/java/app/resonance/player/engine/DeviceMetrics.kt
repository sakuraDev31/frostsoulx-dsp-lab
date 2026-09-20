package app.resonance.player.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import java.io.File

/**
 * Device-side metrics.
 *
 * Deliberately conservative: every field is either a value Android really exposes, or null.
 * Nothing here is estimated, and nothing is labelled more precisely than it is measured.
 *
 * In particular:
 *  - `processCpuPercent` is the *whole app process*, derived from /proc/self/stat utime+stime.
 *    Android does not let a normal app attribute CPU to the audio thread reliably, so this is
 *    never presented as "DSP CPU".
 *  - `gpuPercent` is always null: there is no public Android API for GPU utilisation. The UI
 *    prints "N/A" rather than inventing a number.
 */
data class DeviceMetrics(
    val processCpuPercent: Double? = null,
    val cpuSampleWindowMs: Long = 0,
    val coreCount: Int = Runtime.getRuntime().availableProcessors(),
    val onlineCores: Int? = null,
    val systemLoad1: Double? = null,
    val gpuPercent: Double? = null,          // always null: no public API
    val gpuRenderer: String? = null,
    val javaHeapUsedMb: Double = 0.0,
    val javaHeapMaxMb: Double = 0.0,
    val nativeHeapMb: Double = 0.0,
    val pssMb: Double? = null,
    val batteryTemperatureC: Double? = null,
    val thermalStatus: String? = null,
    val thermalThrottling: Boolean = false,
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
    val soc: String = socName(),
) {
    companion object {
        private fun socName(): String = when {
            Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() &&
                Build.SOC_MODEL != Build.UNKNOWN ->
                listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                    .filter { it.isNotBlank() && it != Build.UNKNOWN }
                    .joinToString(" ")
            Build.HARDWARE.isNotBlank() -> Build.HARDWARE
            else -> "unknown"
        }
    }
}

/**
 * Samples device metrics from a background thread. Each call is a handful of small /proc reads
 * plus Runtime counters; nothing here allocates on the audio thread and nothing is polled
 * faster than the caller asks for.
 */
class DeviceMetricsSampler(context: Context) {
    private val app = context.applicationContext
    private val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var lastProcJiffies = -1L
    private var lastWallNs = 0L
    private val clockTicksPerSec = 100.0   // Android's USER_HZ is 100 on every supported ABI
    private var cachedRenderer: String? = null

    /** GPU renderer string is set once from the Compose/GL side if it can be read at all. */
    fun noteGpuRenderer(renderer: String?) {
        if (!renderer.isNullOrBlank()) cachedRenderer = renderer
    }

    fun sample(): DeviceMetrics {
        val rt = Runtime.getRuntime()
        return DeviceMetrics(
            processCpuPercent = processCpu(),
            cpuSampleWindowMs = lastWindowMs,
            onlineCores = onlineCores(),
            systemLoad1 = loadAverage1(),
            gpuPercent = null,                  // no public Android API; never fabricated
            gpuRenderer = cachedRenderer,
            javaHeapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576.0,
            javaHeapMaxMb = rt.maxMemory() / 1048576.0,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576.0,
            pssMb = null,                       // Debug.getMemoryInfo() is far too slow to poll
            batteryTemperatureC = batteryTemperature(),
            thermalStatus = thermalStatusName(),
            thermalThrottling = thermalThrottling(),
        )
    }

    /** One-shot, slow: only called when a report is exported. */
    fun samplePss(): Double? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss / 1024.0
    }.getOrNull()

    private var lastWindowMs = 0L

    /**
     * Whole-process CPU as a percentage of one core, averaged over the interval since the last
     * call. Reads /proc/self/stat, which stays readable for a process's own stat file on all
     * supported Android versions.
     */
    private fun processCpu(): Double? {
        val jiffies = runCatching {
            val fields = File("/proc/self/stat").readText().split(' ')
            // utime (14) + stime (15), 1-based as in proc(5); the comm field has no spaces here.
            fields[13].toLong() + fields[14].toLong()
        }.getOrNull() ?: return null

        val now = System.nanoTime()
        val prev = lastProcJiffies
        val prevNs = lastWallNs
        lastProcJiffies = jiffies
        lastWallNs = now
        if (prev < 0 || now <= prevNs) return null

        val elapsedSec = (now - prevNs) / 1e9
        lastWindowMs = ((now - prevNs) / 1_000_000)
        if (elapsedSec < 0.05) return null
        val cpuSec = (jiffies - prev) / clockTicksPerSec
        return (cpuSec / elapsedSec * 100.0).coerceAtLeast(0.0)
    }

    private fun onlineCores(): Int? = runCatching {
        // e.g. "0-3,6-7"
        File("/sys/devices/system/cpu/online").readText().trim()
            .split(',')
            .sumOf { part ->
                val r = part.split('-')
                if (r.size == 2) r[1].toInt() - r[0].toInt() + 1 else 1
            }
    }.getOrNull()

    private fun loadAverage1(): Double? = runCatching {
        File("/proc/loadavg").readText().substringBefore(' ').toDouble()
    }.getOrNull()

    private fun batteryTemperature(): Double? = runCatching {
        val intent: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == null || tenths == Int.MIN_VALUE) null else tenths / 10.0
    }.getOrNull()

    private fun thermalStatusValue(): Int? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching { powerManager?.currentThermalStatus }.getOrNull()
    }

    private fun thermalStatusName(): String? = when (thermalStatusValue()) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> null
    }

    private fun thermalThrottling(): Boolean {
        val v = thermalStatusValue() ?: return false
        return v >= PowerManager.THERMAL_STATUS_MODERATE
    }

    /** Uses Process.myTid() only to confirm the API exists; never presented as DSP CPU. */
    @Suppress("unused")
    fun processId(): Int = Process.myPid()
}
