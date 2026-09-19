#include "bridge_core.h"

#include <dlfcn.h>

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
}
