// Loads an engine plugin (see engine-sdk/ae_plugin.h) with dlopen and forwards calls to it.
// Plain C++ so it can be tested on a desktop without JNI.
#pragma once

#include <string>
#include <vector>

struct Bridge;

// Loads `preload` libraries in order (so the entry library's DT_NEEDED entries resolve by
// soname), then the entry library, then creates one engine instance.
// Returns nullptr and fills `error` on failure.
Bridge* bridge_open(const std::string& entryPath,
                    const std::vector<std::string>& preload,
                    int sampleRate, int channels, std::string& error);

void bridge_set_param(Bridge* b, const char* id, float value);

// Calls the engine once with the whole block (no re-blocking).
void bridge_process(Bridge* b, float* interleaved, int frames);

void bridge_reset(Bridge* b);
void bridge_close(Bridge* b);

// --- internal DSP processing quantum -------------------------------------------------------
//
// The host hands us whatever block size the audio sink uses. bridge_set_quantum makes the
// bridge re-block that stream so ae_process() always sees exactly `quantumFrames` frames.
// This allocates, so it must be called off the realtime path (engine open / reconfigure).
//
//   quantumFrames <= 0  -> follow the host block size, no FIFO, zero added latency (AUTO)
//   quantumFrames  > 0  -> fixed quantum, added latency == quantumFrames
//
// Returns false if the buffers could not be allocated (the bridge then stays in AUTO).
bool bridge_set_quantum(Bridge* b, int quantumFrames, int maxHostFrames);

int bridge_quantum(const Bridge* b);          // 0 when following the host block size
int bridge_latency_frames(const Bridge* b);   // frames of latency added by the FIFO

// Re-blocks to the configured quantum, in place. Falls back to bridge_process when the
// quantum is AUTO or equals `frames`, or when `frames` exceeds the configured maximum.
// Realtime safe: never allocates.
//
// Returns the number of times ae_process() was actually invoked for this call. In AUTO that
// is always 1; with a quantum it depends on how the host block lines up with the FIFO, so it
// is reported rather than guessed (the caller needs it to attribute processing time).
int bridge_process_quantized(Bridge* b, float* interleaved, int frames);

// Clears the FIFO (call on seek / track change together with bridge_reset).
void bridge_flush_fifo(Bridge* b);

// Frames the FIFO had to fill with silence because the engine had not produced them yet.
// Monotonic counter, written only by the audio thread, read by the JNI layer right after
// bridge_process_quantized() returns. A non-zero value is a real, measured dropout.
unsigned int bridge_fifo_underflow_frames(const Bridge* b);
