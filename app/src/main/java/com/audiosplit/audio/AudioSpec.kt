package com.audiosplit.audio

import android.media.AudioAttributes
import android.media.AudioFormat

/**
 * The single PCM format everything in this app speaks. AudioPlaybackCapture resamples
 * whatever the source app is actually playing into this, so we never have to care
 * whether Stremio handed us 44.1k or the browser handed us 48k.
 */
object AudioSpec {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 2
    const val BYTES_PER_FRAME = CHANNEL_COUNT * 2 // 16-bit stereo

    const val IN_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
    const val OUT_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** ~21ms of audio. Small enough to keep the mirror tight, big enough to not thrash. */
    const val CHUNK_BYTES = 4096

    const val MAX_DELAY_MS = 400

    /** Routing only settles once audio has actually been flowing for a while. */
    const val ROUTING_SETTLE_MS = 400

    /**
     * Size of the app-side ring between the capture and playback threads. This is where
     * the delay and any Bluetooth burstiness live — NOT in the platform capture buffer,
     * which Android fixes at its minimum (about 64 ms) for playback capture no matter what
     * size is requested, and which drops audio the moment it fills. Has to hold the
     * largest delay plus the resync threshold plus a burst, with room to spare.
     */
    const val RING_CAPACITY_MS = 2000

    /**
     * How long the playback thread waits for a chunk before giving up for this round. Only
     * reached if capture itself stalls; normally a chunk lands every ~21 ms.
     */
    const val RING_WAIT_MS = 200L

    /**
     * Drift is judged on the ring's AVERAGE fill over this long, never on single readings.
     * Bluetooth drains in bursts and capture arrives in chunks, so instantaneous fill swings
     * by tens of ms all the time; reacting to that is what made every earlier corrector
     * audible. Real clock drift is tens of ppm — under 1 ms per 10 s — so a long window
     * loses nothing.
     */
    const val DRIFT_WINDOW_MS = 10_000L

    /** Ignore the start-up transient before taking the reference fill level. */
    const val DRIFT_SETTLE_MS = 2_000L

    /**
     * Average fill may wander this far from the reference before it is corrected. Well
     * above the noise left in a 10 s average, well below anything audible as lip sync.
     */
    const val DRIFT_TOLERANCE_MS = 15

    /**
     * Most frames one chunk may be stretched or squeezed by when correcting drift. One
     * frame over a 1024-frame chunk is a 0.1% speed change for 21 ms (under 2 cents) —
     * inaudible, unlike cutting samples out. Two keeps up with even a badly matched clock.
     */
    const val MAX_STRETCH_FRAMES = 2

    /**
     * Fill this far from where it should be is not drift but a real break. Too much (an
     * output stall, a burst after a hiccup) is cut in one go; too little (capture stalled
     * and the delay drained away) is put back as silence in one go. Either way sync is right
     * now rather than after minutes of stretching. Large on purpose: a false trigger is an
     * audible cut or gap, and Bluetooth bursts alone swing the fill by tens of ms.
     */
    const val RESYNC_MS = 200

    /**
     * Continuous digital silence for this long means nothing is coming through. Capture
     * never goes quiet by returning no data — Android feeds exact zeros in real time when
     * nothing is playing — so silence is the only signal there is. Films have quiet
     * passages, but almost never exact zeros for this long.
     */
    const val SILENCE_REPORT_MS = 1500L

    fun msToBytes(ms: Int): Int {
        val frames = (SAMPLE_RATE.toLong() * ms / 1000L).toInt()
        return frames * BYTES_PER_FRAME
    }

    fun bytesToMs(bytes: Int): Int {
        val frames = bytes / BYTES_PER_FRAME
        return (frames.toLong() * 1000L / SAMPLE_RATE).toInt()
    }

    fun alignToFrame(bytes: Int) = bytes - (bytes % BYTES_PER_FRAME)

    fun captureFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(IN_CHANNEL_MASK)
        .build()

    fun playbackFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(ENCODING)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(OUT_CHANNEL_MASK)
        .build()

    /**
     * The mirror's own output. Deliberately NOT USAGE_MEDIA, which is the single most
     * important line in the app.
     *
     * A pinned media track and the source app's media track fight. Android routes a whole
     * product strategy to a pinned device whenever every active client in it is pinned, so
     * a mirror started first drags the next video app onto the mirror's device; and OEM
     * policies hook every MUSIC-stream start (OnePlus: Dolby "music effects" and Hi-Fi
     * sample-rate switching) in ways that tear down the OTHER music output. Measured on a
     * OnePlus tablet (OPD2305, Android 14): the mirror and VLC invalidated each other about
     * nine times a second, 740 and 495 stream rebuilds in 80 s, heard on both pairs as
     * "a-a-a-a".
     *
     * Accessibility is the one usage with its own product strategy and its own stream type
     * (ASSISTANT still lives under MEDIA), so pinning it moves nothing else and no music
     * hook fires for it. Its volume follows the media volume unless a screen reader asks
     * otherwise, and the gain slider sits on top.
     *
     * Shared with the routing test's pinned tones on purpose: the test only means something
     * if it opens exactly the configuration the mirror will.
     */
    fun mirrorAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

    /**
     * What a typical video or music app plays with. Only for probing where the SOURCE app's
     * audio lands — never for the mirror, see [mirrorAttributes].
     */
    fun sourceAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
}
