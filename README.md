# AudioSplit

**Watch one thing on one Android tablet, hear it on two headphones at once.**
One wired pair, one Bluetooth pair. Built for watching a film with someone on a bus.

---

## How to use it

### Before you start

You need three things plugged in and working:

1. Your **Bluetooth headphones** — paired and connected
2. The **wired earphones** — plugged into the USB-C dongle, dongle in the tablet
3. The **video or music app** you want to watch in — browser, Stremio, a local player

> Check you can hear sound normally first. If nothing is playing, AudioSplit has nothing
> to copy.

---

### Step 1 — Pick where the second copy goes

Open AudioSplit. Under **Headphones → Send a copy to** you'll see every output the tablet
can see.

**Pick the pair that is NOT currently getting sound.**

Which one that is depends on your tablet. Android picks one output as the default and
sends everything there; AudioSplit copies the sound to the *other* one. So:

- If your video is already coming out of the **Bluetooth** headphones → pick the **wired** one
- If your video is already coming out of the **wired** earphones → pick the **Bluetooth** one

Not sure which? Just above the list, **Your video plays to** shows where the sound is going
right now. Pick the other one. If you pick the same one, the app warns you.

---

### Step 2 — Test that your tablet can actually do this (do this once)

In the **One-time check** card, tap **Play test tones (6 s)**. (Once it has passed, the test
moves into **Advanced** so it stays out of your way.)

You should hear, at the same time:

| Where | What you should hear |
| --- | --- |
| The output you picked | a **low** tone |
| The other headphones | a **high** tone |

**Two different tones in two different ears = it works.** You're good forever; you never
need to run this again.

