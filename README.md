# Resonance

A small Android music player with a **sound engine you import as a zip**.

- Scans music on the device (MediaStore) and saves every song's path in `library.json`, so the library is there on the next launch. A quick rescan runs at startup.
- Plays in the background (Media3 `MediaSessionService`, notification controls).
- **Playing** tab: cover art, seek/transport, and an orb that pulses with the real output level (left channel Tide, right channel Rose).
- **Sound** tab: import an engine zip; every control the engine declares shows up as a fader / switch / choice, plus its presets. Nothing in the UI is hard-coded to one engine.
- Engine audio runs inside ExoPlayer's audio processor chain, on the audio thread.

## Build (no laptop needed)

1. Push this folder to a GitHub repo.
2. GitHub Actions (`.github/workflows/build.yml`) builds two artifacts:
   - `resonance-debug-apk` - the app (`app-debug.apk`)
   - `reference-engine` - `reference-engine.zip`, a working engine to import
3. GitHub downloads artifacts as a zip that contains your file. The app accepts that too: if the zip you pick has no `manifest.json` but contains one `.zip`, it unpacks that one.

Gradle is pinned to 8.9 in the workflow (AGP 8.7.3, Kotlin 2.0.21, Media3 1.4.1, minSdk 29).

## Engine zip format

```
my-engine.zip
  manifest.json
  lib/arm64-v8a/libmyengine.so        <- the engine (entry)
  lib/arm64-v8a/libphonon.so          <- optional extra libs it depends on
  lib/x86_64/...                      <- optional
```

`manifest.json`:

```json
{
  "id": "my.engine",              // letters, digits . _ -
  "name": "My Engine",
  "version": "1.0.0",
  "api": 1,
  "description": "shown on the Sound tab",
  "entry": "libmyengine.so",
  "preload": ["libphonon.so"],    // optional; loaded first, in this order
  "params": [
    { "id": "bass", "label": "Bass", "group": "Tone", "type": "slider",
      "min": -12, "max": 12, "default": 0, "step": 0.5, "unit": "dB" },
    { "id": "limiter", "label": "Limiter", "group": "Output", "type": "toggle", "default": 1 },
    { "id": "room", "label": "Room", "group": "Space", "type": "choice",
      "options": ["Off", "Small", "Hall"], "default": 0 }
  ],
  "presets": [
    { "name": "Warm", "values": { "bass": 4, "room": 1 } }
  ]
}
```

- `slider` passes its real value, `toggle` passes 0 or 1, `choice` passes the option index.
- Double-tap a fader to reset it to `default`.
- Only the library for the phone's own ABI is extracted. `arm64-v8a` covers modern phones.

## Engine C ABI (`engine-sdk/ae_plugin.h`)

```c
int        ae_abi_version(void);                              // return 1
AeEngine*  ae_create(int sample_rate, int channels);          // channels is 2; NULL = unsupported
void       ae_destroy(AeEngine*);
void       ae_set_param(AeEngine*, const char* id, float v);  // ignore unknown ids
void       ae_process(AeEngine*, float* interleaved, int frames);  // in place, stereo float
void       ae_reset(AeEngine*);                               // on seek / track change
```

All calls for one instance come from the audio thread, so the engine needs no locking. The app clamps output to [-1, 1] and replaces NaN with 0.

### Wrapping an existing engine (e.g. the Steam Audio one)

Write a thin shim `.so` that exports these six functions and forwards to your engine, then list `libphonon.so` under `preload`. The bridge `dlopen`s preload libraries first so the shim's dependency on `libphonon.so` resolves. Keep `ae_process` allocation-free and keep block sizes small: a slow `ae_process` stalls ExoPlayer's playback thread (play/pause and seek will lag).

## Reference engine

`reference-engine/` is plain C++17: bass/treble shelves, mid/side width, headphone crossfeed, a small reverb, output gain and a look-ahead limiter (no clipping from stacked effects). Each of its 8 parameters is in its manifest.

Host tests (no Android needed, needs g++, python3, numpy):

```
reference-engine/tools/run_host_tests.sh
```

They build the engine plus the bridge core, load the engine through `dlopen` exactly as the app does, and check filter gains, width, crossfeed, reverb, limiter ceiling, NaN safety at extreme settings and odd block sizes/sample rates, and that every manifest parameter audibly changes the output.

## What is not verified

The Kotlin/Compose app and the JNI glue have not been compiled or run: the environment this was written in had no Android SDK. The engine, the bridge core and the manifest were tested on a host (see above). Expect to fix a few compile errors on the first CI run; the first place to look is `PlaybackService.kt` if you change the Media3 version, since Media3's audio APIs shift between releases.

Also untested on a real device: loading a `.so` from app-private storage with `dlopen` (should work; it is how plugin `.so` files are normally loaded), and playback with the engine on gapless track changes.

## Known limits

- Stereo PCM16 / float only. Other formats bypass the engine.
- The engine instance is created on the first audio buffer (and again when the sample rate changes). An engine with slow start-up will delay the start of playback slightly.
- No queue editing, playlists or tag editing. Library is a flat list with search.
