// Reference engine for Resonance: tone shelves, stereo width, headphone crossfeed,
// a small reverb, output gain and a look-ahead limiter. Pure C++17, no dependencies.
//
// Signal chain: bass/treble shelf -> width (mid/side) -> crossfeed -> reverb send
//               -> output gain -> look-ahead limiter

#include "ae_plugin.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <complex>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;

inline float dbToLin(float db) { return std::pow(10.0f, db / 20.0f); }

// RBJ shelving filter, transposed direct form II, two channels of state.
struct Shelf {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};

    void set(float fs, float f0, float gainDb, bool high) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = 2.0f * kPi * f0 / fs;
        const float cw = std::cos(w0), sw = std::sin(w0);
        const float alpha = sw / std::sqrt(2.0f);  // shelf slope S = 1
        const float sq = 2.0f * std::sqrt(A) * alpha;
        float nb0, nb1, nb2, na0, na1, na2;
        if (!high) {
            nb0 = A * ((A + 1) - (A - 1) * cw + sq);
            nb1 = 2 * A * ((A - 1) - (A + 1) * cw);
            nb2 = A * ((A + 1) - (A - 1) * cw - sq);
            na0 = (A + 1) + (A - 1) * cw + sq;
            na1 = -2 * ((A - 1) + (A + 1) * cw);
            na2 = (A + 1) + (A - 1) * cw - sq;
        } else {
            nb0 = A * ((A + 1) + (A - 1) * cw + sq);
            nb1 = -2 * A * ((A - 1) + (A + 1) * cw);
            nb2 = A * ((A + 1) + (A - 1) * cw - sq);
            na0 = (A + 1) - (A - 1) * cw + sq;
            na1 = 2 * ((A - 1) - (A + 1) * cw);
            na2 = (A + 1) - (A - 1) * cw - sq;
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0;
        a1 = na1 / na0; a2 = na2 / na0;
    }
    inline float run(int c, float x) {
        const float y = b0 * x + z1[c];
        z1[c] = b1 * x - a1 * y + z2[c];
        z2[c] = b2 * x - a2 * y;
        return y;
    }
    void clear() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
};

struct Comb {
    std::vector<float> buf;
    size_t idx = 0;
    float store = 0;
    void init(size_t n) { buf.assign(std::max<size_t>(n, 1), 0.0f); idx = 0; store = 0; }
    inline float run(float in, float fb, float damp) {
        const float out = buf[idx];
        store = out * (1.0f - damp) + store * damp;
        if (std::fabs(store) < 1e-20f) store = 0;
        buf[idx] = in + store * fb;
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
    void clear() { std::fill(buf.begin(), buf.end(), 0.0f); store = 0; idx = 0; }
};

struct Allpass {
    std::vector<float> buf;
    size_t idx = 0;
    void init(size_t n) { buf.assign(std::max<size_t>(n, 1), 0.0f); idx = 0; }
    inline float run(float in) {
        const float bo = buf[idx];
        const float out = -in + bo;
        buf[idx] = in + bo * 0.5f;
        if (++idx >= buf.size()) idx = 0;
        return out;
    }
    void clear() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; }
};

// Uniform partitioned convolution. All storage is allocated during init(); processSample()
// is allocation-free and accepts arbitrary host callback sizes through its sample interface.
struct PartitionedConvolver {
    static constexpr int kBlock = 256;
    static constexpr int kFft = kBlock * 2;
    using C = std::complex<float>;

    int partitions = 0;
    int inputPos = 0;
    int outputPos = 0;
    int historyPos = 0;
    std::vector<C> irFreq;
    std::vector<C> history;
    std::vector<C> work;
    std::vector<C> accum;
    std::vector<float> input;
    std::vector<float> output;
    std::vector<float> overlap;
    std::vector<C> twiddle;

    static int reverseBits(int x, int bits) {
        int y = 0;
        for (int i = 0; i < bits; ++i) { y = (y << 1) | (x & 1); x >>= 1; }
        return y;
    }

    void fft(C* data, bool inverse) {
        constexpr int bits = 9; // log2(kFft)
        for (int i = 0; i < kFft; ++i) {
            const int j = reverseBits(i, bits);
            if (j > i) std::swap(data[i], data[j]);
        }
        for (int len = 2; len <= kFft; len <<= 1) {
            const int step = kFft / len;
            for (int base = 0; base < kFft; base += len) {
                for (int j = 0; j < len / 2; ++j) {
                    const C w = inverse ? std::conj(twiddle[j * step]) : twiddle[j * step];
                    const C u = data[base + j];
                    const C v = data[base + j + len / 2] * w;
                    data[base + j] = u + v;
                    data[base + j + len / 2] = u - v;
                }
            }
        }
        if (inverse) for (int i = 0; i < kFft; ++i) data[i] /= static_cast<float>(kFft);
    }

