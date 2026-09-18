#include <jni.h>
#include <algorithm>
#include <cmath>
#include <vector>
#include "frostsoulx/immersive_audio_engine.h"

namespace {
frostsoulx::ImmersiveAudioEngine engine;
int sampleRate = 48000;
long long processCalls = 0;
float inputRms = 0.0f, outputRms = 0.0f, inputPeak = 0.0f, outputPeak = 0.0f, maxDiff = 0.0f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativePrepare(JNIEnv*, jclass, jint rate, jint maxFrames) {
    sampleRate = rate;
    processCalls = 0;
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
    const double values[] = {inputRms, outputRms, inputPeak, outputPeak, maxDiff, static_cast<double>(processCalls), static_cast<double>(engine.lastProcessResult())};
    auto result = env->NewDoubleArray(7); env->SetDoubleArrayRegion(result, 0, 7, values); return result;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcess(JNIEnv* env, jclass, jfloatArray pcm) {
    const jsize n = env->GetArrayLength(pcm); if (n <= 0 || n % 2 != 0) return JNI_FALSE;
    std::vector<float> data(static_cast<size_t>(n)); env->GetFloatArrayRegion(pcm, 0, n, data.data());
    double inEnergy = 0.0, outEnergy = 0.0; inputPeak = outputPeak = maxDiff = 0.0f;
    for (float x : data) { inputPeak = std::max(inputPeak, std::fabs(x)); inEnergy += x * x; }
    const bool ok = engine.process(data.data(), n / 2);
    for (float x : data) { outputPeak = std::max(outputPeak, std::fabs(x)); outEnergy += x * x; }
    inputRms = static_cast<float>(std::sqrt(inEnergy / n)); outputRms = static_cast<float>(std::sqrt(outEnergy / n));
    env->SetFloatArrayRegion(pcm, 0, n, data.data()); processCalls++; return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vxs_frostsoulxdsp_NativeEngine_nativeProcessDirect(JNIEnv* env, jclass, jobject buffer, jint frames) {
    auto* data = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    if (data == nullptr || frames <= 0 || frames > engine.maxFrames()) return JNI_FALSE;
    double inEnergy = 0.0;
    inputPeak = outputPeak = maxDiff = 0.0f;
    for (int i = 0; i < frames * 2; ++i) {
        inputPeak = std::max(inputPeak, std::fabs(data[i]));
        inEnergy += static_cast<double>(data[i]) * data[i];
    }
    const bool ok = engine.process(data, frames);
    double outEnergy = 0.0;
    for (int i = 0; i < frames * 2; ++i) {
        outputPeak = std::max(outputPeak, std::fabs(data[i]));
        outEnergy += static_cast<double>(data[i]) * data[i];
    }
    inputRms = static_cast<float>(std::sqrt(inEnergy / (frames * 2)));
    outputRms = static_cast<float>(std::sqrt(outEnergy / (frames * 2)));
    processCalls++;
    return ok ? JNI_TRUE : JNI_FALSE;
}
