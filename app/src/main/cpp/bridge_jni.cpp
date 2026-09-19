// JNI glue for app.resonance.player.engine.NativeEngine. All logic lives in bridge_core.cpp.
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include "bridge_core.h"

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
    return reinterpret_cast<jlong>(b);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeSetParam(JNIEnv* env, jobject, jlong handle,
                                                             jstring id, jfloat value) {
    const std::string s = toStd(env, id);
    bridge_set_param(reinterpret_cast<Bridge*>(handle), s.c_str(), value);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeProcess(JNIEnv* env, jobject, jlong handle,
                                                            jobject buffer, jint frames) {
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (!data) return;
    bridge_process(reinterpret_cast<Bridge*>(handle), data, frames);
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeReset(JNIEnv*, jobject, jlong handle) {
    bridge_reset(reinterpret_cast<Bridge*>(handle));
}

JNIEXPORT void JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeClose(JNIEnv*, jobject, jlong handle) {
    bridge_close(reinterpret_cast<Bridge*>(handle));
}

JNIEXPORT jstring JNICALL
Java_app_resonance_player_engine_NativeEngine_nativeLastError(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_errMutex);
    return env->NewStringUTF(g_lastError.c_str());
}

}  // extern "C"