    void init(float fs) {
        const int irLength = std::max(kBlock, std::min(4096, static_cast<int>(std::lround(fs * 0.085f))));
        partitions = (irLength + kBlock - 1) / kBlock;
        irFreq.assign(static_cast<size_t>(partitions) * kFft, C(0, 0));
        history.assign(static_cast<size_t>(partitions) * kFft, C(0, 0));
        work.assign(kFft, C(0, 0));
        accum.assign(kFft, C(0, 0));
        input.assign(kBlock, 0.0f);
        output.assign(kBlock, 0.0f);
        overlap.assign(kBlock, 0.0f);
        twiddle.resize(kFft / 2);
        for (int k = 0; k < kFft / 2; ++k) {
            const float phase = -2.0f * kPi * static_cast<float>(k) / static_cast<float>(kFft);
            twiddle[k] = C(std::cos(phase), std::sin(phase));
        }

        // Built-in short room IR: direct path plus deterministic early reflections and a
        // quiet decaying tail. It is deliberately conservative; mix defaults to zero.
        std::vector<float> ir(static_cast<size_t>(irLength), 0.0f);
        ir[0] = 1.0f;
        const int taps[] = { 37, 83, 151, 239, 331, 487, 701, 997, 1433, 2011, 2789, 3617 };
        const float gains[] = { .28f, -.20f, .16f, .12f, -.10f, .075f, .06f, -.045f, .035f, .025f, -.018f, .012f };
        for (size_t i = 0; i < std::size(taps); ++i) if (taps[i] < irLength) ir[taps[i]] = gains[i];
        for (int i = 1; i < irLength; ++i) {
            const float t = static_cast<float>(i) / static_cast<float>(fs);
            ir[i] += 0.012f * std::exp(-22.0f * t) * std::sin(2.0f * kPi * 137.0f * t);
        }
        for (int p = 0; p < partitions; ++p) {
            std::fill(work.begin(), work.end(), C(0, 0));
            for (int i = 0; i < kBlock; ++i) {
                const int n = p * kBlock + i;
                if (n < irLength) work[i] = C(ir[n], 0);
            }
            fft(work.data(), false);
            std::copy(work.begin(), work.end(), irFreq.begin() + static_cast<size_t>(p) * kFft);
        }
        reset();
    }

    void reset() {
        inputPos = outputPos = historyPos = 0;
        std::fill(input.begin(), input.end(), 0.0f);
        std::fill(output.begin(), output.end(), 0.0f);
        std::fill(overlap.begin(), overlap.end(), 0.0f);
        std::fill(history.begin(), history.end(), C(0, 0));
    }

    void processBlock() {
        std::fill(work.begin(), work.end(), C(0, 0));
        for (int i = 0; i < kBlock; ++i) work[i] = C(input[i], 0);
        fft(work.data(), false);
        const size_t current = static_cast<size_t>(historyPos) * kFft;
        std::copy(work.begin(), work.end(), history.begin() + current);
        std::fill(accum.begin(), accum.end(), C(0, 0));
        for (int p = 0; p < partitions; ++p) {
            int h = historyPos - p;
            if (h < 0) h += partitions;
            const C* x = history.data() + static_cast<size_t>(h) * kFft;
            const C* ir = irFreq.data() + static_cast<size_t>(p) * kFft;
            for (int k = 0; k < kFft; ++k) accum[k] += x[k] * ir[k];
        }
        fft(accum.data(), true);
        for (int i = 0; i < kBlock; ++i) output[i] = accum[i].real() + overlap[i];
        for (int i = 0; i < kBlock; ++i) overlap[i] = accum[i + kBlock].real();
        historyPos = (historyPos + 1) % partitions;
        inputPos = 0;
    }

    float processSample(float sample) {
        const float out = output[outputPos++];
        input[inputPos++] = sample;
        if (inputPos == kBlock) { processBlock(); outputPos = 0; }
        return out;
    }
};

}  // namespace

struct AeEngine {
    float fs;

    // Targets (set from ae_set_param) and smoothed values.
    float tBass = 0, tTreble = 0, tWidth = 1, tCross = 0, tMix = 0, tRoom = 0.5f, tGainDb = 0;
    float tConvMix = 0, tConvPre = 0, tConvDamp = 0;
    bool tLimiter = true;
    float cBass = 0, cTreble = 0, cWidth = 1, cCross = 0, cMix = 0, cRoom = 0.5f, cGain = 1;
    float cConvMix = 0, cConvPre = 0, cConvDamp = 0;
    float aBass = 0, aTreble = 0;  // gains the shelf coefficients were last computed for

    Shelf bass, treble;

    Comb combs[2][4];
    Allpass aps[2][2];
    PartitionedConvolver convolution[2];
    std::vector<float> convDelay[2];
    size_t convDelayIdx = 0;
    size_t convDelayLen = 1;

