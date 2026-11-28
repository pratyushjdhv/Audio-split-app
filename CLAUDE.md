# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

AudioSplit: a single-module Android app (`:app`, package `com.audiosplit`) that mirrors whatever
another app is playing to a second output device, so a wired pair and a Bluetooth pair hear the
same thing at once. It captures system playback with `AudioPlaybackCapture` (MediaProjection)
and re-plays it on an `AudioTrack` pinned with `setPreferredDevice()`. The UI is Jetpack Compose
with no ViewModel and no DI. The app has no network code at all, and the README's privacy section
promises that, so keep it that way.

## Build & run

Use `gradlew.bat` on Windows and `./gradlew` elsewhere.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug          # lint config: app/lint.xml
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s MirrorEngine   # one DIAG line per second while mirroring
```

- Requires JDK 17 (source/target) and SDK platform 35. minSdk 29 is a hard floor because
  playback capture arrived in Android 10. targetSdk is 34.
- **There are no tests**, unit or instrumented. Routing and sync behaviour depend on the OEM audio
  policy, so you can only verify them on a real device with both outputs connected. The in-app
  "Test both outputs" dual-tone check is the routing test.
- CI (`.github/workflows/build.yml`) only runs `assembleDebug` and uploads the APK as
  `audiosplit-debug-apk`.
- Release signing reads a git-ignored `keystore.properties` at the repo root (`storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`). Without that file, release builds fall back to the
  debug key, and the result won't install over a published release.
- Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, tag `vX.Y`, and publish a
  GitHub Release with `AudioSplit-<version>.apk`.
- Leave `res/mipmap-anydpi-v26` in place even though lint calls the qualifier obsolete. AAPT2
  needs it for `<adaptive-icon>` (see `app/lint.xml`).

## Architecture

Data flows one way:

1. **Entry points:**
   - The screen: `MainActivity` hosts the permission and consent launchers. `MirrorViewModel`
     owns the device list, selection, tuning and tone test. `ui/MirrorScreen.kt` is stateless
     and takes `MirrorUiState` + `MirrorActions`.
   - The Quick Settings tile: `MirrorTileService`, which uses the transparent
     `StartMirrorActivity` to host the consent dialog.

   Both start sessions through **`MirrorLauncher`**, which resolves the target via
   **`MirrorSettings`** (all SharedPreferences, including per-pair delays) and calls
   `MirrorService.start`.
2. **`MirrorService`** is a foreground service of type `mediaProjection` that owns the
   `MediaProjection` and the engine. All control goes through Intent actions via companion
   helpers:
   - `start` and `stop`.
   - `update`: live delay, gain and autoSync. The ViewModel debounces these by 50 ms.
   - `pause`/`resume`: pause tears down the engine but keeps the projection; resume builds a new
     `MirrorEngine` on the same projection.

   `MirrorNotification` builds the notification with its Pause/Resume and Stop buttons.
3. **`audio/MirrorEngine`** does the work: capture thread → `PcmRing` → playback thread → pinned
   `AudioTrack`.
4. **`MirrorState`** is a process-wide `StateFlow<MirrorStatus>` that the service and engine
   threads write. The ViewModel and the tile read it. It is deliberately not a bound service.
   `running` means a session exists, `paused` means it is held without copying, and
   `sharing = running && !paused`.

Supporting pieces:
- `audio/AudioSpec.kt` defines the one PCM format (48 kHz, stereo, 16-bit) and every sync/timing
  constant. Each constant's KDoc records why it has its value.
- `audio/Pcm.kt` provides peak, gain and the one-frame stretch/squeeze helpers.
- `audio/ToneTester.kt` plays the dual-tone routing test.
- `audio/DefaultOutput.kt` finds where media apps currently play by opening an un-pinned
  `USAGE_MEDIA` track with -90 dBFS dither and reading `routedDevice`.
- `audio/OutputDevices.kt` handles device enumeration and labels.

## Invariants that are easy to break

These invariants came from measurements on real devices, mainly a OnePlus OPD2305 running Android
14. The comments next to them explain the reasoning, so read those before changing anything here.

- **The mirror must never play as `USAGE_MEDIA`.** It uses `AudioSpec.mirrorAttributes()`
  (`USAGE_ASSISTANCE_ACCESSIBILITY`). A pinned media track fights the source app's media track:
  routes get dragged and OEM music hooks rebuild streams many times a second. The pinned tones in
  `ToneTester` must use the same attributes so the test certifies the real configuration.
  `sourceAttributes()` is only for probes that stand in for the source app.
- **The capture thread may only `read()` and append to the ring.** Platform playback-capture
  buffers are fixed at about 64 ms whatever size you request, and they drop audio silently when
  full. Keep all waiting, delay and Bluetooth burstiness in `PcmRing` on the playback side. The
  capture thread doesn't log; it publishes `captureStats`, and the playback thread logs them.
- Capture excludes the app's own UID (`excludeUid(Process.myUid())`). Without it the mirror would
  capture and replay its own output in a feedback loop.
- **Silence means sustained exact zeros.** Capture never stops delivering data. Android feeds zeros
  in real time when nothing plays or when an app blocks capture (Netflix and similar).
- **Sync control has three separate mechanisms:**
  - Slider moves are exact deltas (silence written, or ring bytes skipped), and the drift
    reference shifts by the same amount.
  - Drift is judged only on the ring's average fill over `DRIFT_WINDOW_MS`, and corrected by
    stretching or squeezing a chunk by at most `MAX_STRETCH_FRAMES`. Never cut samples out; that
    clicks.
  - Only an error of `RESYNC_MS` or more gets a one-step cut or pad.

  Never react to single fill readings.
- `MirrorEngine.start()` must never throw, because it runs inside `onStartCommand`. It reports
  failures through `listener.onError`.
- `MirrorEngine.stop()` must unblock every thread (record.stop, ring.close, track.pause/flush),
  join them, and only then release the streams. Releasing earlier is a native use-after-free.
- `MirrorState` mutators must use `update {}`. Two threads write to it, and the routing verdict is
  edge-triggered, so a lost write stays lost.
- `AudioDeviceInfo.id` isn't stable across replugs or reboots. Persist and re-match the selected
  device by type + address (`MirrorSettings.resolveTarget`). Never override an explicit user
  pick: `rememberAutomaticPick` must not overwrite it while that device is disconnected.
- **Every fresh session needs the system consent dialog.** The projection token can't be reused.
  That's why stopping and restarting can't happen silently, and why pause keeps the projection
  instead. Controls sent with no live projection must `stopSelf()`, not leave an idle started
  service.
- `MirrorState.resumed()` must run before the new engine starts. It wipes per-engine status, and
  routing is reported only once per engine.
- `MirrorSettings` keeps the pre-1.4 SharedPreferences key names so updates keep users'
  settings. It migrates the old single `delay_ms` into the per-pair key once.
- `DIAG`-tagged log lines are diagnostics only. Nothing may feed them back into the audio path.
