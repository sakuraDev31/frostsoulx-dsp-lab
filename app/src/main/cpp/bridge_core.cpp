#include "bridge_core.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstring>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ResonanceBridge", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif

struct Bridge {
    std::vector<void*> libs;
    void* entry = nullptr;
    int (*abiVersion)() = nullptr;
    void* (*create)(int, int) = nullptr;
    void (*destroy)(void*) = nullptr;
    void (*setParam)(void*, const char*, float) = nullptr;
    void (*process)(void*, float*, int) = nullptr;
    void (*reset)(void*) = nullptr;
    void* inst = nullptr;

    // Internal DSP quantum re-blocking. All sizes are in stereo frames.
    int quantum = 0;          // 0 = follow host block size
    int maxHostFrames = 0;
    std::vector<float> inBuf;   // quantum frames, interleaved stereo
    int inFill = 0;
    std::vector<float> fifo;    // ring of processed frames
    int fifoCapacity = 0;       // frames
    int fifoRead = 0;
    int fifoWrite = 0;
    int fifoCount = 0;

    // Measured dropouts: frames the FIFO could not supply and had to zero-fill. Plain int,
    // only ever touched by the audio thread (the JNI layer reads it from the same thread,
    // immediately after processing), so no atomics and no synchronisation are needed.
    unsigned int fifoUnderflowFrames = 0;
    // ae_process() invocations during the current bridge_process_quantized() call.
    int invocations = 0;
};

template <typename F>
static bool loadSym(void* lib, const char* name, F& out) {
    void* p = dlsym(lib, name);
    out = reinterpret_cast<F>(p);
    return p != nullptr;
}

static std::string dlerr() {
    const char* e = dlerror();
    return e ? e : "unknown error";
}

void bridge_close(Bridge* b) {
    if (!b) return;
    if (b->inst && b->destroy) b->destroy(b->inst);
    if (b->entry) dlclose(b->entry);
    for (auto it = b->libs.rbegin(); it != b->libs.rend(); ++it) dlclose(*it);
    delete b;
}

Bridge* bridge_open(const std::string& entryPath, const std::vector<std::string>& preload,
                    int sampleRate, int channels, std::string& error) {
    auto* b = new Bridge();
    auto fail = [&](const std::string& msg) -> Bridge* {
        error = msg;
        LOGE("%s", msg.c_str());
        bridge_close(b);
        return nullptr;
    };

    for (const auto& p : preload) {
        void* h = dlopen(p.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!h) return fail("Could not load " + p + ": " + dlerr());
        b->libs.push_back(h);
    }

    b->entry = dlopen(entryPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!b->entry) return fail("Could not load " + entryPath + ": " + dlerr());

    if (!loadSym(b->entry, "ae_abi_version", b->abiVersion)) return fail("Engine is missing ae_abi_version");
    if (!loadSym(b->entry, "ae_create", b->create)) return fail("Engine is missing ae_create");
    if (!loadSym(b->entry, "ae_destroy", b->destroy)) return fail("Engine is missing ae_destroy");
    if (!loadSym(b->entry, "ae_set_param", b->setParam)) return fail("Engine is missing ae_set_param");
    if (!loadSym(b->entry, "ae_process", b->process)) return fail("Engine is missing ae_process");
    if (!loadSym(b->entry, "ae_reset", b->reset)) return fail("Engine is missing ae_reset");

    if (b->abiVersion() != 1) return fail("Unsupported engine ABI version " + std::to_string(b->abiVersion()));

    b->inst = b->create(sampleRate, channels);
    if (!b->inst) return fail("Engine refused " + std::to_string(sampleRate) + " Hz / " +
                              std::to_string(channels) + " channels");
    return b;
}

void bridge_set_param(Bridge* b, const char* id, float value) {
    if (b && b->inst && id) b->setParam(b->inst, id, value);
}

void bridge_process(Bridge* b, float* data, int frames) {
    if (b && b->inst && data && frames > 0) b->process(b->inst, data, frames);
}

void bridge_reset(Bridge* b) {
    if (b && b->inst) b->reset(b->inst);
    bridge_flush_fifo(b);
}

// --- quantum re-blocking -------------------------------------------------------------------

void bridge_flush_fifo(Bridge* b) {
    if (!b) return;
    b->inFill = 0;
    b->fifoRead = 0;
    b->fifoWrite = 0;
    b->fifoCount = 0;
    if (b->quantum > 0 && !b->fifo.empty()) {
        // Re-prime with `quantum` frames of silence: keeps output available from the first block.
        std::fill(b->fifo.begin(), b->fifo.end(), 0.0f);
        b->fifoWrite = b->quantum * 2;
        b->fifoCount = b->quantum;
    }
}

