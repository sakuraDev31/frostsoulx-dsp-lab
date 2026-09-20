#include "telemetry.h"

#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>
#include <mutex>
#include <vector>

namespace telemetry {
namespace {

constexpr double kSilenceDb = -200.0;
constexpr int kRingFrames = 32768;            // ~0.68 s at 48 kHz, power of two
constexpr int kRingMask = kRingFrames - 1;
constexpr int kMomentarySubBlocks = 4;        // 4 x 100 ms = 400 ms
constexpr int kShortTermSubBlocks = 30;       // 30 x 100 ms = 3 s
constexpr int kSubRing = 32;                  // >= kShortTermSubBlocks
constexpr int kMaxMomentaryHistory = 36000;   // 100 ms steps -> 1 hour
constexpr int kMaxShortHistory = 3600;        // 1 s steps -> 1 hour
constexpr int kBenchBins = 4096;              // 4 us per bin -> up to 16.4 ms
constexpr int64_t kBenchBinNs = 4000;

// Relaxed atomic double: on arm64 / x86_64 these compile to plain loads and stores, so the
// audio thread pays nothing while the analyzer thread still reads without a data race.
struct AD {
    std::atomic<double> v{0.0};
    inline void set(double x) { v.store(x, std::memory_order_relaxed); }
    inline double get() const { return v.load(std::memory_order_relaxed); }
};
struct AU {
    std::atomic<uint64_t> v{0};
    inline void add(uint64_t x = 1) { v.fetch_add(x, std::memory_order_relaxed); }
    inline void set(uint64_t x) { v.store(x, std::memory_order_relaxed); }
    inline uint64_t get() const { return v.load(std::memory_order_relaxed); }
};

struct Biquad {
    double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    double z1 = 0, z2 = 0;
    inline double process(double x) {
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    inline void clear() { z1 = z2 = 0; }
};

// BS.1770-4 K-weighting: 1 - high shelf (+4 dB @ 1681.97 Hz), 2 - RLB high pass (38.13 Hz).
// Designed with the RBJ cookbook so any sample rate works; at 48 kHz these reproduce the
// coefficient table in the recommendation.
Biquad designHighShelf(double sr, double f0, double gainDb, double q) {
    Biquad f;
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * f0 / sr;
    const double cw = std::cos(w0), sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double tsa = 2.0 * std::sqrt(A) * alpha;
    const double a0 = (A + 1) - (A - 1) * cw + tsa;
    f.b0 = (A * ((A + 1) + (A - 1) * cw + tsa)) / a0;
    f.b1 = (-2 * A * ((A - 1) + (A + 1) * cw)) / a0;
    f.b2 = (A * ((A + 1) + (A - 1) * cw - tsa)) / a0;
    f.a1 = (2 * ((A - 1) - (A + 1) * cw)) / a0;
    f.a2 = ((A + 1) - (A - 1) * cw - tsa) / a0;
    return f;
}

Biquad designHighPass(double sr, double f0, double q) {
    Biquad f;
    const double w0 = 2.0 * M_PI * f0 / sr;
    const double cw = std::cos(w0), sw = std::sin(w0);
    const double alpha = sw / (2.0 * q);
    const double a0 = 1 + alpha;
    f.b0 = ((1 + cw) / 2) / a0;
    f.b1 = (-(1 + cw)) / a0;
    f.b2 = ((1 + cw) / 2) / a0;
    f.a1 = (-2 * cw) / a0;
    f.a2 = (1 - alpha) / a0;
    return f;
}

struct State {
    // --- configuration -------------------------------------------------------------------
    std::atomic<int> sampleRate{48000};
    std::atomic<bool> filtersReady{false};
    std::atomic<int> quantum{0};
    std::atomic<int> latencyFrames{0};
    std::atomic<bool> engineActive{false};
    std::atomic<bool> bypass{false};

    // --- counters ------------------------------------------------------------------------
    AU framesProcessed, callbackCount, blockCount, deadlineMisses, clipCount;
    AU nanCount, infCount, underruns, resetCount;
    std::atomic<int> hostBlockFrames{0};

    // --- timing --------------------------------------------------------------------------
    AD procLastNs, procMinNs, procMaxNs;
    std::atomic<double> procSumNs{0.0}, procSumSqNs{0.0}, rtRatioSum{0.0};
    AD rtRatioLast;
    AD lastBlockNs;

    // --- levels --------------------------------------------------------------------------
    AD inRmsL, inRmsR, inPeakL, inPeakR;
    AD outRmsL, outRmsR, outPeakL, outPeakR, holdL, holdR;
    AD correlation, balance, width, midRms, sideRms, monoRisk;
    AD truePeakLDb, truePeakRDb, truePeakHoldDb;
    AD peakFreqHz, peakMagDb, spectralRmsDb, spectralCentroidHz;
    // Peak-hold decay is driven by block count, not wall time, so it is allocation free.
    double holdDecayPerBlock = 0.94;

