// Real-time audio telemetry for the Resonance DSP Lab.
//
// Split in two halves:
//
//   * audio-thread half  - telemetry_block_*(), telemetry_analyze_*(): fixed work per block,
//                          no allocation, no locks, no logging, no syscalls except one
//                          clock_gettime(CLOCK_MONOTONIC) pair per block.
//   * analyzer half      - telemetry_snapshot(), telemetry_spectrum(): called from a normal
//                          background thread. Allocates lazily (FFT tables) and does the
//                          expensive maths (FFT, true peak, gated loudness).
//
// Values the audio thread produces are published through relaxed atomics; the analyzer only
// ever reads them. Nothing here ever blocks the audio thread.
#pragma once

#include <cstdint>

namespace telemetry {

// Indices into the snapshot buffer (doubles). Mirrored by TelemetrySlots.kt - keep in sync.
enum Slot : int {
    S_SAMPLE_RATE = 0,
    S_FRAMES_PROCESSED,
    S_CALLBACK_COUNT,
    S_BLOCK_COUNT,          // engine invocations (quantum blocks)
    S_PROC_LAST_NS,
    S_PROC_AVG_NS,
    S_PROC_MIN_NS,
    S_PROC_MAX_NS,
    S_PROC_STDDEV_NS,
    S_RT_RATIO_AVG,         // 0..1, average processing time / block wall duration
    S_RT_RATIO_LAST,
    S_DEADLINE_MISSES,
    S_QUANTUM,              // internal DSP quantum in frames (0 = follows host block)
    S_HOST_BLOCK_FRAMES,    // last host callback block size
    S_LATENCY_FRAMES,       // latency added by the quantum FIFO
    S_IN_RMS_L,
    S_IN_RMS_R,
    S_IN_PEAK_L,
    S_IN_PEAK_R,
    S_OUT_RMS_L,
    S_OUT_RMS_R,
    S_OUT_PEAK_L,
    S_OUT_PEAK_R,
    S_OUT_HOLD_L,
    S_OUT_HOLD_R,
    S_TRUE_PEAK_L_DB,       // -200 when not yet measured
    S_TRUE_PEAK_R_DB,
    S_CLIP_COUNT,
    S_HEADROOM_DB,
    S_CORRELATION,          // -1..1
    S_BALANCE,              // -1 (left) .. +1 (right)
    S_WIDTH,                // 0 = mono, 1 = normal stereo, >1 = wide
    S_MID_RMS,
    S_SIDE_RMS,
    S_MONO_RISK,            // 0..1, energy lost when folded to mono
    S_LUFS_MOMENTARY,       // -200 when not yet available
    S_LUFS_SHORT_TERM,
    S_LUFS_INTEGRATED,
    S_LRA,
    S_NAN_COUNT,
    S_INF_COUNT,
    S_ENGINE_ACTIVE,
    S_BYPASS,
    S_AGE_NS,               // nanoseconds since the last audio block
    S_PEAK_FREQ_HZ,
    S_PEAK_MAG_DB,
    S_SPECTRAL_RMS_DB,
    S_SPECTRAL_CENTROID_HZ,
    S_BENCH_ACTIVE,
    S_BENCH_BLOCKS,
    S_BENCH_AVG_NS,
    S_BENCH_MIN_NS,
    S_BENCH_MAX_NS,
    S_BENCH_P50_NS,
    S_BENCH_P95_NS,
    S_BENCH_P99_NS,
    S_BENCH_STDDEV_NS,
    S_BENCH_RT_RATIO,
    S_BENCH_DEADLINE_MISSES,
    S_FIFO_UNDERFLOWS,
    S_LOUDNESS_BLOCKS,
    S_TRUE_PEAK_HOLD_DB,
    S_RESET_COUNT,
    S_SLOT_COUNT,
};

// ---- configuration (analyzer / UI thread) -------------------------------------------------

void configure(int sampleRate);
void setEngineActive(bool active);
void setBypass(bool bypass);
void setQuantum(int quantumFrames, int latencyFrames);
void resetStats();          // clears timing + loudness history, keeps configuration
void resetLoudness();

// ---- audio thread -------------------------------------------------------------------------

// Wall-clock nanoseconds from CLOCK_MONOTONIC.
int64_t nowNs();

// Input measurement: interleaved stereo floats, read only.
void analyzeInput(const float* interleaved, int frames);

// Records one engine invocation. `frames` is the block the engine saw.
void recordBlock(int64_t startNs, int64_t endNs, int frames);

// Records one host audio callback (may contain several engine blocks).
void recordCallback(int hostFrames);

// Output measurement + sanitation: replaces NaN/Inf with 0 and clamps to [-1, 1] in place,
// counts clipped samples, updates levels, stereo maths, K-weighted loudness and feeds the
// analyzer ring buffer. This is the only pass over the output block.
void analyzeOutputAndSanitize(float* interleaved, int frames);

// ---- analyzer thread ------------------------------------------------------------------------

// Fills `out` with S_SLOT_COUNT doubles. Returns the number written (0 if `cap` is too small).
int snapshot(double* out, int cap);

// Computes the magnitude spectrum of the newest `fftSize` frames (mono sum, Hann window)
// into `outDb` (fftSize/2 bins, dBFS), and refreshes the true-peak / spectral slots.
// `fftSize` must be a power of two between 256 and 8192. Returns bins written.
int spectrum(float* outDb, int cap, int fftSize);

// ---- benchmark --------------------------------------------------------------------------

void benchmarkStart(int64_t durationMs);
void benchmarkStop();
bool benchmarkRunning();

// ---- external counters (reported by the Kotlin side) -------------------------------------

void noteUnderrun();
uint32_t fifoUnderflows();

}  // namespace telemetry