    // Crossfeed: low-passed, slightly delayed opposite channel.
    std::vector<float> xDelay[2];
    size_t xIdx = 0;
    float lp[2] = {0, 0};
    float lpCoef = 0.1f;

    // Look-ahead limiter.
    int D = 2;
    std::vector<float> dl[2], tg, mnb;
    size_t limIdx = 0;
    float sum = 0, limGain = 1, relCoef = 0.001f;
    static constexpr float kThreshold = 0.98f;

    explicit AeEngine(float sampleRate) : fs(sampleRate) {
        const float scale = fs / 44100.0f;
        static const int combLen[4] = {1116, 1188, 1277, 1356};
        static const int apLen[2] = {556, 441};
        for (int c = 0; c < 2; ++c) {
            const int spread = c * 23;
            for (int i = 0; i < 4; ++i) combs[c][i].init((size_t)((combLen[i] + spread) * scale));
            for (int i = 0; i < 2; ++i) aps[c][i].init((size_t)((apLen[i] + spread) * scale));
            convolution[c].init(fs);
        }
        convDelayLen = std::max<size_t>(1, static_cast<size_t>(std::lround(0.020f * fs)) + 1);
        convDelay[0].assign(convDelayLen, 0.0f);
        convDelay[1].assign(convDelayLen, 0.0f);
        const size_t xLen = std::max<size_t>(1, (size_t)std::lround(0.00025f * fs));
        xDelay[0].assign(xLen, 0.0f);
        xDelay[1].assign(xLen, 0.0f);
        lpCoef = 1.0f - std::exp(-2.0f * kPi * 700.0f / fs);

        D = std::max(2, (int)std::lround(0.0015f * fs));
        dl[0].assign(D, 0.0f);
        dl[1].assign(D, 0.0f);
        tg.assign(D, 1.0f);
        mnb.assign(D, 1.0f);
        sum = (float)D;
        relCoef = 1.0f - std::exp(-1.0f / (0.12f * fs));

        bass.set(fs, 120.0f, 0.0f, false);
        treble.set(fs, 8000.0f, 0.0f, true);
    }

    void reset() {
        bass.clear(); treble.clear();
        for (auto& c : combs) for (auto& x : c) x.clear();
        for (auto& c : aps) for (auto& x : c) x.clear();
        for (auto& c : convolution) c.reset();
        for (auto& d : convDelay) std::fill(d.begin(), d.end(), 0.0f);
        convDelayIdx = 0;
        for (auto& d : xDelay) std::fill(d.begin(), d.end(), 0.0f);
        lp[0] = lp[1] = 0; xIdx = 0;
        for (auto& d : dl) std::fill(d.begin(), d.end(), 0.0f);
        std::fill(tg.begin(), tg.end(), 1.0f);
        std::fill(mnb.begin(), mnb.end(), 1.0f);
        sum = (float)D; limGain = 1; limIdx = 0;
    }

