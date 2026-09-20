// Test-only: exposes the C++ bridge core through a C interface so Python ctypes can drive it.
#include <string>
#include <vector>
#include "../../app/src/main/cpp/bridge_core.h"

static std::string g_err;

extern "C" {
void* shim_open(const char* entry, int sr, int ch) {
    std::vector<std::string> none;
    return bridge_open(entry, none, sr, ch, g_err);
}
const char* shim_error() { return g_err.c_str(); }
void shim_set(void* b, const char* id, float v) { bridge_set_param((Bridge*)b, id, v); }
void shim_process(void* b, float* d, int n) { bridge_process((Bridge*)b, d, n); }
void shim_reset(void* b) { bridge_reset((Bridge*)b); }
void shim_close(void* b) { bridge_close((Bridge*)b); }
}
