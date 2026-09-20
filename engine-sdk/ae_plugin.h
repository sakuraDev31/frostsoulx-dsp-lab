/*
 * Resonance engine plugin ABI, version 1.
 *
 * A sound engine is a shared library (.so) exporting the six C functions below.
 * The app loads it with dlopen(), so the engine can be built with any toolchain
 * as long as it exports this C interface.
 *
 * Threading: every call for one AeEngine instance is made from one thread (the
 * player's audio thread). The engine does NOT need to be thread safe. Parameter
 * changes are queued by the app and applied on that thread between buffers.
 *
 * Audio: interleaved 32-bit float, in-place, nominal range [-1, 1].
 * The app clamps the output to [-1, 1] afterwards, so stay near or below full scale.
 * Only stereo (channels == 2) is passed today.
 */
#ifndef AE_PLUGIN_H
#define AE_PLUGIN_H

#ifdef __cplusplus
extern "C" {
#endif

#define AE_ABI_VERSION 1

#if defined(_WIN32)
#define AE_EXPORT __declspec(dllexport)
#else
#define AE_EXPORT __attribute__((visibility("default")))
#endif

typedef struct AeEngine AeEngine;

/* Must return AE_ABI_VERSION. */
AE_EXPORT int ae_abi_version(void);

/* Create an instance. Return NULL if the format is unsupported. */
AE_EXPORT AeEngine* ae_create(int sample_rate, int channels);

AE_EXPORT void ae_destroy(AeEngine* engine);

/* `id` is a parameter id from manifest.json. Unknown ids must be ignored.
 * Sliders pass their real value, toggles pass 0 or 1, choices pass the option index. */
AE_EXPORT void ae_set_param(AeEngine* engine, const char* id, float value);

/* Process `frames` interleaved stereo frames in place. */
AE_EXPORT void ae_process(AeEngine* engine, float* interleaved, int frames);

/* Clear internal state (delay lines, reverb tails). Called on seek and track change. */
AE_EXPORT void ae_reset(AeEngine* engine);

#ifdef __cplusplus
}
#endif

#endif /* AE_PLUGIN_H */
