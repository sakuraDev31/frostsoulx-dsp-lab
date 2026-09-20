#!/usr/bin/env python3
"""Host-side checks for the reference engine and the bridge core (no Android needed).
Run: reference-engine/tools/run_host_tests.sh"""
import ctypes, json, math, os, sys
import numpy as np

build = sys.argv[1]
eng = ctypes.CDLL(os.path.join(build, "libref_engine.so"))
shim = ctypes.CDLL(os.path.join(build, "libbridge_shim.so"))
FS = 44100
D = max(2, round(0.0015 * FS))          # limiter look-ahead
LAT = D - 1

eng.ae_create.restype = ctypes.c_void_p
eng.ae_create.argtypes = [ctypes.c_int, ctypes.c_int]
eng.ae_destroy.argtypes = [ctypes.c_void_p]
eng.ae_set_param.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_float]
eng.ae_process.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
eng.ae_reset.argtypes = [ctypes.c_void_p]
eng.ae_abi_version.restype = ctypes.c_int

fails = []
def check(name, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + name + (f"  [{detail}]" if detail else ""))
    if not cond: fails.append(name)

def run(params, x, block=512, fs=FS):
    """x: (n,2) float32. Returns processed (n,2)."""
    h = eng.ae_create(fs, 2)
    for k, v in params.items(): eng.ae_set_param(h, k.encode(), float(v))
    y = np.ascontiguousarray(x, dtype=np.float32).copy()
    n = len(y)
    for i in range(0, n, block):
        chunk = np.ascontiguousarray(y[i:i + block])
        eng.ae_process(h, chunk.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), len(chunk))
        y[i:i + block] = chunk
    eng.ae_destroy(h)
    return y

def sine(f, amp=0.1, secs=1.0, stereo=(1, 1)):
    t = np.arange(int(FS * secs)) / FS
    s = amp * np.sin(2 * np.pi * f * t)
    return np.stack([s * stereo[0], s * stereo[1]], axis=1).astype(np.float32)

