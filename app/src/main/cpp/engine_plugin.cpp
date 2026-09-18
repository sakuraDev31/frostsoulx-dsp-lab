#include "engine_api.h"
#include "frostsoulx/immersive_audio_engine.h"
#include <algorithm>
#include <cstdio>
#include <new>

namespace {
struct Instance {
    frostsoulx::ImmersiveAudioEngine engine;
    FxControls applied{};
    bool hasControls = false;
    uint64_t sequence = 0;
    uint32_t profileFrames = 0;
};
void* create() { try { return new Instance; } catch (...) { return nullptr; } }
void destroy(void* p) { delete static_cast<Instance*>(p); }
int32_t prepare(void* p, int32_t rate, int32_t frames) {
    auto& i = *static_cast<Instance*>(p);
    i.hasControls = false; i.sequence = 0; i.profileFrames = 0;
    return i.engine.prepare(rate, frames);
}
void reset(void* p) {
    auto& i = *static_cast<Instance*>(p);
    i.engine.reset(); i.sequence = 0; i.profileFrames = 0;
}
void controls(void* p, const FxControls* c) {
    auto& i = *static_cast<Instance*>(p);
    const auto& a = i.applied;
#define APPLY(field, call) if (!i.hasControls || a.field != c->field) i.engine.call
    APPLY(enabled, setEnabled(c->enabled != 0));
    APPLY(intensity, setSpatialBlend(c->intensity));
    APPLY(preset, setRoomSimulationPreset(static_cast<frostsoulx::RoomSimulationPreset>(c->preset)));
    APPLY(room_mix, setRoomMix(c->room_mix));
    APPLY(reflection, setReflectionAmount(c->reflection));
    APPLY(reverb_seconds, setReverbTimeSeconds(c->reverb_seconds));
    APPLY(room_size, setRoomSize(c->room_size));
    APPLY(dampening, setDampening(c->dampening));
    APPLY(width, setStereoWidth(c->width));
#if defined(FROSTSOULX_DIAGNOSTICS_API)
    APPLY(profiling, setDiagnosticsEnabled(c->profiling != 0));
#endif
#undef APPLY
    i.applied = *c; i.hasControls = true;
}
int32_t process(void* p, float* pcm, int32_t frames) {
    auto& i = *static_cast<Instance*>(p);
    const bool result = i.engine.process(pcm, frames);
#if defined(FROSTSOULX_DIAGNOSTICS_API)
    const auto d = i.engine.stageDiagnostics();
    if (d.profileSequence != i.sequence) {
        i.sequence = d.profileSequence; i.profileFrames = frames;
    }
#endif
    return result;
}
void telemetry(void* p, FxTelemetry* out) {
    if (!out || out->size != sizeof(FxTelemetry)) return;
    auto& i = *static_cast<Instance*>(p);
    *out = {}; out->size = sizeof(FxTelemetry);
    out->result = static_cast<int32_t>(i.engine.lastProcessResult());
    out->prepared = i.engine.isPrepared();
    out->quantum_frames = frostsoulx::ImmersiveAudioEngine::kPreferredQuantumFrames;
#if defined(FROSTSOULX_DIAGNOSTICS_API)
    const auto d = i.engine.stageDiagnostics();
    const char* names[] = {"Input sanitization", "Steam Audio HRTF", "Room / reflections / reverb", "Stereo safety limiter"};
    out->stage_count = 4; out->profile_sequence = d.profileSequence;
    out->profile_frames = i.profileFrames;
    for (unsigned s = 0; s < 4; ++s) {
        std::snprintf(out->stages[s].name, sizeof(out->stages[s].name), "%s", names[s]);
        out->stages[s].enabled = (d.activeMask & (1u << s)) != 0;
        out->stages[s].milliseconds = d.milliseconds[s];
    }
#endif
}
const FxApi api{FX_ABI_VERSION, sizeof(FxApi), "FrostSoulX / Steam Audio", create, destroy,
    prepare, reset, controls, process, telemetry};
}
extern "C" const FxApi* frostsoulx_get_api(uint32_t version) {
    return version == FX_ABI_VERSION ? &api : nullptr;
}
