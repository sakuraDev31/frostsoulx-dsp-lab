// Reference engine for Resonance: tone shelves, stereo width, headphone crossfeed,
// a small reverb, output gain and a look-ahead limiter. Pure C++17, no dependencies.
//
// Signal chain: bass/treble shelf -> width (mid/side) -> crossfeed -> reverb send
//               -> output gain -> look-ahead limiter

#include "ae_plugin.h"

#include <algorithm>
#include <cmath>
#include <cstring>
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

}  // namespace

struct AeEngine {
    float fs;

    // Targets (set from ae_set_param) and smoothed values.
    float tBass = 0, tTreble = 0, tWidth = 1, tCross = 0, tMix = 0, tRoom = 0.5f, tGainDb = 0;
    bool tLimiter = true;
    float cBass = 0, cTreble = 0, cWidth = 1, cCross = 0, cMix = 0, cRoom = 0.5f, cGain = 1;
    float aBass = 0, aTreble = 0;  // gains the shelf coefficients were last computed for

    Shelf bass, treble;

    Comb combs[2][4];
    Allpass aps[2][2];

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
        }
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
    else if (!std::strcmp(id, "gain")) e->tGainDb = std::clamp(v, -12.0f, 6.0f);
    else if (!std::strcmp(id, "limiter")) e->tLimiter = v >= 0.5f;
}

AE_EXPORT void ae_process(AeEngine* e, float* data, int frames) {
    if (e && data && frames > 0) e->process(data, frames);
}

AE_EXPORT void ae_reset(AeEngine* e) { if (e) e->reset(); }

}  // extern "C"
