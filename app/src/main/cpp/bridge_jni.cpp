// JNI glue for app.resonance.player.engine.NativeEngine. All logic lives in bridge_core.cpp
// (plugin loading / quantum re-blocking) and telemetry.cpp (measurement).
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "bridge_core.h"
#include "telemetry.h"

namespace {

std::mutex g_errMutex;
std::string g_lastError;

void setLastError(const std::string& s) {
    std::lock_guard<std::mutex> lock(g_errMutex);
    g_lastError = s;
    // JNI's NewStringUTF wants (modified) UTF-8; keep it ASCII to be safe.
    for (auto& ch : g_lastError) {
        if (static_cast<unsigned char>(ch) > 127) ch = '?';
    }
}

std::string toStd(JNIEnv* env, jstring js) {
    if (!js) return std::string();
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = c ? c : "";
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeOpen(JNIEnv* env, jobject, jstring entry,
                                                         jobjectArray preload, jint sampleRate,
                                                         jint channels) {
    std::vector<std::string> pre;
    const jsize n = preload ? env->GetArrayLength(preload) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(preload, i));
        pre.push_back(toStd(env, js));
        if (js) env->DeleteLocalRef(js);
    }
    std::string error;
    Bridge* b = bridge_open(toStd(env, entry), pre, sampleRate, channels, error);
    if (!b) {
        setLastError(error);
        return 0;
    }
    telemetry::configure(sampleRate);
    telemetry::setEngineActive(true);
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSetParam(JNIEnv* env, jobject, jlong handle,
                                                             jstring id, jfloat value) {
    const std::string s = toStd(env, id);
    bridge_set_param(reinterpret_cast<Bridge*>(handle), s.c_str(), value);
}

/**
 * One host audio callback: measures the input, runs the engine (re-blocked to the configured
 * DSP quantum), times every engine invocation, then sanitises and measures the output.
 * Realtime safe: no allocation, no locking, no logging.
 */
JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeProcess(JNIEnv* env, jobject, jlong handle,
                                                            jobject buffer, jint frames) {
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (!data || frames <= 0) return;
    auto* b = reinterpret_cast<Bridge*>(handle);

    telemetry::recordCallback(frames);
    telemetry::analyzeInput(data, frames);

    const int64_t t0 = telemetry::nowNs();
    bridge_process_quantized(b, data, frames);
    const int64_t t1 = telemetry::nowNs();

    // Time is attributed to the engine block size that actually ran.
    const int q = bridge_quantum(b);
    const int blockFrames = (q > 0 && q <= frames) ? q : frames;
    const int invocations = (q > 0 && q <= frames) ? (frames / q) : 1;
    if (invocations > 1) {
        const int64_t per = (t1 - t0) / invocations;
        for (int i = 0; i < invocations; ++i) {
            telemetry::recordBlock(t0 + per * i, t0 + per * (i + 1), blockFrames);
        }
    } else {
        telemetry::recordBlock(t0, t1, blockFrames);
    }

    telemetry::analyzeOutputAndSanitize(data, frames);
}

/** Measures and sanitises a block the engine did not touch (bypass / no engine loaded). */
JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeMeter(JNIEnv* env, jobject, jobject buffer,
                                                          jint frames) {
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (!data || frames <= 0) return;
    telemetry::recordCallback(frames);
    telemetry::analyzeInput(data, frames);
    telemetry::analyzeOutputAndSanitize(data, frames);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeReset(JNIEnv*, jobject, jlong handle) {
    bridge_reset(reinterpret_cast<Bridge*>(handle));
    telemetry::resetLoudness();
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeClose(JNIEnv*, jobject, jlong handle) {
    bridge_close(reinterpret_cast<Bridge*>(handle));
    telemetry::setEngineActive(false);
    telemetry::setQuantum(0, 0);
}

JNIEXPORT jstring JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_errMutex);
    return env->NewStringUTF(g_lastError.c_str());
}

// --- processing quantum ---------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSetQuantum(JNIEnv*, jobject, jlong handle,
                                                               jint quantum, jint maxHostFrames) {
    auto* b = reinterpret_cast<Bridge*>(handle);
    const bool ok = bridge_set_quantum(b, quantum, maxHostFrames);
    telemetry::setQuantum(bridge_quantum(b), bridge_latency_frames(b));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeQuantum(JNIEnv*, jobject, jlong handle) {
    return bridge_quantum(reinterpret_cast<Bridge*>(handle));
}

JNIEXPORT jint JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeLatencyFrames(JNIEnv*, jobject, jlong handle) {
    return bridge_latency_frames(reinterpret_cast<Bridge*>(handle));
}

// --- telemetry -------------------------------------------------------------------------------

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeConfigureTelemetry(JNIEnv*, jobject,
                                                                      jint sampleRate) {
    telemetry::configure(sampleRate);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSetBypass(JNIEnv*, jobject, jboolean bypass) {
    telemetry::setBypass(bypass == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSetEngineActive(JNIEnv*, jobject,
                                                                    jboolean active) {
    telemetry::setEngineActive(active == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeResetStats(JNIEnv*, jobject) {
    telemetry::resetStats();
}

JNIEXPORT jint JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSnapshot(JNIEnv* env, jobject,
                                                             jdoubleArray out) {
    if (!out) return 0;
    const jsize cap = env->GetArrayLength(out);
    jdouble* p = env->GetDoubleArrayElements(out, nullptr);
    if (!p) return 0;
    const int n = telemetry::snapshot(p, static_cast<int>(cap));
    env->ReleaseDoubleArrayElements(out, p, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSpectrum(JNIEnv* env, jobject,
                                                             jfloatArray out, jint fftSize) {
    if (!out) return 0;
    const jsize cap = env->GetArrayLength(out);
    jfloat* p = env->GetFloatArrayElements(out, nullptr);
    if (!p) return 0;
    const int n = telemetry::spectrum(p, static_cast<int>(cap), fftSize);
    env->ReleaseFloatArrayElements(out, p, 0);
    return n;
}

JNIEXPORT jint JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSlotCount(JNIEnv*, jobject) {
    return telemetry::S_SLOT_COUNT;
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeBenchmarkStart(JNIEnv*, jobject,
                                                                   jlong durationMs) {
    telemetry::benchmarkStart(durationMs);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeBenchmarkStop(JNIEnv*, jobject) {
    telemetry::benchmarkStop();
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeNoteUnderrun(JNIEnv*, jobject) {
    telemetry::noteUnderrun();
}

}  // extern "C"
