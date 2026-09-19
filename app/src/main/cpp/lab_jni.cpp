#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <vector>
#include "frostsoulx/immersive_audio_engine.h"

namespace {
frostsoulx::ImmersiveAudioEngine engine;
int sampleRate = 48000;
long long processCalls = 0;
float inputRms = 0.0f, outputRms = 0.0f, inputPeak = 0.0f, outputPeak = 0.0f, maxDiff = 0.0f;
float changedPercentage = 0.0f, dcOffset = 0.0f;
long long clippingCount = 0, nanCount = 0, infCount = 0, processingTimeUs = 0;
std::vector<float> inputSnapshot;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativePrepare(JNIEnv*, jclass, jint rate, jint maxFrames) {
    sampleRate = rate;
    processCalls = 0;
    inputSnapshot.assign(static_cast<size_t>(std::max(0, maxFrames)) * 2U, 0.0f);
    return engine.prepare(rate, maxFrames) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetEnabled(JNIEnv*, jclass, jboolean value) { engine.setEnabled(value == JNI_TRUE); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetIntensity(JNIEnv*, jclass, jfloat value) { engine.setSpatialBlend(std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetRoomPreset(JNIEnv*, jclass, jint value) { engine.setRoomSimulationPreset(static_cast<frostsoulx::RoomSimulationPreset>(std::clamp(value, 0, 5))); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetRoomMix(JNIEnv*, jclass, jfloat value) { engine.setRoomMix(value); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetReflectionAmount(JNIEnv*, jclass, jfloat value) { engine.setReflectionAmount(value); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetReverbTime(JNIEnv*, jclass, jfloat value) { engine.setReverbTimeSeconds(value); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetRoomSize(JNIEnv*, jclass, jfloat value) { engine.setRoomSize(value); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetDampening(JNIEnv*, jclass, jfloat value) { engine.setDampening(value); }
extern "C" JNIEXPORT void JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeSetWidth(JNIEnv*, jclass, jfloat value) { engine.setStereoWidth(value); }
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeDiagnostics(JNIEnv* env, jclass) {
    const double values[] = {inputRms, outputRms, inputPeak, outputPeak, maxDiff, changedPercentage,
        static_cast<double>(clippingCount), dcOffset, static_cast<double>(nanCount), static_cast<double>(infCount),
        static_cast<double>(processingTimeUs), static_cast<double>(processCalls), static_cast<double>(sampleRate),
        static_cast<double>(engine.maxFrames()), 2.0, static_cast<double>(engine.lastProcessResult()),
        static_cast<double>(engine.lastEffectState())};
    auto result = env->NewDoubleArray(17); env->SetDoubleArrayRegion(result, 0, 17, values); return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcess(JNIEnv* env, jclass, jfloatArray pcm) {
    const jsize n = env->GetArrayLength(pcm); if (n <= 0 || n % 2 != 0) return JNI_FALSE;
    std::vector<float> data(static_cast<size_t>(n)); env->GetFloatArrayRegion(pcm, 0, n, data.data());
    double inEnergy = 0.0, outEnergy = 0.0, difference = 0.0; inputPeak = outputPeak = maxDiff = 0.0f;
    for (jsize i = 0; i < n; ++i) { const float x = data[static_cast<size_t>(i)]; inputPeak = std::max(inputPeak, std::fabs(x)); inEnergy += x * x; if (i < static_cast<jsize>(inputSnapshot.size())) inputSnapshot[static_cast<size_t>(i)] = x; }
    const auto started = std::chrono::steady_clock::now();
    const bool ok = engine.process(data.data(), n / 2);
    processingTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
    long long changed = 0;
    for (jsize i = 0; i < n; ++i) { outputPeak = std::max(outputPeak, std::fabs(data[i])); outEnergy += data[i] * data[i]; const float diff = std::fabs(data[i] - inputSnapshot[static_cast<size_t>(i)]); difference = std::max(difference, static_cast<double>(diff)); if (diff > 1.0e-5f) ++changed; if (std::isnan(data[i])) ++nanCount; else if (!std::isfinite(data[i])) ++infCount; if (std::fabs(data[i]) >= 0.98f) ++clippingCount; }
    inputRms = static_cast<float>(std::sqrt(inEnergy / n)); outputRms = static_cast<float>(std::sqrt(outEnergy / n));
    maxDiff = static_cast<float>(difference); changedPercentage = n > 0 ? (100.0f * changed / n) : 0.0f;
    env->SetFloatArrayRegion(pcm, 0, n, data.data()); processCalls++; return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcessDirect(JNIEnv* env, jclass, jobject buffer, jint frames) {
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr || frames <= 0 || frames > engine.maxFrames()) return JNI_FALSE;
    double inEnergy = 0.0, outEnergy = 0.0, dcSum = 0.0;
    inputPeak = outputPeak = maxDiff = 0.0f;
    for (int i = 0; i < frames * 2; ++i) {
        inputPeak = std::max(inputPeak, std::fabs(data[i]));
        inEnergy += static_cast<double>(data[i]) * data[i];
        if (i < static_cast<int>(inputSnapshot.size())) inputSnapshot[static_cast<size_t>(i)] = data[i];
        if (std::isnan(data[i])) ++nanCount; else if (!std::isfinite(data[i])) ++infCount;
    }
    const auto started = std::chrono::steady_clock::now();
    const bool ok = engine.process(data, frames);
    processingTimeUs = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
    long long changed = 0;
    for (int i = 0; i < frames * 2; ++i) {
        outputPeak = std::max(outputPeak, std::fabs(data[i]));
        outEnergy += static_cast<double>(data[i]) * data[i]; dcSum += data[i];
        const float diff = std::fabs(data[i] - inputSnapshot[static_cast<size_t>(i)]); maxDiff = std::max(maxDiff, diff); if (diff > 1.0e-5f) ++changed;
        if (std::isnan(data[i])) ++nanCount; else if (!std::isfinite(data[i])) ++infCount;
        if (std::fabs(data[i]) >= 0.98f) ++clippingCount;
    }
    inputRms = static_cast<float>(std::sqrt(inEnergy / (frames * 2)));
    outputRms = static_cast<float>(std::sqrt(outEnergy / (frames * 2)));
    changedPercentage = 100.0f * changed / (frames * 2); dcOffset = static_cast<float>(dcSum / (frames * 2));
    processCalls++;
    return ok ? JNI_TRUE : JNI_FALSE;
}