bool bridge_set_quantum(Bridge* b, int quantumFrames, int maxHostFrames) {
    if (!b) return false;
    if (quantumFrames <= 0) {
        b->quantum = 0;
        b->maxHostFrames = 0;
        b->inBuf.clear();
        b->inBuf.shrink_to_fit();
        b->fifo.clear();
        b->fifo.shrink_to_fit();
        b->fifoCapacity = 0;
        bridge_flush_fifo(b);
        return true;
    }
    const int host = std::max(maxHostFrames, quantumFrames);
    const int capacity = quantumFrames * 3 + host;
    try {
        b->inBuf.assign(static_cast<size_t>(quantumFrames) * 2, 0.0f);
        b->fifo.assign(static_cast<size_t>(capacity) * 2, 0.0f);
    } catch (...) {
        b->quantum = 0;
        b->fifoCapacity = 0;
        b->inBuf.clear();
        b->fifo.clear();
        return false;
    }
    b->quantum = quantumFrames;
    b->maxHostFrames = host;
    b->fifoCapacity = capacity;
    bridge_flush_fifo(b);
    return true;
}

int bridge_quantum(const Bridge* b) { return b ? b->quantum : 0; }

int bridge_latency_frames(const Bridge* b) { return (b && b->quantum > 0) ? b->quantum : 0; }

static inline void fifoPush(Bridge* b, const float* src, int frames) {
    for (int i = 0; i < frames; ++i) {
        b->fifo[static_cast<size_t>(b->fifoWrite)] = src[2 * i];
        b->fifo[static_cast<size_t>(b->fifoWrite) + 1] = src[2 * i + 1];
        b->fifoWrite += 2;
        if (b->fifoWrite >= b->fifoCapacity * 2) b->fifoWrite = 0;
    }
    b->fifoCount += frames;
}

static inline void fifoPop(Bridge* b, float* dst, int frames) {
    for (int i = 0; i < frames; ++i) {
        dst[2 * i] = b->fifo[static_cast<size_t>(b->fifoRead)];
        dst[2 * i + 1] = b->fifo[static_cast<size_t>(b->fifoRead) + 1];
        b->fifoRead += 2;
        if (b->fifoRead >= b->fifoCapacity * 2) b->fifoRead = 0;
    }
    b->fifoCount -= frames;
}

// Re-blocks one chunk that is guaranteed to fit the FIFO (chunk <= maxHostFrames).
static void processChunk(Bridge* b, float* data, int frames) {
    const int q = b->quantum;
    int consumed = 0;
    while (consumed < frames) {
        const int take = std::min(q - b->inFill, frames - consumed);
        std::memcpy(b->inBuf.data() + static_cast<size_t>(b->inFill) * 2,
                    data + static_cast<size_t>(consumed) * 2,
                    static_cast<size_t>(take) * 2 * sizeof(float));
        b->inFill += take;
        consumed += take;
        if (b->inFill == q) {
            b->process(b->inst, b->inBuf.data(), q);
            ++b->invocations;
            fifoPush(b, b->inBuf.data(), q);
            b->inFill = 0;
        }
    }

    if (b->fifoCount >= frames) {
        fifoPop(b, data, frames);
    } else {
        // Cannot happen with the priming in bridge_flush_fifo, but never read stale memory.
        const int have = b->fifoCount;
        if (have > 0) fifoPop(b, data, have);
        std::memset(data + static_cast<size_t>(have) * 2, 0,
                    static_cast<size_t>(frames - have) * 2 * sizeof(float));
        b->fifoUnderflowFrames += static_cast<unsigned int>(frames - have);
    }
}

int bridge_process_quantized(Bridge* b, float* data, int frames) {
    if (!b || !b->inst || !data || frames <= 0) return 0;
    if (b->quantum <= 0 || b->fifoCapacity <= 0) {
        // AUTO: the engine sees the host block size directly, no FIFO, no added latency.
        b->process(b->inst, data, frames);
        return 1;
    }
    // Note: there is deliberately no "frames == quantum" shortcut. Once the FIFO is primed it
    // holds the stream's delay, so skipping it for one block would duplicate/drop audio.
    b->invocations = 0;
    int done = 0;
    while (done < frames) {
        const int chunk = std::min(frames - done, b->maxHostFrames);
        processChunk(b, data + static_cast<size_t>(done) * 2, chunk);
        done += chunk;
    }
    return b->invocations;
}

unsigned int bridge_fifo_underflow_frames(const Bridge* b) {
    return b ? b->fifoUnderflowFrames : 0u;
}