    // --- ring buffer for the analyzer ------------------------------------------------------
    std::atomic<float> ringL[kRingFrames];
    std::atomic<float> ringR[kRingFrames];
    std::atomic<uint32_t> ringWrite{0};

    // --- loudness (audio thread owns the filter state) --------------------------------------
    Biquad kShelfL, kShelfR, kHpL, kHpR;
    double subSumL = 0, subSumR = 0;
    int subFill = 0;
    int subSize = 4800;                      // 100 ms
    std::atomic<float> subZ[kSubRing];       // per-sub-block mean square (K-weighted, L+R)
    std::atomic<uint32_t> subWrite{0};
    std::atomic<float> momHistory[kMaxMomentaryHistory];
    std::atomic<uint32_t> momCount{0};
    std::atomic<float> stHistory[kMaxShortHistory];
    std::atomic<uint32_t> stCount{0};
    AD lufsMomentary, lufsShortTerm;
    AU loudnessBlocks;

    // --- benchmark --------------------------------------------------------------------------
    std::atomic<bool> benchActive{false};
    std::atomic<int64_t> benchEndNs{0};
    AU benchBlocks, benchDeadlineMisses;
    AD benchMinNs, benchMaxNs;
    std::atomic<double> benchSumNs{0.0}, benchSumSqNs{0.0}, benchRtSum{0.0};
    std::atomic<uint32_t> benchHist[kBenchBins];
    AD benchAvgNs, benchP50, benchP95, benchP99, benchStdDev, benchRtRatio;
    std::atomic<bool> benchFinished{false};
};

State g;
std::mutex g_fftMutex;   // analyzer thread only

void resetTimingLocked() {
    g.procLastNs.set(0);
    g.procMinNs.set(0);
    g.procMaxNs.set(0);
    g.procSumNs.store(0.0, std::memory_order_relaxed);
    g.procSumSqNs.store(0.0, std::memory_order_relaxed);
    g.rtRatioSum.store(0.0, std::memory_order_relaxed);
    g.rtRatioLast.set(0);
    g.blockCount.set(0);
    g.callbackCount.set(0);
    g.framesProcessed.set(0);
    g.deadlineMisses.set(0);
    g.clipCount.set(0);
    g.nanCount.set(0);
    g.infCount.set(0);
    g.underruns.set(0);
}

void designFilters(int sr) {
    g.kShelfL = designHighShelf(sr, 1681.974450955533, 3.999843853973347, 0.7071752369554196);
    g.kShelfR = g.kShelfL;
    g.kHpL = designHighPass(sr, 38.13547087602444, 0.5003270373238773);
    g.kHpR = g.kHpL;
    g.subSize = std::max(64, sr / 10);
}

inline double toDb(double lin) { return lin > 1e-10 ? 20.0 * std::log10(lin) : kSilenceDb; }

}  // namespace

// ---------------------------------------------------------------------------------------------

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void configure(int sampleRate) {
    if (sampleRate <= 0) return;
    const bool same = g.sampleRate.exchange(sampleRate, std::memory_order_relaxed) == sampleRate;
    if (same && g.filtersReady.load(std::memory_order_relaxed)) return;
    designFilters(sampleRate);
    g.filtersReady.store(true, std::memory_order_relaxed);
    resetLoudness();
}

void setEngineActive(bool active) { g.engineActive.store(active, std::memory_order_relaxed); }
void setBypass(bool bypass) { g.bypass.store(bypass, std::memory_order_relaxed); }

void setQuantum(int quantumFrames, int latencyFrames) {
    g.quantum.store(quantumFrames, std::memory_order_relaxed);
    g.latencyFrames.store(latencyFrames, std::memory_order_relaxed);
}

void resetStats() {
    resetTimingLocked();
    g.truePeakLDb.set(kSilenceDb);
    g.truePeakRDb.set(kSilenceDb);
    g.truePeakHoldDb.set(kSilenceDb);
    g.holdL.set(0);
    g.holdR.set(0);
    g.resetCount.add();
    resetLoudness();
}

void resetLoudness() {
    g.kShelfL.clear(); g.kShelfR.clear(); g.kHpL.clear(); g.kHpR.clear();
    g.subSumL = g.subSumR = 0;
    g.subFill = 0;
    g.subWrite.store(0, std::memory_order_relaxed);
    g.momCount.store(0, std::memory_order_relaxed);
    g.stCount.store(0, std::memory_order_relaxed);
    g.lufsMomentary.set(kSilenceDb);
    g.lufsShortTerm.set(kSilenceDb);
    g.loudnessBlocks.set(0);
}

// --- audio thread ---------------------------------------------------------------------------

void analyzeInput(const float* in, int frames) {
    if (!in || frames <= 0) return;
    double sl = 0, sr = 0;
    float pl = 0, pr = 0;
    for (int i = 0; i < frames; ++i) {
        const float l = in[2 * i], r = in[2 * i + 1];
        if (!std::isfinite(l) || !std::isfinite(r)) continue;
        sl += static_cast<double>(l) * l;
        sr += static_cast<double>(r) * r;
        const float al = std::fabs(l), ar = std::fabs(r);
        if (al > pl) pl = al;
        if (ar > pr) pr = ar;
    }
    const double n = static_cast<double>(frames);
    g.inRmsL.set(std::sqrt(sl / n));
    g.inRmsR.set(std::sqrt(sr / n));
    g.inPeakL.set(pl);
    g.inPeakR.set(pr);
}

void recordCallback(int hostFrames) {
    g.callbackCount.add();
    g.hostBlockFrames.store(hostFrames, std::memory_order_relaxed);
}

void recordBlock(int64_t startNs, int64_t endNs, int frames) {
    const int64_t dur = endNs - startNs;
    if (dur < 0 || frames <= 0) return;
    const double d = static_cast<double>(dur);
    const int sr = g.sampleRate.load(std::memory_order_relaxed);
    const double budgetNs = (static_cast<double>(frames) / (sr > 0 ? sr : 48000)) * 1e9;
    const double ratio = budgetNs > 0 ? d / budgetNs : 0.0;

    const uint64_t n = g.blockCount.get();
    g.procLastNs.set(d);
    g.rtRatioLast.set(ratio);
    if (n == 0 || d < g.procMinNs.get()) g.procMinNs.set(d);
    if (d > g.procMaxNs.get()) g.procMaxNs.set(d);
    g.procSumNs.store(g.procSumNs.load(std::memory_order_relaxed) + d, std::memory_order_relaxed);
    g.procSumSqNs.store(g.procSumSqNs.load(std::memory_order_relaxed) + d * d, std::memory_order_relaxed);
    g.rtRatioSum.store(g.rtRatioSum.load(std::memory_order_relaxed) + ratio, std::memory_order_relaxed);
    g.blockCount.add();
    g.framesProcessed.add(static_cast<uint64_t>(frames));
    if (d > budgetNs) g.deadlineMisses.add();
    g.lastBlockNs.set(static_cast<double>(endNs));

    if (g.benchActive.load(std::memory_order_relaxed)) {
        if (endNs >= g.benchEndNs.load(std::memory_order_relaxed)) {
            g.benchActive.store(false, std::memory_order_relaxed);
            g.benchFinished.store(true, std::memory_order_relaxed);
        } else {
            const uint64_t bn = g.benchBlocks.get();
            if (bn == 0 || d < g.benchMinNs.get()) g.benchMinNs.set(d);
            if (d > g.benchMaxNs.get()) g.benchMaxNs.set(d);
            g.benchSumNs.store(g.benchSumNs.load(std::memory_order_relaxed) + d, std::memory_order_relaxed);
            g.benchSumSqNs.store(g.benchSumSqNs.load(std::memory_order_relaxed) + d * d, std::memory_order_relaxed);
            g.benchRtSum.store(g.benchRtSum.load(std::memory_order_relaxed) + ratio, std::memory_order_relaxed);
            g.benchBlocks.add();
            if (d > budgetNs) g.benchDeadlineMisses.add();
            int bin = static_cast<int>(dur / kBenchBinNs);
            if (bin < 0) bin = 0;
            if (bin >= kBenchBins) bin = kBenchBins - 1;
            g.benchHist[bin].fetch_add(1, std::memory_order_relaxed);
        }
    }
}

void analyzeOutputAndSanitize(float* out, int frames) {
    if (!out || frames <= 0) return;

    double sl = 0, sr = 0, sm = 0, ss = 0, slr = 0;
    float pl = 0, pr = 0;
    uint32_t clipped = 0, nans = 0, infs = 0;
    double kSum = 0;   // K-weighted energy accumulated into the current sub-block

    uint32_t w = g.ringWrite.load(std::memory_order_relaxed);
    const int subSize = g.subSize;

    for (int i = 0; i < frames; ++i) {
        float l = out[2 * i], r = out[2 * i + 1];
        if (l != l) { l = 0.f; ++nans; }
        else if (!std::isfinite(l)) { l = 0.f; ++infs; }
        if (r != r) { r = 0.f; ++nans; }
        else if (!std::isfinite(r)) { r = 0.f; ++infs; }

        if (l > 1.f) { l = 1.f; ++clipped; }
        else if (l < -1.f) { l = -1.f; ++clipped; }
        if (r > 1.f) { r = 1.f; ++clipped; }
        else if (r < -1.f) { r = -1.f; ++clipped; }

        out[2 * i] = l;
        out[2 * i + 1] = r;

        const double dl = l, dr = r;
        sl += dl * dl;
        sr += dr * dr;
        const double mid = 0.5 * (dl + dr), side = 0.5 * (dl - dr);
        sm += mid * mid;
        ss += side * side;
        slr += dl * dr;
        const float al = std::fabs(l), ar = std::fabs(r);
        if (al > pl) pl = al;
        if (ar > pr) pr = ar;

        g.ringL[w & kRingMask].store(l, std::memory_order_relaxed);
        g.ringR[w & kRingMask].store(r, std::memory_order_relaxed);
        ++w;

        // K-weighting for BS.1770 loudness.
        const double yl = g.kHpL.process(g.kShelfL.process(dl));
        const double yr = g.kHpR.process(g.kShelfR.process(dr));
        g.subSumL += yl * yl;
        g.subSumR += yr * yr;
        if (++g.subFill >= subSize) {
            const double z = (g.subSumL + g.subSumR) / static_cast<double>(subSize);
            const uint32_t sw = g.subWrite.load(std::memory_order_relaxed);
            g.subZ[sw % kSubRing].store(static_cast<float>(z), std::memory_order_relaxed);
            g.subWrite.store(sw + 1, std::memory_order_relaxed);
            g.subSumL = g.subSumR = 0;
            g.subFill = 0;
            kSum += 1;   // marks "a sub-block closed in this audio block"

            const uint32_t total = sw + 1;
            // Momentary: mean of the last 4 sub-blocks (400 ms).
            if (total >= kMomentarySubBlocks) {
                double acc = 0;
                for (int k = 0; k < kMomentarySubBlocks; ++k) {
                    acc += g.subZ[(total - 1 - k) % kSubRing].load(std::memory_order_relaxed);
                }
                const double mean = acc / kMomentarySubBlocks;
                const double lufs = mean > 1e-12 ? -0.691 + 10.0 * std::log10(mean) : kSilenceDb;
                g.lufsMomentary.set(lufs);
                const uint32_t mc = g.momCount.load(std::memory_order_relaxed);
                if (mc < kMaxMomentaryHistory) {
                    g.momHistory[mc].store(static_cast<float>(lufs), std::memory_order_relaxed);
                    g.momCount.store(mc + 1, std::memory_order_relaxed);
                }
                g.loudnessBlocks.add();
            }
            // Short term: mean of the last 30 sub-blocks (3 s), sampled once a second.
            if (total >= kShortTermSubBlocks) {
                double acc = 0;
                for (int k = 0; k < kShortTermSubBlocks; ++k) {
                    acc += g.subZ[(total - 1 - k) % kSubRing].load(std::memory_order_relaxed);
                }
                const double mean = acc / kShortTermSubBlocks;
                const double lufs = mean > 1e-12 ? -0.691 + 10.0 * std::log10(mean) : kSilenceDb;
                g.lufsShortTerm.set(lufs);
                if (total % 10 == 0) {
                    const uint32_t sc = g.stCount.load(std::memory_order_relaxed);
                    if (sc < kMaxShortHistory) {
                        g.stHistory[sc].store(static_cast<float>(lufs), std::memory_order_relaxed);
                        g.stCount.store(sc + 1, std::memory_order_relaxed);
                    }
                }
            }
        }
    }
    (void) kSum;
    g.ringWrite.store(w, std::memory_order_relaxed);

    const double n = static_cast<double>(frames);
    const double rmsL = std::sqrt(sl / n), rmsR = std::sqrt(sr / n);
    const double rmsM = std::sqrt(sm / n), rmsS = std::sqrt(ss / n);
    g.outRmsL.set(rmsL);
    g.outRmsR.set(rmsR);
    g.outPeakL.set(pl);
    g.outPeakR.set(pr);
    g.midRms.set(rmsM);
    g.sideRms.set(rmsS);

    // Peak hold with a per-block decay (no wall-clock reads on the audio thread).
    const double hl = std::max(static_cast<double>(pl), g.holdL.get() * g.holdDecayPerBlock);
    const double hr = std::max(static_cast<double>(pr), g.holdR.get() * g.holdDecayPerBlock);
    g.holdL.set(hl);
    g.holdR.set(hr);

    // Pearson correlation of L and R (zero mean assumed, true for audio).
    const double denom = std::sqrt(sl * sr);
    g.correlation.set(denom > 1e-12 ? std::max(-1.0, std::min(1.0, slr / denom)) : 0.0);

    const double sum = rmsL + rmsR;
    g.balance.set(sum > 1e-9 ? (rmsR - rmsL) / sum : 0.0);
    // Width: side/mid energy ratio, normalised so a hard-panned/out-of-phase pair reads ~2.
    g.width.set(rmsM > 1e-9 ? std::min(2.0, 2.0 * rmsS / (rmsM + rmsS)) : (rmsS > 1e-9 ? 2.0 : 0.0));
    // Mono risk: fraction of energy that disappears when L+R are summed.
    const double stereoE = sl + sr;
    const double monoE = 2.0 * sm;
    g.monoRisk.set(stereoE > 1e-12 ? std::max(0.0, std::min(1.0, 1.0 - monoE / stereoE)) : 0.0);

    if (clipped) g.clipCount.add(clipped);
    if (nans) g.nanCount.add(nans);
    if (infs) g.infCount.add(infs);
}

void noteUnderrun() { g.underruns.add(); }
uint32_t fifoUnderflows() { return static_cast<uint32_t>(g.underruns.get()); }

// --- analyzer thread ---------------------------------------------------------------------

namespace {

// Gated integrated loudness, BS.1770-4: absolute gate at -70 LUFS, then a relative gate
// 10 LU below the ungated mean of the surviving blocks.
double integratedLoudness() {
    const uint32_t n = g.momCount.load(std::memory_order_relaxed);
    if (n == 0) return kSilenceDb;
    double sum = 0;
    uint32_t count = 0;
    for (uint32_t i = 0; i < n; ++i) {
        const double l = g.momHistory[i].load(std::memory_order_relaxed);
        if (l <= -70.0) continue;
        sum += std::pow(10.0, (l + 0.691) / 10.0);
        ++count;
    }
    if (count == 0) return kSilenceDb;
    const double ungated = -0.691 + 10.0 * std::log10(sum / count);
    const double gate = ungated - 10.0;
    double sum2 = 0;
    uint32_t count2 = 0;
    for (uint32_t i = 0; i < n; ++i) {
        const double l = g.momHistory[i].load(std::memory_order_relaxed);
        if (l <= -70.0 || l <= gate) continue;
        sum2 += std::pow(10.0, (l + 0.691) / 10.0);
        ++count2;
    }
    if (count2 == 0) return ungated;
    return -0.691 + 10.0 * std::log10(sum2 / count2);
}

// EBU R128 loudness range from the 3 s short-term history (10th..95th percentile of the
// blocks above the -20 LU relative gate). Needs at least 60 s of history to mean anything.
double loudnessRange(std::vector<float>& scratch) {
    const uint32_t n = g.stCount.load(std::memory_order_relaxed);
    if (n < 60) return kSilenceDb;
    scratch.clear();
    scratch.reserve(n);
    double sum = 0;
    uint32_t c = 0;
    for (uint32_t i = 0; i < n; ++i) {
        const float l = g.stHistory[i].load(std::memory_order_relaxed);
        if (l <= -70.f) continue;
        sum += std::pow(10.0, (l + 0.691) / 10.0);
        ++c;
    }
    if (c == 0) return kSilenceDb;
    const double gate = (-0.691 + 10.0 * std::log10(sum / c)) - 20.0;
    for (uint32_t i = 0; i < n; ++i) {
        const float l = g.stHistory[i].load(std::memory_order_relaxed);
        if (l > -70.f && l > gate) scratch.push_back(l);
    }
    if (scratch.size() < 2) return kSilenceDb;
    std::sort(scratch.begin(), scratch.end());
    auto pct = [&](double p) {
        const double idx = p * (scratch.size() - 1);
        const size_t i0 = static_cast<size_t>(idx);
        const size_t i1 = std::min(i0 + 1, scratch.size() - 1);
        const double f = idx - i0;
        return scratch[i0] * (1 - f) + scratch[i1] * f;
    };
    return pct(0.95) - pct(0.10);
}

double percentileFromHist(double p, uint64_t total) {
    if (total == 0) return 0;
    const uint64_t target = static_cast<uint64_t>(p * total);
    uint64_t acc = 0;
    for (int i = 0; i < kBenchBins; ++i) {
        acc += g.benchHist[i].load(std::memory_order_relaxed);
        if (acc >= target) return (i + 0.5) * kBenchBinNs;
    }
    return static_cast<double>(kBenchBins) * kBenchBinNs;
}

void finishBenchmark() {
    const uint64_t n = g.benchBlocks.get();
    if (n == 0) return;
    const double sum = g.benchSumNs.load(std::memory_order_relaxed);
    const double sumSq = g.benchSumSqNs.load(std::memory_order_relaxed);
    const double mean = sum / n;
    const double var = std::max(0.0, sumSq / n - mean * mean);
    g.benchAvgNs.set(mean);
    g.benchStdDev.set(std::sqrt(var));
    g.benchRtRatio.set(g.benchRtSum.load(std::memory_order_relaxed) / n);
    g.benchP50.set(percentileFromHist(0.50, n));
    g.benchP95.set(percentileFromHist(0.95, n));
    g.benchP99.set(percentileFromHist(0.99, n));
}

}  // namespace

int snapshot(double* out, int cap) {
    if (!out || cap < S_SLOT_COUNT) return 0;
    static std::vector<float> lraScratch;

    if (g.benchFinished.exchange(false, std::memory_order_relaxed)) finishBenchmark();
    if (g.benchActive.load(std::memory_order_relaxed)) finishBenchmark();

    const uint64_t blocks = g.blockCount.get();
    const double sum = g.procSumNs.load(std::memory_order_relaxed);
    const double sumSq = g.procSumSqNs.load(std::memory_order_relaxed);
    const double avg = blocks ? sum / blocks : 0.0;
    const double var = blocks ? std::max(0.0, sumSq / blocks - avg * avg) : 0.0;

    out[S_SAMPLE_RATE] = g.sampleRate.load(std::memory_order_relaxed);
    out[S_FRAMES_PROCESSED] = static_cast<double>(g.framesProcessed.get());
    out[S_CALLBACK_COUNT] = static_cast<double>(g.callbackCount.get());
    out[S_BLOCK_COUNT] = static_cast<double>(blocks);
    out[S_PROC_LAST_NS] = g.procLastNs.get();
    out[S_PROC_AVG_NS] = avg;
    out[S_PROC_MIN_NS] = blocks ? g.procMinNs.get() : 0.0;
    out[S_PROC_MAX_NS] = g.procMaxNs.get();
    out[S_PROC_STDDEV_NS] = std::sqrt(var);
    out[S_RT_RATIO_AVG] = blocks ? g.rtRatioSum.load(std::memory_order_relaxed) / blocks : 0.0;
    out[S_RT_RATIO_LAST] = g.rtRatioLast.get();
    out[S_DEADLINE_MISSES] = static_cast<double>(g.deadlineMisses.get());
    out[S_QUANTUM] = g.quantum.load(std::memory_order_relaxed);
    out[S_HOST_BLOCK_FRAMES] = g.hostBlockFrames.load(std::memory_order_relaxed);
    out[S_LATENCY_FRAMES] = g.latencyFrames.load(std::memory_order_relaxed);

    out[S_IN_RMS_L] = g.inRmsL.get();
    out[S_IN_RMS_R] = g.inRmsR.get();
    out[S_IN_PEAK_L] = g.inPeakL.get();
    out[S_IN_PEAK_R] = g.inPeakR.get();
    out[S_OUT_RMS_L] = g.outRmsL.get();
    out[S_OUT_RMS_R] = g.outRmsR.get();
    out[S_OUT_PEAK_L] = g.outPeakL.get();
    out[S_OUT_PEAK_R] = g.outPeakR.get();
    out[S_OUT_HOLD_L] = g.holdL.get();
    out[S_OUT_HOLD_R] = g.holdR.get();
    out[S_TRUE_PEAK_L_DB] = g.truePeakLDb.get();
    out[S_TRUE_PEAK_R_DB] = g.truePeakRDb.get();
    out[S_TRUE_PEAK_HOLD_DB] = g.truePeakHoldDb.get();
    out[S_CLIP_COUNT] = static_cast<double>(g.clipCount.get());
    const double peak = std::max(g.outPeakL.get(), g.outPeakR.get());
    out[S_HEADROOM_DB] = peak > 1e-9 ? -toDb(peak) : 200.0;

    out[S_CORRELATION] = g.correlation.get();
    out[S_BALANCE] = g.balance.get();
    out[S_WIDTH] = g.width.get();
    out[S_MID_RMS] = g.midRms.get();
    out[S_SIDE_RMS] = g.sideRms.get();
    out[S_MONO_RISK] = g.monoRisk.get();

    out[S_LUFS_MOMENTARY] = g.lufsMomentary.get();
    out[S_LUFS_SHORT_TERM] = g.lufsShortTerm.get();
    out[S_LUFS_INTEGRATED] = integratedLoudness();
    out[S_LRA] = loudnessRange(lraScratch);
    out[S_LOUDNESS_BLOCKS] = static_cast<double>(g.loudnessBlocks.get());

    out[S_NAN_COUNT] = static_cast<double>(g.nanCount.get());
    out[S_INF_COUNT] = static_cast<double>(g.infCount.get());
    out[S_ENGINE_ACTIVE] = g.engineActive.load(std::memory_order_relaxed) ? 1 : 0;
    out[S_BYPASS] = g.bypass.load(std::memory_order_relaxed) ? 1 : 0;
    const double last = g.lastBlockNs.get();
    out[S_AGE_NS] = last > 0 ? static_cast<double>(nowNs()) - last : 1e18;

    out[S_PEAK_FREQ_HZ] = g.peakFreqHz.get();
    out[S_PEAK_MAG_DB] = g.peakMagDb.get();
    out[S_SPECTRAL_RMS_DB] = g.spectralRmsDb.get();
    out[S_SPECTRAL_CENTROID_HZ] = g.spectralCentroidHz.get();

    out[S_BENCH_ACTIVE] = g.benchActive.load(std::memory_order_relaxed) ? 1 : 0;
    out[S_BENCH_BLOCKS] = static_cast<double>(g.benchBlocks.get());
    out[S_BENCH_AVG_NS] = g.benchAvgNs.get();
    out[S_BENCH_MIN_NS] = g.benchBlocks.get() ? g.benchMinNs.get() : 0.0;
    out[S_BENCH_MAX_NS] = g.benchMaxNs.get();
    out[S_BENCH_P50_NS] = g.benchP50.get();
    out[S_BENCH_P95_NS] = g.benchP95.get();
    out[S_BENCH_P99_NS] = g.benchP99.get();
    out[S_BENCH_STDDEV_NS] = g.benchStdDev.get();
    out[S_BENCH_RT_RATIO] = g.benchRtRatio.get();
    out[S_BENCH_DEADLINE_MISSES] = static_cast<double>(g.benchDeadlineMisses.get());

    out[S_FIFO_UNDERFLOWS] = static_cast<double>(g.underruns.get());
    out[S_RESET_COUNT] = static_cast<double>(g.resetCount.get());
    return S_SLOT_COUNT;
}

// --- FFT (analyzer thread only) -------------------------------------------------------------

namespace {

struct FftPlan {
    int n = 0;
    std::vector<float> cosT, sinT, window;
    std::vector<int> rev;
    std::vector<float> re, im, mono;

