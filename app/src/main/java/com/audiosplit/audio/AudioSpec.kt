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
     * Window over which captured audio is compared against the wall clock to spot a
     * backlog. Long on purpose: the comparison only has to be right about a burst, and a
     * long window makes it immune to ordinary per-chunk jitter.
     */
    const val BACKLOG_WINDOW_MS = 2000

    /**
     * How much more audio than wall-clock time has to arrive before we treat it as a
     * backlog and skip forward. In steady state capture and the clock both run at
     * SAMPLE_RATE, so the excess sits at ~0 — this is orders of magnitude above the noise
     * floor and cannot fire during normal playback.
     */
    const val BACKLOG_RESYNC_MS = 120

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
     * Shared by the mirror and by the routing test on purpose: the test is only meaningful
     * if it certifies byte-for-byte the same stream configuration the mirror will open.
     * OEM effect stages can be bound to a specific content type and can override routing,
     * so a test that passed with different attributes would prove nothing.
     */
    fun mirrorAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
}
