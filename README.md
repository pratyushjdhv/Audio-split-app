# AudioSplit

Play something on one Android tablet, hear it on **two** headphones at once — one wired,
one Bluetooth. Built for watching a movie with someone on a bus.

Android has no built-in equivalent of Nahimic's dual-output feature, and Samsung's "Dual
Audio" only pairs two *Bluetooth* devices. This app takes a different route.

## How it works

It is not a media player. It's a **system audio mirror**:

1. You start the movie in whatever app you normally use — Vivaldi, Stremio, a local
   video/music player.
2. That app plays to whatever Android picked as the default output. With a Bluetooth
   headset connected, that's the Bluetooth headset.
3. AudioSplit uses `AudioPlaybackCapture` to grab the same PCM stream the system is
   playing, and writes a second copy to an `AudioTrack` pinned to the wired dongle via
   `AudioTrack.setPreferredDevice()`.

Result: two people, two headphones, one video, no extra hardware.

## What works and what doesn't

**Works:** browsers (Vivaldi, Chrome), Stremio, local video and MP3 players, most games
and podcast apps — anything that doesn't explicitly opt out of audio capture.

**Doesn't work:** Netflix, Prime Video, Disney+ and other DRM-protected players. They set
`ALLOW_CAPTURE_BY_NONE`, so the OS hands us silence. This is enforced below the app layer
and there is no way around it short of a rooted device. The level meter on the main screen
tells you immediately which case you're in: if it's flat while something is playing, that
app is blocking capture.

**The one real unknown:** `setPreferredDevice()` is a *request*. Whether a given ROM
honours it for a device that isn't the current default is up to that ROM's audio policy,
and OnePlus/Oppo/Xiaomi ROMs are not always cooperative. That's why there's a routing test
built into step 2 — run it before trusting the app with movie night.

## First run

1. Connect both headphones (Bluetooth paired and connected, dongle plugged in).
2. Open AudioSplit. Pick the output that is **not** currently getting sound — normally the
   wired/USB-C one, since Bluetooth wins the default.
3. Hit **Test both outputs**. You should hear a low tone in one pair and a high tone in the
   other, simultaneously. The app also prints what the system actually did with each
   request.
   - Two different tones in two different ears → you're good.
   - Both tones in the same ear, or the app reports `OVERRIDDEN` → this ROM blocks
     per-track routing. See *If routing is blocked* below.
4. Start playback in your video app, come back, hit **Start sharing**, and grant the
   capture permission.
5. Slide **Delay** until the two of you hear the same thing at the same time.

## The delay slider

Bluetooth A2DP runs roughly 150–250 ms behind a wired connection. Video apps already
compensate their picture for that, which means the wired copy arrives *early* and needs to
be held back. That's what the slider does, and it's adjustable while playing because the
only way to get it right is to nudge it until it sounds right. Start around 180 ms.

The separate volume slider exists because the hardware volume buttons move both outputs
together — this one balances one pair against the other.

## If routing is blocked

If the tone test shows `OVERRIDDEN`, the fallback is to stream the mirrored audio over a
local hotspot to the second person's phone, which plays it to their own earphones. That
isn't built yet — it's only worth writing if the direct path actually fails, so run the
test first.

## Building

CI builds a debug APK on every push: **Actions → Build APK → the newest run → download
`audiosplit-debug-apk`**. Unzip it on the tablet and install (you'll need "install unknown
apps" enabled for your file manager).

Locally:

```bash
./gradlew assembleDebug   # or: gradle assembleDebug
```

Requires JDK 17 and an Android SDK with platform 35. minSdk is 29, because
`AudioPlaybackCapture` landed in Android 10.

## Layout

| Path | What's in it |
| --- | --- |
| `audio/AudioSpec.kt` | The single PCM format everything speaks (48 kHz stereo 16-bit) |
| `audio/OutputDevices.kt` | Enumerating and labelling output devices |
| `audio/MirrorEngine.kt` | Capture → delay → gain → routed output. The core loop. |
| `audio/ToneTester.kt` | The dual-tone routing test |
| `MirrorService.kt` | Foreground service holding the projection and the engine |
| `MainActivity.kt` | The single screen |

## Privacy

Android asks for screen-capture permission because that's the same permission that gates
audio capture; the app never creates a virtual display and never looks at the screen.
Nothing is recorded and nothing leaves the device. There is no network code in it at all.