**If both tones come out of the same ear**, or a line in red says the sound "went to"
somewhere else, your tablet's software is refusing to send audio to two places at once.
Nothing in the app can fix that — see [If the test fails](#if-the-test-fails) below.

---

### Step 3 — Start your video

Go to your browser / Stremio / player and **start playing** something. Get the sound going
first.

---

### Step 4 — Hit Start sharing

Come back to AudioSplit and tap **Start sharing**.

Android will ask for two permissions the first time:

- **Microphone** — Android requires this for *any* audio capture. The app never opens the
  mic. Say yes.
- **Screen capture** — this is the permission that covers recording audio too. Nothing on
  your screen is recorded or sent anywhere. Say yes.

Both people should now hear the film.

---

### Step 5 — Line up the sound

Bluetooth is slower than a wire — sound reaches Bluetooth ears roughly a fifth of a second
late. The delay slider holds the mirrored copy back so the two line up.

**Adjust "Delay on the copy" until you both hear the same thing at the same moment.** Drag
the slider for big moves and tap **−** / **+** for 5 ms nudges. It updates live while the
film plays, so just nudge it until it sounds right.

Where to start depends on which pair you're mirroring to:

| Mirroring to | Try around |
| --- | --- |
| The **wired** earphones | **180 ms** — the Bluetooth side is already running late, so the wired copy waits for it |
| The **Bluetooth** headphones | **0 ms** — Bluetooth is already the slow one; adding more would make it worse |

The delay only needs setting once per pair of headphones — the app remembers it for each
pair separately.

There's also a **Copy volume** slider. Use it if one of you needs it louder than the
other. (The tablet's volume buttons move *both* pairs together, which is why this exists
separately.)

---

### Pausing, and controlling it without opening the app

- **Pause** stops the copy but keeps the session. **Resume** picks up straight away, with no
  permission prompt.
- The **notification** has **Pause/Resume** and **Stop** buttons, so you never have to leave
  your video.
- Add the **AudioSplit Quick Settings tile** (Advanced → **Add to Quick Settings**, or drag it
  in from the Quick Settings edit screen). Then, from the pull-down shade:
  - when sharing is off, tap it and the screen-capture prompt appears over your video;
    accept it and sharing starts
  - when sharing, tap it to pause; tap again to resume
  - long-press it to open the app

Android asks for screen-capture permission every time a new session starts, and no app can
skip that. Pausing instead of stopping avoids it.

---

### When you're done

Tap **Stop**, either in the app or on the notification.

---

## Quick troubleshooting

| What you see | What it means |
| --- | --- |
| The level bar is flat while a video plays | That app blocks audio copying. Netflix, Prime Video and Disney+ all do this on purpose and there's no way around it. Use the browser, Stremio, or a local file. |
| Sound is out of sync | Adjust the delay (Step 5). |
| One side is too quiet | Use the volume slider, not the volume buttons. |
| Red "sent the copy to … instead" message | Your tablet refused to split the audio. See below. |
| Both tones in the same ear during the test | Same thing — see below. |
| Second pair went silent mid-film | Check the dongle didn't get knocked loose, then Stop and Start again. |

---

## What works and what doesn't

**Works:** browsers (Vivaldi, Chrome), Stremio, local video files, MP3s, podcasts, games —
basically anything that doesn't deliberately block audio copying.

**Doesn't work:** Netflix, Prime Video, Disney+ and other paid streaming apps. They switch
audio copying off at the system level for copyright reasons. This is enforced below the
app, so no app can get around it. The level bar on the main screen tells you instantly:
flat bar while something is playing means that app is blocking it.

---

## If the test fails

The app asks Android to send one sound stream to a specific pair of headphones. That's a
*request*, and each manufacturer's software decides whether to honour it — OnePlus, Oppo
and Xiaomi builds are not always cooperative. There's no way to know without testing,
which is why the test is Step 2 rather than buried in a menu.

If your tablet refuses, the fallback is to send the second audio stream over a local
hotspot to the other person's phone, which plays it to their own earphones. That isn't
built yet — it's a fair amount of work and it's only worth doing if the direct route
actually fails, so run the test first.

---

## Installing it

**Easiest:** on the phone or tablet, open the
[latest release](https://github.com/pratyushjdhv/Audio-split-app/releases/latest), download
`AudioSplit-<version>.apk` and open it. Android asks once to allow installing apps from
your browser or file manager — allow it.

- Works on **Android 10 or newer**, phones and tablets alike. Android 10 is a hard floor:
  the system feature that lets one app copy another's sound arrived in that version.
- One APK for every device: it carries every processor type Android runs on (64- and
  32-bit ARM, 64- and 32-bit x86).
- Coming from **1.1 or 1.2**: uninstall AudioSplit once, then install. Releases are signed
  with a dedicated key from 1.3 on, and Android won't update across a key change. After
  that, new releases install straight over the old one and keep your settings.
- If Android says the package conflicts with an existing one, you have a build signed
  differently (for example from GitHub Actions). Uninstall AudioSplit, then install again.

**Latest development build:** **Actions** tab → **Build APK** → newest run → download
`audiosplit-debug-apk`, unzip it and open the APK.

> Actions has to be switched on for the repo first:
> **Settings → Actions → General → Allow all actions**, then push anything.

**Building it yourself:**

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and an Android SDK with platform 35. Release builds are signed with the key
named in a git-ignored `keystore.properties` at the project root (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`); without that file they fall back to the debug
key, which installs fine but won't update a published release.

---

## Privacy

No network code exists in this app at all. Nothing is recorded, nothing is stored, nothing
leaves the tablet. The screen-capture permission is only there because Android bundles
audio capture under it; the app never creates a screen capture and never looks at your
display.

---

## For developers

<details>
<summary>How it actually works, and where the code lives</summary>

It isn't a media player. It's a **system audio mirror**:

1. The source app plays normally, to whatever Android picked as the default output.
2. AudioSplit captures the same PCM stream via `AudioPlaybackCapture` (MediaProjection).
3. It writes a second copy to an `AudioTrack` pinned to a chosen device with
   `setPreferredDevice()`.

Capture and playback run on separate threads either side of a ring buffer. That split is
load-bearing, for a reason that's easy to miss: **for playback capture Android ignores
the buffer size you ask for** and allocates its minimum (measured: 250 ms requested,
64 ms granted), then silently drops audio when that fills. A single-threaded relay parks
the delay and every Bluetooth burst in that tiny buffer, which overflows constantly —
choppy audio. With the ring, the capture thread only reads and appends, so the platform
buffer stays empty and the delay lives in app memory.

Easiest of all to miss: **the mirror must not play as media.** A pinned media track and
the source app's media track fight. Android routes the whole media strategy to a pinned
device while every active media client is pinned, and OEM policies hook every
music-stream start in ways that tear the other output down. On a OnePlus tablet the
mirror and VLC rebuilt each other's streams nine times a second, which sounds like
"a-a-a-a" on both pairs and is why starting the mirror before the video used to break
it. The mirror plays as `USAGE_ASSISTANCE_ACCESSIBILITY`, the one usage with its own
routing strategy; see `AudioSpec.mirrorAttributes()`.

Also easy to miss: capture never goes quiet by returning no data. With nothing playing,
or with an app that blocks capture, Android feeds exact zeros in real time. "Nothing is
coming through" can only be detected as sustained digital silence.

The two ends are clocked by different crystals — the system mixer on one side, the USB
DAC or Bluetooth stack on the other — and a few dozen ppm is enough to drift a two-hour
film out of sync if nothing closes the loop. Three separate mechanisms, because the three
problems have different shapes:

- **Slider moves** apply as exact deltas — silence written to the output, or audio
  skipped from the ring — and shift the drift reference by exactly the same amount, so a
  slider move is never mistaken for drift.
- **Drift** is judged only on the ring's *average* fill over 10-second windows, never on
  single readings: fill swings by tens of ms on every A2DP burst and capture chunk.
  Outside a 15 ms band it is corrected by resampling a chunk by one or two frames (a 0.1%
  speed change for 21 ms), never by cutting samples out, which clicks.
- **Start-up backlog** — audio that piles up while an output is slow to start draining,
  and then never leaves — is squeezed out the same inaudible way once, when the drift
  reference is taken, instead of becoming permanent latency.
- **Real breaks** — fill 200 ms off from where it belongs, either way — are fixed in one
  step: the excess cut, or a drained delay put back as silence, so sync is right
  immediately.

| Path | What's in it |
| --- | --- |
| `audio/AudioSpec.kt` | PCM format and the sync constants, with the reasoning behind each |
| `audio/PcmRing.kt` | Ring buffer between the two audio threads |
| `audio/Pcm.kt` | Peak, gain, and the one-frame stretch used for drift correction |
| `audio/MirrorEngine.kt` | Capture → ring → delay → gain → routed output. The core. |
| `audio/OutputDevices.kt` | Device enumeration and labelling |
| `audio/DefaultOutput.kt` | Finds where the source app's audio is going right now |
| `audio/ToneTester.kt` | The dual-tone routing test |
| `MirrorService.kt` | Foreground service owning the MediaProjection; start, pause, resume, stop |
| `MirrorNotification.kt` | The notification and its Pause/Resume and Stop buttons |
| `MirrorTileService.kt` | The Quick Settings tile |
| `StartMirrorActivity.kt` | Invisible activity that shows the consent prompt when the tile starts a session |
| `MirrorLauncher.kt` | The one start path, shared by the screen and the tile |
| `MirrorSettings.kt` | Saved settings, per-pair delays, and which device the copy goes to |
| `MirrorState.kt` | StateFlow shared service → UI and tile |
| `MirrorViewModel.kt` | State for the screen |
| `ui/MirrorScreen.kt` | The single screen |

Every `MirrorEngine` log line tagged `DIAG` is diagnostics only and never feeds back into
the audio. `adb logcat -s MirrorEngine` gives one line per second covering capture
(platform backlog), the ring (fill, overflow), sync (drift, corrections, resyncs) and
output (underruns), all logged from the playback thread so capture never waits on logd.

</details>
