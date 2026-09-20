package app.resonance.player.engine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** FFT sizes the analyzer offers. Larger = finer frequency resolution, slower refresh. */
enum class FftSize(val bins: Int, val label: String) {
    N1024(1024, "1024"),
    N2048(2048, "2048"),
    N4096(4096, "4096");

    val binCount: Int get() = bins / 2
}

/**
 * A spectrum frame. `magsDb` is reused between frames to avoid churning arrays 20x a second;
 * `serial` changes on every refresh so Compose knows to redraw.
 */
class SpectrumFrame(val magsDb: FloatArray, val sampleRate: Int, val fftSize: Int, val serial: Int)

/**
 * Owns the analyzer thread.
 *
 * The audio thread only ever writes compact counters into native memory (see telemetry.cpp).
 * This class runs on Dispatchers.Default, pulls one snapshot per tick, runs the FFT there,
 * and publishes an immutable [AudioTelemetry] plus a spectrum frame. Compose collects those
 * StateFlows; the audio callback is never involved in UI work and never blocks on it.
 *
 * Polling only runs while the DSP Lab screen is on-screen ([addObserver] / [removeObserver]).
 */
class TelemetryHub(context: Context) {

    private val app = context.applicationContext
    val deviceSampler = DeviceMetricsSampler(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val observers = AtomicInteger(0)

    private val _telemetry = MutableStateFlow(AudioTelemetry(available = NativeEngine.available))
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val _device = MutableStateFlow(DeviceMetrics())
    val device: StateFlow<DeviceMetrics> = _device.asStateFlow()

    private val _hardware = MutableStateFlow(AudioHardware())
    val hardware: StateFlow<AudioHardware> = _hardware.asStateFlow()

    private val _spectrum = MutableStateFlow<SpectrumFrame?>(null)
    val spectrum: StateFlow<SpectrumFrame?> = _spectrum.asStateFlow()

    private val _fftSize = MutableStateFlow(FftSize.N2048)
    val fftSize: StateFlow<FftSize> = _fftSize.asStateFlow()

    /** UI refresh rate. 30 Hz is plenty for meters and keeps mid-range devices comfortable. */
    private val _updateHz = MutableStateFlow(30)
    val updateHz: StateFlow<Int> = _updateHz.asStateFlow()

    private val _benchmark = MutableStateFlow(BenchmarkState())
    val benchmark: StateFlow<BenchmarkState> = _benchmark.asStateFlow()

    // Scratch buffers owned by the analyzer thread. Allocated once.
    private val snapshotBuf = DoubleArray(Slot.COUNT)
    private var spectrumBuf = FloatArray(FftSize.N2048.binCount)
    private var spectrumSerial = 0
    private var deviceTick = 0

    fun setFftSize(size: FftSize) {
        _fftSize.value = size
    }

    fun setUpdateHz(hz: Int) {
        _updateHz.value = hz.coerceIn(10, 60)
    }

    fun addObserver() {
        if (observers.incrementAndGet() == 1) start()
    }

    fun removeObserver() {
        if (observers.decrementAndGet() <= 0) {
            observers.set(0)
            job?.cancel()
            job = null
        }
    }

    private fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val started = System.nanoTime()
                pollOnce()
                val periodNs = 1_000_000_000L / _updateHz.value
                val spent = System.nanoTime() - started
                delay(((periodNs - spent) / 1_000_000L).coerceAtLeast(1L))
            }
        }
    }

    private fun pollOnce() {
        if (!NativeEngine.available) {
            _telemetry.value = AudioTelemetry(available = false)
            return
        }

        // Spectrum first: it also refreshes the true-peak and spectral slots that the
        // snapshot below reads, so both come from the same analysis window.
        val size = _fftSize.value
        if (spectrumBuf.size != size.binCount) spectrumBuf = FloatArray(size.binCount)
        val bins = runCatching { NativeEngine.nativeSpectrum(spectrumBuf, size.bins) }.getOrDefault(0)

        val n = runCatching { NativeEngine.nativeSnapshot(snapshotBuf) }.getOrDefault(0)
        if (n <= 0) return
        val t = AudioTelemetry.from(snapshotBuf, true)
        _telemetry.value = t

        if (bins > 0) {
            _spectrum.value = SpectrumFrame(spectrumBuf.copyOf(bins), t.sampleRate, size.bins, ++spectrumSerial)
        }

        // Device metrics change far more slowly than audio meters: sample about twice a second.
        if (++deviceTick >= _updateHz.value / 2) {
            deviceTick = 0
            _device.value = deviceSampler.sample()
            _hardware.value = AudioHardwareReader.read(
                app,
                encoding = EngineManager.processor.encodingName,
                channels = 2,
            )
        }

        // Benchmark countdown / completion.
        val b = _benchmark.value
        if (b.running) {
            val remaining = b.endsAtMs - System.currentTimeMillis()
            if (remaining <= 0 || !t.benchActive) {
                runCatching { NativeEngine.nativeBenchmarkStop() }
                // One more snapshot so the final percentiles are in.
                if (runCatching { NativeEngine.nativeSnapshot(snapshotBuf) }.getOrDefault(0) > 0) {
                    _telemetry.value = AudioTelemetry.from(snapshotBuf, true)
                }
                _benchmark.value = b.copy(running = false, remainingMs = 0, completed = true)
            } else {
                _benchmark.value = b.copy(remainingMs = remaining)
            }
        }
    }

    // --- benchmark ------------------------------------------------------------------------

    fun startBenchmark(durationSec: Int) {
        if (!NativeEngine.available) return
        runCatching { NativeEngine.nativeBenchmarkStart(durationSec * 1000L) }
        _benchmark.value = BenchmarkState(
            running = true,
            durationSec = durationSec,
            endsAtMs = System.currentTimeMillis() + durationSec * 1000L,
            remainingMs = durationSec * 1000L,
            completed = false,
        )
        addObserverIfIdle()
    }

    fun stopBenchmark() {
        runCatching { NativeEngine.nativeBenchmarkStop() }
        _benchmark.value = _benchmark.value.copy(running = false, remainingMs = 0, completed = true)
    }

    /** Keeps polling alive for the duration of a benchmark even if the screen scrolls away. */
    private fun addObserverIfIdle() {
        if (job == null) start()
    }

    fun resetStats() {
        runCatching { NativeEngine.nativeResetStats() }
        _benchmark.value = BenchmarkState()
    }
}

data class BenchmarkState(
    val running: Boolean = false,
    val durationSec: Int = 0,
    val endsAtMs: Long = 0,
    val remainingMs: Long = 0,
    val completed: Boolean = false,
)
