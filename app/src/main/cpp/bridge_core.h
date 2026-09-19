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
void bridge_process(Bridge* b, float* interleaved, int frames);
void bridge_reset(Bridge* b);
void bridge_close(Bridge* b);
