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

Open AudioSplit. You'll see a list of every output the tablet can see.

**Pick the pair that is NOT currently getting sound.**

Which one that is depends on your tablet. Android picks one output as the default and
sends everything there; AudioSplit copies the sound to the *other* one. So:

- If your video is already coming out of the **Bluetooth** headphones → pick the **wired** one
- If your video is already coming out of the **wired** earphones → pick the **Bluetooth** one

Not sure which? Step 2 tells you — it prints "your apps currently play to: …". Pick the
other one.

---

### Step 2 — Test that your tablet can actually do this (do this once)

Tap **Test both outputs (6s)**.

You should hear, at the same time:

| Where | What you should hear |
| --- | --- |
| The output you picked | a **low** tone |
| The other headphones | a **high** tone |

**Two different tones in two different ears = it works.** You're good forever; you never
need to run this again.

The app also prints a line saying **"your apps currently play to: …"**. That's where your
video's sound is going right now. It should be the *other* pair — not the one you picked.
If it names the same one you picked, the app will warn you, and you should pick the other
output instead.

**If both tones come out of the same ear**, or you see the word `OVERRIDDEN` in red, your
tablet's software is refusing to send audio to two places at once. Nothing in the app can
fix that — see [If the test fails](#if-the-test-fails) below.

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

**Drag the "Delay on the mirrored side" slider until you both hear the same thing at the
same moment.** It updates live while the film plays, so just nudge it until it sounds
right.

Where to start depends on which pair you're mirroring to:

| Mirroring to | Try around |
| --- | --- |
| The **wired** earphones | **180 ms** — the Bluetooth side is already running late, so the wired copy waits for it |
| The **Bluetooth** headphones | **0 ms** — Bluetooth is already the slow one; adding more would make it worse |

The slider only needs setting once per pair of headphones — the app remembers it.

There's also a **volume slider** for the mirrored side. Use it if one of you needs it
louder than the other. (The tablet's volume buttons move *both* pairs together, which is
why this exists separately.)

---

### When you're done

Tap **Stop sharing**, or hit **Stop** on the notification.

---

## Quick troubleshooting

| What you see | What it means |
| --- | --- |
| The level bar is flat while a video plays | That app blocks audio copying. Netflix, Prime Video and Disney+ all do this on purpose and there's no way around it. Use the browser, Stremio, or a local file. |
| Sound is out of sync | Drag the delay slider (Step 5). |
| One side is too quiet | Use the volume slider, not the volume buttons. |
| Red `OVERRIDDEN` message | Your tablet refused to split the audio. See below. |
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

**From GitHub Actions:** go to the **Actions** tab → **Build APK** → newest run → download
`audiosplit-debug-apk`. Unzip on the tablet and open the APK. You'll need to allow
"install unknown apps" for whatever file manager you use.

> Actions has to be switched on for the repo first:
> **Settings → Actions → General → Allow all actions**, then push anything.

**Building it yourself:**

```bash
./gradlew assembleDebug
```

Needs JDK 17 and an Android SDK with platform 35. The APK lands in
`app/build/outputs/apk/debug/`.

Minimum Android version is **10** — the audio-copying feature didn't exist before that.

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
load-bearing: the ring's occupancy *is* the current latency, so the playback thread can
measure and correct it. The two ends are clocked by different crystals — the system mixer
on one side, the USB DAC's own on the other — and a few dozen ppm is enough to drift a
two-hour film out of sync or overrun the buffer if nothing closes the loop.

Three separate mechanisms, because the three problems have different shapes:

- **Priming** builds the initial cushion. That cushion is the delay.
- **Slider moves** apply as exact deltas, never re-derived from measured occupancy —
  occupancy swings by a whole chunk (21 ms) between reads, so comparing it against a band
  either ignores small moves or chases its own read granularity.
- **Drift** is corrected against a one-second *average* occupancy, which is the only place
  a tolerance band is meaningful.

| Path | What's in it |
| --- | --- |
| `audio/AudioSpec.kt` | PCM format and the control-loop constants, with their invariants |
| `audio/PcmRing.kt` | Ring buffer between the two audio threads |
| `audio/MirrorEngine.kt` | Capture → delay → gain → routed output. The core. |
| `audio/OutputDevices.kt` | Device enumeration and labelling |
| `audio/ToneTester.kt` | The dual-tone routing test and the un-pinned probe |
| `MirrorService.kt` | Foreground service owning the MediaProjection |
| `MirrorState.kt` | StateFlow shared service → UI |
| `MainActivity.kt` | The single screen |

`MIN_CUSHION_MS > DRIFT_TOLERANCE_MS > one CHUNK_BYTES` is an invariant, not a
coincidence. If the floor and the band are equal, an empty ring sits exactly on the band's
low edge and the loop accepts zero occupancy as converged.

</details>
