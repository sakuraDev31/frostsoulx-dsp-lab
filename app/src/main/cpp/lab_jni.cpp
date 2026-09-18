#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <ctime>
#include <vector>
#include "engine_api.h"
#include <dlfcn.h>
#include <string>
#include <cstring>

namespace {
constexpr int kMaxFrames = 16384, kWaveSize = 256, kPrefix = 40;
const FxApi* api = nullptr;
void* instance = nullptr;
void* pluginHandle = nullptr;
std::mutex engineMutex; // loader/lifecycle serialize; audio never waits
std::array<std::array<char, 48>, FX_STAGE_COUNT> stageNames{};
FxTelemetry telemetry{};
bool initialize() {
    if (!api) api = frostsoulx_get_api(FX_ABI_VERSION);
    if (!instance && api) instance = api->create();
    return instance != nullptr;
}
void readTelemetry() {
    telemetry = {}; telemetry.size = sizeof(telemetry); telemetry.result = -1;
    if (api->telemetry) api->telemetry(instance, &telemetry);
    telemetry.stage_count = std::min(telemetry.stage_count, FX_STAGE_COUNT);
}
// UI only writes mailboxes. Engine lifecycle, setters and processing share the audio thread.
std::array<std::atomic<float>, 10> controls{{0, 1, 2, .18f, .28f, 1.35f, .5f, .5f, .5f, 0}};
std::array<float, 10> applied{};
std::atomic<bool> resetRequested{false};
bool controlsApplied = false;
int sampleRate = 0, profileFrames = 0;
std::uint64_t lastProfileSequence = 0;
std::uint64_t calls = 0, inputClips = 0, outputClips = 0, invalidSamples = 0;
std::array<float, kMaxFrames * 2> original{};
std::array<double, kPrefix + kWaveSize> published{};
std::mutex snapshotMutex; // audio uses try_lock ONLY; UI cannot stall playback
using Clock = std::chrono::steady_clock;
double ms(Clock::duration duration) { return std::chrono::duration<double, std::milli>(duration).count(); }
double cpuMs() {
    timespec t{};
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t) != 0) return -1;
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}
void applyControls() {
    for (size_t i = 0; i < controls.size(); ++i) applied[i] = controls[i].load(std::memory_order_relaxed);
    const FxControls c{uint32_t(applied[0] != 0), uint32_t(applied[9] != 0), int32_t(applied[2]),
        applied[1], applied[3], applied[4], applied[5], applied[6], applied[7], applied[8]};
    api->controls(instance, &c);
    controlsApplied = true;
}
void publish(const std::array<double, kPrefix + kWaveSize>& data) {
    std::unique_lock<std::mutex> lock(snapshotMutex, std::try_to_lock);
    if (lock.owns_lock()) {
        published = data;
        for (unsigned s = 0; s < FX_STAGE_COUNT; ++s) {
            stageNames[s].fill(0);
            if (s < telemetry.stage_count) std::memcpy(stageNames[s].data(), telemetry.stages[s].name, 47);
        }
    }
}
bool process(float* data, int frames) {
    std::unique_lock<std::mutex> lock(engineMutex, std::try_to_lock);
    if (!lock.owns_lock() || !instance || !data || frames <= 0 || sampleRate <= 0) return false;
    const double cpuStart = cpuMs();
    applyControls();
    if (resetRequested.exchange(false)) {
        api->reset(instance); calls = inputClips = outputClips = invalidSamples = 0;
        profileFrames = 0; lastProfileSequence = 0;
    }
    std::array<double, kPrefix + kWaveSize> d{};
    std::array<double, 4> energy{}, sum{}, peak{};
    double processMs = 0, maxDiff = 0;
    bool ok = true;
    unsigned stageMask = 0;
    for (int offset = 0; offset < frames;) {
        const int n = std::min(kMaxFrames, frames - offset);
        float* block = data + offset * 2;
        std::copy_n(block, n * 2, original.data());
        const auto start = Clock::now();
        const bool processed = api->process(instance, block, n);
        processMs += ms(Clock::now() - start);
        // A native error must never leak a partially processed buffer.
        if (!processed) std::copy_n(original.data(), n * 2, block);
        ok = ok && processed;
        readTelemetry();
        for (unsigned s = 0; s < FX_STAGE_COUNT; ++s) {
            d[28 + s] = s < telemetry.stage_count ? telemetry.stages[s].milliseconds : -1;
            if (s < telemetry.stage_count && telemetry.stages[s].enabled) stageMask |= 1u << s;
        }
        d[33] = telemetry.stage_count ? double(telemetry.profile_sequence) : -1;
        profileFrames = telemetry.profile_frames;
        for (int i = 0; i < n * 2; ++i) {
            const int ch = i % 2;
            float in = original[i], out = block[i];
            if (!std::isfinite(in)) { ++invalidSamples; in = 0; }
            if (!std::isfinite(out)) { ++invalidSamples; out = block[i] = 0; }
            if (std::fabs(in) >= 1) ++inputClips;
            if (std::fabs(out) >= 1) ++outputClips;
            peak[ch] = std::max(peak[ch], double(std::fabs(in)));
            peak[ch + 2] = std::max(peak[ch + 2], double(std::fabs(out)));
            energy[ch] += double(in) * in; energy[ch + 2] += double(out) * out;
            sum[ch] += in; sum[ch + 2] += out;
            maxDiff = std::max(maxDiff, double(std::fabs(out - in)));
        }
        offset += n;
    }
    d[0] = 1; d[1] = double(++calls); d[2] = sampleRate; d[3] = frames;
    d[4] = kMaxFrames; d[5] = 2; d[6] = processMs;
    d[8] = frames * 1000.0 / sampleRate;
    const double cpuEnd = cpuMs();
    d[7] = cpuStart < 0 || cpuEnd < 0 ? -1 : (cpuEnd - cpuStart) / d[8] * 100;
    d[9] = telemetry.quantum_frames ? telemetry.quantum_frames * 1000.0 / sampleRate : -1;
    d[10] = double(telemetry.result); d[11] = stageMask;
    d[12] = double(inputClips); d[13] = double(outputClips); d[14] = double(invalidSamples); d[15] = maxDiff;
    for (int ch = 0; ch < 2; ++ch) {
        d[16 + ch] = peak[ch]; d[18 + ch] = std::sqrt(energy[ch] / frames);
        d[20 + ch] = peak[ch + 2]; d[22 + ch] = std::sqrt(energy[ch + 2] / frames);
        d[24 + ch] = sum[ch] / frames; d[26 + ch] = sum[ch + 2] / frames;
    }
    const int waveFrames = std::min(frames, kWaveSize);
    d[32] = waveFrames; d[34] = telemetry.prepared;
    d[35] = ms(Clock::now().time_since_epoch());
    d[36] = profileFrames; d[37] = applied[9]; d[38] = applied[0];
    for (int i = 0; i < waveFrames; ++i) {
        const int index = (frames - waveFrames + i) * 2;
        d[kPrefix + i] = (double(data[index]) + data[index + 1]) * .5;
    }
    publish(d);
    return ok;
}
void control(int index, float value, float lo, float hi) {
    controls[index].store(std::isfinite(value) ? std::clamp(value, lo, hi) : lo, std::memory_order_relaxed);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativePrepare(JNIEnv*, jclass, jint rate, jint) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!initialize()) return JNI_FALSE;
    sampleRate = rate; calls = inputClips = outputClips = invalidSamples = 0;
    controlsApplied = false; profileFrames = 0; lastProfileSequence = 0;
    const bool ready = api->prepare(instance, rate, kMaxFrames);
    applyControls();
    readTelemetry();
    std::array<double, kPrefix + kWaveSize> d{};
    d[0] = 1; d[2] = rate; d[4] = kMaxFrames; d[5] = 2; d[34] = ready;
    publish(d);
    return ready;
}
#define CONTROL_JNI(Name, Index, Type, Low, High) \
extern "C" JNIEXPORT void JNICALL Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSet##Name(JNIEnv*, jclass, Type value) { control(Index, value, Low, High); }
CONTROL_JNI(Enabled, 0, jboolean, 0, 1)
CONTROL_JNI(Intensity, 1, jfloat, 0, 1)
CONTROL_JNI(RoomPreset, 2, jint, 0, 5)
CONTROL_JNI(RoomMix, 3, jfloat, 0, 1)
CONTROL_JNI(ReflectionAmount, 4, jfloat, 0, 1)
CONTROL_JNI(ReverbTime, 5, jfloat, .2f, 8)
CONTROL_JNI(RoomSize, 6, jfloat, 0, 1)
CONTROL_JNI(Dampening, 7, jfloat, 0, 1)
CONTROL_JNI(Width, 8, jfloat, 0, 1)
CONTROL_JNI(Profiling, 9, jboolean, 0, 1)
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeReset(JNIEnv*, jclass) { resetRequested.store(true); }
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeDiagnostics(JNIEnv* env, jclass) {
    std::array<double, kPrefix + kWaveSize> copy;
    { std::lock_guard<std::mutex> lock(snapshotMutex); copy = published; }
    copy[39] = copy[35] > 0 ? ms(Clock::now().time_since_epoch()) - copy[35] : -1;
    auto result = env->NewDoubleArray(copy.size());
    if (result) env->SetDoubleArrayRegion(result, 0, copy.size(), copy.data());
    return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcess(JNIEnv* env, jclass, jfloatArray pcm) {
    if (!pcm) return JNI_FALSE;
    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0 || n % 2 != 0 || n > kMaxFrames * 2) return JNI_FALSE;
    // Compatibility/test API only. Playback always uses the allocation-free direct path.
    std::vector<float> data(n);
    env->GetFloatArrayRegion(pcm, 0, n, data.data());
    const bool ok = process(data.data(), n / 2);
    env->SetFloatArrayRegion(pcm, 0, n, data.data());
    return ok;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcessDirect(JNIEnv* env, jclass, jobject buffer, jint frames) {
    if (!buffer || frames <= 0) return JNI_FALSE;
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!data || capacity < 0 || static_cast<jlong>(frames) * 8 > capacity) return JNI_FALSE;
    return process(data, frames);
}