def rms(x): return float(np.sqrt(np.mean(np.square(x.astype(np.float64)))))
def tail(x): return x[len(x) // 2:]            # settled part

# --- ABI ---
check("abi version is 1", eng.ae_abi_version() == 1)
check("rejects mono", eng.ae_create(FS, 1) is None)
check("rejects silly rate", eng.ae_create(100, 2) is None)

# --- flat = identity delayed by limiter look-ahead ---
x = sine(440, 0.3)
y = run({}, x)
err = np.max(np.abs(y[LAT:LAT + 20000] - x[:20000]))
check("flat is transparent (delayed by look-ahead)", err < 1e-3, f"max err {err:.2e}, latency {LAT}")

# --- tone shelves ---
base = rms(tail(run({}, sine(60))))
bass = rms(tail(run({"bass": 12}, sine(60))))
check("bass +12 dB boosts 60 Hz", bass / base > 2.5, f"x{bass/base:.2f}")
mid = rms(tail(run({"bass": 12}, sine(1000)))) / rms(tail(run({}, sine(1000))))
check("bass +12 dB leaves 1 kHz alone", 0.85 < mid < 1.2, f"x{mid:.2f}")
tr = rms(tail(run({"treble": 12}, sine(12000)))) / rms(tail(run({}, sine(12000))))
check("treble +12 dB boosts 12 kHz", tr > 3.0, f"x{tr:.2f}")
cut = rms(tail(run({"bass": -12}, sine(60)))) / base
check("bass -12 dB cuts 60 Hz", cut < 0.4, f"x{cut:.2f}")

# --- width ---
rng = np.random.default_rng(1)
noise = (rng.standard_normal((FS, 2)) * 0.1).astype(np.float32)
y0 = tail(run({"width": 0}, noise))
check("width 0 gives mono", np.max(np.abs(y0[:, 0] - y0[:, 1])) < 1e-3)
side = sine(500, 0.1, stereo=(1, -1))
w2 = rms(tail(run({"width": 2}, side))) / rms(tail(run({"width": 1}, side)))
check("width 2 doubles side content", 1.8 < w2 < 2.2, f"x{w2:.2f}")

# --- gain ---
g = rms(tail(run({"gain": -6}, sine(1000)))) / rms(tail(run({}, sine(1000))))
check("gain -6 dB halves level", abs(g - 0.501) < 0.02, f"x{g:.3f}")

# --- crossfeed ---
left = sine(300, 0.2, stereo=(1, 0))
r0 = rms(tail(run({"crossfeed": 0}, left))[:, 1])
r1 = rms(tail(run({"crossfeed": 1}, left))[:, 1])
check("crossfeed feeds the other ear", r0 < 1e-4 and r1 > 0.02, f"R rms {r0:.5f} -> {r1:.4f}")

# --- reverb ---
burst = np.zeros((FS * 2, 2), np.float32); burst[:2000] = (rng.standard_normal((2000, 2)) * 0.3)
dry_tail = rms(run({"reverb_mix": 0}, burst)[FS:])
wet = run({"reverb_mix": 0.6, "reverb_room": 0.8}, burst)
check("reverb off leaves silence after burst", dry_tail < 1e-6, f"{dry_tail:.2e}")
check("reverb on rings after burst", rms(wet[FS // 4:FS // 2]) > 1e-3, f"{rms(wet[FS//4:FS//2]):.4f}")
check("reverb decays", rms(wet[int(1.5 * FS):]) < rms(wet[FS // 4:FS // 2]))
small = rms(run({"reverb_mix": 0.6, "reverb_room": 0.0}, burst)[FS:])
big = rms(run({"reverb_mix": 0.6, "reverb_room": 1.0}, burst)[FS:])
check("bigger room rings longer", big > small * 2, f"{small:.5f} vs {big:.5f}")

# --- limiter ---
hot = sine(1000, 0.9)
lim_on = run({"gain": 6, "limiter": 1}, hot)
lim_off = run({"gain": 6, "limiter": 0}, hot)
check("limiter caps peaks at 0.98", np.max(np.abs(lim_on)) <= 0.9801, f"peak {np.max(np.abs(lim_on)):.4f}")
check("limiter off passes >1.0", np.max(np.abs(lim_off)) > 1.5, f"peak {np.max(np.abs(lim_off)):.3f}")
sq = np.tile(np.concatenate([np.ones(30), -np.ones(30)]), 400)[:, None] * np.array([[1, 1]]) * 0.95
lim_sq = run({"gain": 6}, sq.astype(np.float32))
check("limiter holds on square-wave bursts", np.max(np.abs(lim_sq)) <= 0.9801, f"peak {np.max(np.abs(lim_sq)):.4f}")

# --- optional partitioned convolution ---
impulse = np.zeros((FS, 2), np.float32); impulse[0] = (0.4, 0.2)
conv_off = run({"convolution_mix": 0}, impulse)
conv_on = run({"convolution_mix": 1}, impulse)
check("convolution bypass is transparent", np.max(np.abs(conv_off - run({}, impulse))) < 1e-6)
check("convolution mix produces an IR tail", rms(conv_on[300:2000]) > 1e-4, f"tail rms {rms(conv_on[300:2000]):.4f}")
conv_damped = run({"convolution_mix": 1, "convolution_damping": 1}, impulse)
check("convolution damping changes output", rms(conv_damped - conv_on) > 1e-5)

# --- robustness ---
extreme = {"bass": 12, "treble": 12, "width": 2, "crossfeed": 1, "reverb_mix": 1, "reverb_room": 1, "convolution_mix": 1, "convolution_predelay": 1, "convolution_damping": 1, "gain": 6}
for blk in (1, 7, 64, 4096):
    y = run(extreme, noise[:8000], block=blk)
    check(f"no NaN/inf, extreme params, block {blk}", bool(np.all(np.isfinite(y))) and np.max(np.abs(y)) <= 0.9801)
for fs in (8000, 22050, 48000, 96000):
    y = run({"reverb_mix": 0.3, "bass": 5}, (rng.standard_normal((fs // 2, 2)) * 0.1).astype(np.float32), fs=fs)
    check(f"works at {fs} Hz", bool(np.all(np.isfinite(y))))

h = eng.ae_create(FS, 2)
eng.ae_set_param(h, b"nonsense", 5.0); eng.ae_set_param(h, None, 1.0); eng.ae_set_param(h, b"gain", float("nan"))
buf = np.zeros((256, 2), np.float32)
eng.ae_process(h, buf.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 256)
check("unknown / null / NaN params are ignored", bool(np.all(buf == 0)))
# reset clears reverb tail
eng.ae_set_param(h, b"reverb_mix", 1.0)
b = burst[:4096].copy(); eng.ae_process(h, b.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 4096)
eng.ae_reset(h)
z = np.zeros((8192, 2), np.float32); eng.ae_process(h, z.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 8192)
check("reset clears tails", float(np.max(np.abs(z))) < 1e-6)
eng.ae_destroy(h)

# --- manifest sanity (mirrors what the Kotlin parser requires) ---
mp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "manifest.json")
m = json.load(open(mp))
ids = [p["id"] for p in m["params"]]
check("manifest: ids unique", len(ids) == len(set(ids)))
check("manifest: api == 1 and entry set", m["api"] == 1 and m["entry"].endswith(".so"))
ok = True
for p in m["params"]:
    if p.get("type", "slider") == "slider":
        ok &= p["min"] < p["max"] and p["min"] <= p["default"] <= p["max"]
check("manifest: slider ranges valid", ok)
check("manifest: preset ids all exist", all(k in ids for pr in m["presets"] for k in pr["values"]))

# every manifest param really changes the sound (proves the controls are wired to DSP)
probe = (rng.standard_normal((FS, 2)) * 0.08).astype(np.float32)
probe[:, 1] *= 0.5
defaults = {p["id"]: p["default"] for p in m["params"]}
needs = {
    "reverb_room": {"reverb_mix": 0.5},
    "convolution_predelay": {"convolution_mix": 0.7},
    "convolution_damping": {"convolution_mix": 0.7},
}    # wet-only parameters need their path enabled
for p in m["params"]:
    if p["id"] == "limiter": continue           # only audible when clipping
    base_p = {**defaults, **needs.get(p["id"], {})}
    ref = run(base_p, probe)
    alt = p["max"] if p["default"] != p["max"] else p["min"]
    y = run({**base_p, p["id"]: alt}, probe)
    d = rms(y - ref)
    check(f"param '{p['id']}' audibly changes output", d > 1e-3, f"diff rms {d:.4f}")

# --- bridge core (dlopen path) matches direct calls ---
shim.shim_open.restype = ctypes.c_void_p
shim.shim_open.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
shim.shim_error.restype = ctypes.c_char_p
shim.shim_set.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_float]
shim.shim_process.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float), ctypes.c_int]
shim.shim_reset.argtypes = [ctypes.c_void_p]
shim.shim_close.argtypes = [ctypes.c_void_p]

so = os.path.join(build, "libref_engine.so").encode()
b = shim.shim_open(so, FS, 2)
check("bridge opens engine via dlopen", bool(b), (shim.shim_error() or b"").decode())
for k, v in {"bass": 6, "width": 1.5, "reverb_mix": 0.2}.items(): shim.shim_set(b, k.encode(), v)
x = (rng.standard_normal((4000, 2)) * 0.1).astype(np.float32)
yb = x.copy()
for i in range(0, 4000, 500):
    c = np.ascontiguousarray(yb[i:i + 500]); shim.shim_process(b, c.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), 500); yb[i:i + 500] = c
yd = run({"bass": 6, "width": 1.5, "reverb_mix": 0.2}, x, block=500)
check("bridge output == direct output", np.max(np.abs(yb - yd)) < 1e-6)
shim.shim_reset(b); shim.shim_close(b)
check("bridge rejects missing file", not shim.shim_open(b"/nonexistent/lib.so", FS, 2))
print("   bridge error text:", shim.shim_error().decode())
check("bridge rejects unsupported format", not shim.shim_open(so, FS, 1))
print("   bridge error text:", shim.shim_error().decode())
check("bridge rejects non-engine library", not shim.shim_open(b"libm.so.6", FS, 2))
print("   bridge error text:", shim.shim_error().decode())

print()
print("ALL PASSED" if not fails else f"{len(fails)} FAILED: {fails}")
sys.exit(1 if fails else 0)