    void build(int size) {
        n = size;
        cosT.resize(n / 2);
        sinT.resize(n / 2);
        window.resize(n);
        rev.resize(n);
        re.resize(n);
        im.resize(n);
        mono.resize(n);
        for (int i = 0; i < n / 2; ++i) {
            cosT[i] = std::cos(-2.0 * M_PI * i / n);
            sinT[i] = std::sin(-2.0 * M_PI * i / n);
        }
        for (int i = 0; i < n; ++i) window[i] = 0.5f - 0.5f * std::cos(2.0 * M_PI * i / (n - 1));
        int bits = 0;
        while ((1 << bits) < n) ++bits;
        for (int i = 0; i < n; ++i) {
            int x = i, r = 0;
            for (int b = 0; b < bits; ++b) { r = (r << 1) | (x & 1); x >>= 1; }
            rev[i] = r;
        }
    }

    void run() {
        for (int i = 0; i < n; ++i) {
            const int j = rev[i];
            if (j > i) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            const int half = len >> 1;
            const int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; ++k) {
                    const float c = cosT[k * step], s = sinT[k * step];
                    const int a = i + k, b = i + k + half;
                    const float tr = re[b] * c - im[b] * s;
                    const float ti = re[b] * s + im[b] * c;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr;        im[a] += ti;
                }
            }
        }
    }
};

FftPlan g_plan;

// 4x oversampling true-peak estimate (ITU-R BS.1770-4 Annex 2 style): windowed-sinc polyphase
// interpolation of the last `count` frames. Analyzer thread only.
void measureTruePeak(int count) {
    constexpr int kTaps = 24;      // per phase
    constexpr int kPhases = 4;
    static std::vector<float> coeff;
    if (coeff.empty()) {
        coeff.resize(kTaps * kPhases);
        for (int p = 0; p < kPhases; ++p) {
            for (int t = 0; t < kTaps; ++t) {
                const double x = (t - (kTaps / 2 - 1)) - p / static_cast<double>(kPhases);
                double s = (std::fabs(x) < 1e-9) ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
                const double w = 0.54 - 0.46 * std::cos(2.0 * M_PI * (t + 0.5) / kTaps);  // Hamming
                coeff[p * kTaps + t] = static_cast<float>(s * w);
            }
        }
    }
    const uint32_t w = g.ringWrite.load(std::memory_order_relaxed);
    if (w < static_cast<uint32_t>(count + kTaps)) return;
    float peakL = 0, peakR = 0;
    const uint32_t start = w - count;
    for (int i = 0; i < count; ++i) {
        for (int p = 0; p < kPhases; ++p) {
            float accL = 0, accR = 0;
            for (int t = 0; t < kTaps; ++t) {
                const uint32_t idx = (start + i + t) & kRingMask;
                const float c = coeff[p * kTaps + t];
                accL += g.ringL[idx].load(std::memory_order_relaxed) * c;
                accR += g.ringR[idx].load(std::memory_order_relaxed) * c;
            }
            peakL = std::max(peakL, std::fabs(accL));
            peakR = std::max(peakR, std::fabs(accR));
        }
    }
    const double dl = toDb(peakL), dr = toDb(peakR);
    g.truePeakLDb.set(dl);
    g.truePeakRDb.set(dr);
    g.truePeakHoldDb.set(std::max(g.truePeakHoldDb.get(), std::max(dl, dr)));
}

}  // namespace