// The caller releases Media3 before replacement. Keep the previous instance until the
// candidate passes ABI validation AND prepare. No C++ symbols cross this boundary.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeLoadPlugin(JNIEnv* env, jclass, jstring path) {
    std::string location;
    if (path) {
        const char* chars = env->GetStringUTFChars(path, nullptr);
        if (!chars) return nullptr;
        location = chars; env->ReleaseStringUTFChars(path, chars);
    }
    std::lock_guard<std::mutex> lock(engineMutex);
    void* handle = nullptr;
    const FxApi* candidate = nullptr;
    if (location.empty()) candidate = frostsoulx_get_api(FX_ABI_VERSION);
    else {
        handle = dlopen(location.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!handle) { const char* error = dlerror(); return env->NewStringUTF(error ? error : "dlopen failed"); }
        const auto getApi = reinterpret_cast<FxGetApi>(dlsym(handle, "frostsoulx_get_api"));
        if (getApi) candidate = getApi(FX_ABI_VERSION);
    }
    if (!candidate || candidate->version != FX_ABI_VERSION || candidate->size != sizeof(FxApi) ||
        !candidate->create || !candidate->destroy || !candidate->prepare || !candidate->reset ||
        !candidate->controls || !candidate->process) {
        if (handle) dlclose(handle);
        return env->NewStringUTF("Incompatible engine C ABI (expected v1)");
    }
    void* next = candidate->create();
    if (!next || !candidate->prepare(next, sampleRate > 0 ? sampleRate : 48000, kMaxFrames)) {
        if (next) candidate->destroy(next);
        if (handle) dlclose(handle);
        return env->NewStringUTF("Engine prepare failed; previous engine retained");
    }
    if (instance) api->destroy(instance);
    if (pluginHandle) dlclose(pluginHandle);
    api = candidate; instance = next; pluginHandle = handle;
    controlsApplied = false; calls = inputClips = outputClips = invalidSamples = 0;
    profileFrames = 0; lastProfileSequence = 0; sampleRate = 0;
    applyControls(); readTelemetry();
    // The prepare above is only a compatibility probe, NOT a playback measurement.
    publish({});
    return env->NewStringUTF("");
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeStageNames(JNIEnv* env, jclass) {
    std::array<std::array<char, 48>, FX_STAGE_COUNT> copy;
    { std::lock_guard<std::mutex> lock(snapshotMutex); copy = stageNames; }
    jclass strings = env->FindClass("java/lang/String");
    if (!strings) return nullptr;
    auto result = env->NewObjectArray(FX_STAGE_COUNT, strings, nullptr);
    if (!result) return nullptr;
    for (unsigned s = 0; s < FX_STAGE_COUNT; ++s) {
        auto name = env->NewStringUTF(copy[s].data());
        env->SetObjectArrayElement(result, s, name); env->DeleteLocalRef(name);
    }
    return result;
}
