# FrostSoulX DSP Lab

A tiny Android playground for testing the FrostSoulX native audio engine without rebuilding the full music app.

## What it does

- Scans device audio through `MediaStore.Audio.Media`.
- Stores stable `content://` URIs in app preferences so discovered files survive rescans and reboots.
- Plays local files through Media3 ExoPlayer.
- Routes float stereo PCM through a `Media3 AudioProcessor` and the imported native engine.
- Exposes every current public engine control: enable, intensity, room preset, room mix, reflection amount, reverb time, room size, dampening, and stereo width.
- Shows native status, input/output RMS, peaks, process calls, and result code.
- Imports an engine ZIP through the system document picker and stages it for the next native build.
- Supports Media3 hardware offload preferences, while explicitly bypassing the custom processor when offload is requested.

## Important offload rule

Hardware audio offload and a custom PCM processor are mutually exclusive. When offload is enabled, the lab requests Media3 offload and disables the custom processor. When DSP is enabled, the lab disables offload so the PCM engine can actually receive samples. The UI reports this state instead of claiming that both paths are active.

## Engine import workflow

The default engine source is under:

```text
app/src/main/cpp/engine/
```

It is copied from `sakuraDev31/frostsoulx-audio-engine`. To replace it with a new source bundle:

```bash
./tools/import-engine-bundle.sh path/to/frostsoulx-audio-engine.zip
./gradlew :app:assembleDebug --no-daemon
```

The in-app ZIP picker persists the selected URI and marks it as staged. Native code cannot be hot-swapped safely inside a running APK; a rebuild is required to activate an imported engine. This is intentional and prevents ABI/library races.

## Build

The sandbox build needs Android SDK, NDK `28.2.13676358`, and CMake. Then:

```bash
printf 'sdk.dir=/absolute/path/to/android-sdk\n' > local.properties
./gradlew :app:assembleDebug --no-daemon
```

The build artifact is:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Research decisions

Android’s `MediaStore` is used for device audio discovery because it returns indexed media and stable content URIs. The app retains URI grants and the URI strings rather than assuming raw filesystem paths. The custom processor accepts only two-channel float PCM, which keeps the test path explicit and avoids silently processing unsupported formats. Hardware offload is controlled through Media3’s `AudioOffloadPreferences`; because offload bypasses `AudioProcessor` chains on supported devices, the two modes are mutually exclusive.

## Scope

This is a test harness, not a production player. The native processor is expected to remain allocation-free in its callback. The JNI direct-buffer path is used for playback; diagnostics are intentionally lightweight and are not part of the FrostSoulX production adapter.