int spectrum(float* outDb, int cap, int fftSize) {
    if (!outDb || fftSize < 256 || fftSize > 8192 || (fftSize & (fftSize - 1)) != 0) return 0;
    const int bins = fftSize / 2;
    if (cap < bins) return 0;

    std::lock_guard<std::mutex> lock(g_fftMutex);   // analyzer threads only, never audio
    if (g_plan.n != fftSize) g_plan.build(fftSize);

    const uint32_t w = g.ringWrite.load(std::memory_order_relaxed);
    if (w < static_cast<uint32_t>(fftSize)) {
        std::fill(outDb, outDb + bins, static_cast<float>(kSilenceDb));
        return bins;
    }
    const uint32_t start = w - fftSize;
    double energy = 0;
    for (int i = 0; i < fftSize; ++i) {
        const uint32_t idx = (start + i) & kRingMask;
        const float m = 0.5f * (g.ringL[idx].load(std::memory_order_relaxed) +
                                g.ringR[idx].load(std::memory_order_relaxed));
        energy += static_cast<double>(m) * m;
        g_plan.re[i] = m * g_plan.window[i];
        g_plan.im[i] = 0.f;
    }
    g_plan.run();

    const int sr = g.sampleRate.load(std::memory_order_relaxed);
    const double norm = 2.0 / (fftSize * 0.5);   // Hann coherent gain = 0.5
    double bestMag = 0;
    int bestBin = 0;
    double cSum = 0, cW = 0;
    for (int i = 0; i < bins; ++i) {
        const double mag = std::sqrt(static_cast<double>(g_plan.re[i]) * g_plan.re[i] +
                                     static_cast<double>(g_plan.im[i]) * g_plan.im[i]) * norm;
        if (mag > bestMag) { bestMag = mag; bestBin = i; }
        const double f = static_cast<double>(i) * sr / fftSize;
        cSum += mag * f;
        cW += mag;
        outDb[i] = static_cast<float>(toDb(mag));
    }

    // Parabolic interpolation around the peak bin for a sub-bin frequency estimate.
    double peakBin = bestBin;
    if (bestBin > 0 && bestBin < bins - 1) {
        const double a = outDb[bestBin - 1], b = outDb[bestBin], c = outDb[bestBin + 1];
        const double d = (a - c) / (2.0 * (a - 2.0 * b + c));
        if (std::isfinite(d) && std::fabs(d) <= 1.0) peakBin += d;
    }
    g.peakFreqHz.set(peakBin * sr / fftSize);
    g.peakMagDb.set(toDb(bestMag));
    g.spectralRmsDb.set(toDb(std::sqrt(energy / fftSize)));
    g.spectralCentroidHz.set(cW > 1e-12 ? cSum / cW : 0.0);

    measureTruePeak(std::min(fftSize, 2048));
    return bins;
}

