#pragma once
#include <stdint.h>

/* Stable C boundary: no STL, JNI, Android, or ownership crosses this API.
 * V1 is immutable. New incompatible layouts require a new ABI version.
 * All calls for an instance must be serialized by the host; process allocates no memory.
 * A failed process may have modified samples; hosts must restore their dry backup.
 */
#ifdef __cplusplus
extern "C" {
#endif
#define FX_ABI_VERSION 1u
#define FX_STAGE_COUNT 5u

typedef struct FxControls {
    uint32_t enabled;
    int32_t preset;
    float intensity, room_mix, reflection, reverb_seconds, room_size, dampening, width;
} FxControls;

typedef struct FxStage {
    char name[48];
    uint32_t enabled;
    double milliseconds;
} FxStage;

typedef struct FxTelemetry {
    uint32_t size;
    int32_t result;
    uint32_t stage_count;
    FxStage stages[FX_STAGE_COUNT];
} FxTelemetry;

typedef struct FxApi {
    uint32_t version, size;
    const char* name;
    void* (*create)(void);
    void (*destroy)(void*);
    int32_t (*prepare)(void*, int32_t sample_rate, int32_t max_frames);
    void (*reset)(void*);
    void (*controls)(void*, const FxControls*);
    int32_t (*process)(void*, float* stereo, int32_t frames);
    /* Optional; hosts show stage telemetry as unavailable when null. */
    void (*telemetry)(void*, FxTelemetry*);
} FxApi;

typedef const FxApi* (*FxGetApi)(uint32_t version);
#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
const FxApi* frostsoulx_get_api(uint32_t version);
#ifdef __cplusplus
}
#endif