    void process(float* buf, int frames) {
        // Block-rate smoothing for the shelves and reverb size.
        cBass += (tBass - cBass) * 0.4f;   if (std::fabs(tBass - cBass) < 0.005f) cBass = tBass;
        cTreble += (tTreble - cTreble) * 0.4f; if (std::fabs(tTreble - cTreble) < 0.005f) cTreble = tTreble;
        cRoom += (tRoom - cRoom) * 0.4f;   if (std::fabs(tRoom - cRoom) < 0.002f) cRoom = tRoom;
        cConvMix += (tConvMix - cConvMix) * 0.08f;
        cConvPre += (tConvPre - cConvPre) * 0.08f;
        cConvDamp += (tConvDamp - cConvDamp) * 0.08f;
        if (cBass != aBass) { bass.set(fs, 120.0f, cBass, false); aBass = cBass; }
        if (cTreble != aTreble) { treble.set(fs, 8000.0f, cTreble, true); aTreble = cTreble; }

        const float fb = 0.70f + 0.28f * cRoom;
        const float damp = 0.20f + 0.25f * cRoom;
        const float sm = 0.002f;  // per-sample one-pole for the remaining parameters
        const float gainTarget = dbToLin(tGainDb);
        const size_t xLen = xDelay[0].size();

        for (int i = 0; i < frames; ++i) {
            cWidth += (tWidth - cWidth) * sm;
            cCross += (tCross - cCross) * sm;
            cMix += (tMix - cMix) * sm;
            cGain += (gainTarget - cGain) * sm;

            float l = treble.run(0, bass.run(0, buf[2 * i]));
            float r = treble.run(1, bass.run(1, buf[2 * i + 1]));

            // Width (mid/side).
            const float m = 0.5f * (l + r);
            const float s = 0.5f * (l - r) * cWidth;
            l = m + s;
            r = m - s;

            // Crossfeed.
            const float xl = xDelay[0][xIdx], xr = xDelay[1][xIdx];
            lp[0] += lpCoef * (r - lp[0]);
            lp[1] += lpCoef * (l - lp[1]);
            xDelay[0][xIdx] = lp[0];
            xDelay[1][xIdx] = lp[1];
            if (++xIdx >= xLen) xIdx = 0;
            const float k = 0.5f * cCross;
            const float nl = l + k * xl;
            const float nr = r + k * xr;
            l = nl;
            r = nr;

            // Reverb send (dry level unchanged).
            const float in = (l + r) * 0.5f * 0.03f;
            float wl = 0, wr = 0;
            for (int c = 0; c < 4; ++c) {
                wl += combs[0][c].run(in, fb, damp);
                wr += combs[1][c].run(in, fb, damp);
            }
            for (int a = 0; a < 2; ++a) {
                wl = aps[0][a].run(wl);
                wr = aps[1][a].run(wr);
            }
            l += wl * 2.5f * cMix;
            r += wr * 2.5f * cMix;

            // Optional IR path. The convolver is always prepared, but with the default
            // zero mix the existing parametric path remains bit-for-bit unaffected apart
            // from the already-present limiter latency.
            if (cConvMix > 0.0001f) {
                const float convL = convolution[0].processSample(l);
                const float convR = convolution[1].processSample(r);
                const size_t delaySamples = std::min(
                    convDelayLen - 1,
                    static_cast<size_t>(std::lround(cConvPre * 0.020f * fs)));
                const size_t write = convDelayIdx;
                convDelay[0][write] = convL;
                convDelay[1][write] = convR;
                const size_t read = (write + convDelayLen - delaySamples) % convDelayLen;
                convDelayIdx = (convDelayIdx + 1) % convDelayLen;
                const float damp = 1.0f - 0.65f * cConvDamp;
                const float wetL = convDelay[0][read] * damp;
                const float wetR = convDelay[1][read] * damp;
                l += (wetL - l) * cConvMix;
                r += (wetR - r) * cConvMix;
            }

            l *= cGain;
            r *= cGain;

            // Look-ahead limiter (constant latency even when disabled).
            const float peak = std::max(std::fabs(l), std::fabs(r));
            const float tgt = (tLimiter && peak > kThreshold) ? kThreshold / peak : 1.0f;
            const size_t w = limIdx;
            tg[w] = tgt;
            float mn = 1.0f;
            for (int j = 0; j < D; ++j) mn = std::min(mn, tg[j]);
            sum += mn - mnb[w];
            mnb[w] = mn;
            const float g = sum / (float)D;
            const size_t oldest = (w + 1) % (size_t)D;
            const float outL = dl[0][oldest], outR = dl[1][oldest];
            dl[0][w] = l;
            dl[1][w] = r;
            limIdx = oldest;
            if (g < limGain) limGain = g; else limGain += (g - limGain) * relCoef;

            buf[2 * i] = outL * limGain;
            buf[2 * i + 1] = outR * limGain;
        }
    }
};

extern "C" {

AE_EXPORT int ae_abi_version(void) { return AE_ABI_VERSION; }

AE_EXPORT AeEngine* ae_create(int sample_rate, int channels) {
    if (channels != 2 || sample_rate < 8000 || sample_rate > 384000) return nullptr;
    return new AeEngine((float)sample_rate);
}

AE_EXPORT void ae_destroy(AeEngine* e) { delete e; }

AE_EXPORT void ae_set_param(AeEngine* e, const char* id, float v) {
    if (!e || !id || v != v) return;
    if (!std::strcmp(id, "bass")) e->tBass = std::clamp(v, -12.0f, 12.0f);
    else if (!std::strcmp(id, "treble")) e->tTreble = std::clamp(v, -12.0f, 12.0f);
    else if (!std::strcmp(id, "width")) e->tWidth = std::clamp(v, 0.0f, 2.0f);
    else if (!std::strcmp(id, "crossfeed")) e->tCross = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "reverb_mix")) e->tMix = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "reverb_room")) e->tRoom = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "convolution_mix")) e->tConvMix = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "convolution_predelay")) e->tConvPre = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "convolution_damping")) e->tConvDamp = std::clamp(v, 0.0f, 1.0f);
    else if (!std::strcmp(id, "gain")) e->tGainDb = std::clamp(v, -12.0f, 6.0f);
    else if (!std::strcmp(id, "limiter")) e->tLimiter = v >= 0.5f;
}

AE_EXPORT void ae_process(AeEngine* e, float* data, int frames) {
    if (e && data && frames > 0) e->process(data, frames);
}

AE_EXPORT void ae_reset(AeEngine* e) { if (e) e->reset(); }

}  // extern "C"