// --- benchmark ------------------------------------------------------------------------------

void benchmarkStart(int64_t durationMs) {
    g.benchActive.store(false, std::memory_order_relaxed);
    g.benchBlocks.set(0);
    g.benchDeadlineMisses.set(0);
    g.benchMinNs.set(0);
    g.benchMaxNs.set(0);
    g.benchSumNs.store(0.0, std::memory_order_relaxed);
    g.benchSumSqNs.store(0.0, std::memory_order_relaxed);
    g.benchRtSum.store(0.0, std::memory_order_relaxed);
    for (int i = 0; i < kBenchBins; ++i) g.benchHist[i].store(0, std::memory_order_relaxed);
    g.benchAvgNs.set(0); g.benchP50.set(0); g.benchP95.set(0); g.benchP99.set(0);
    g.benchStdDev.set(0); g.benchRtRatio.set(0);
    g.benchFinished.store(false, std::memory_order_relaxed);
    g.benchEndNs.store(nowNs() + durationMs * 1000000LL, std::memory_order_relaxed);
    g.benchActive.store(true, std::memory_order_release);
}

void benchmarkStop() {
    g.benchActive.store(false, std::memory_order_relaxed);
    finishBenchmark();
}

bool benchmarkRunning() { return g.benchActive.load(std::memory_order_relaxed); }

}  // namespace telemetry
