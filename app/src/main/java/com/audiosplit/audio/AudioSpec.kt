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

    /**
     * Floor on the buffered cushion. Below this the output starves on ordinary scheduler
     * jitter. It must stay strictly greater than DRIFT_TOLERANCE_MS: if the two were
     * equal, an empty ring would sit exactly on the low edge of the control band at the
     * floor, the loop would accept zero occupancy as converged, and the mirror would
     * splice in silence on every scheduler hiccup forever.
     */
    const val MIN_CUSHION_MS = 60

    /**
     * How far the buffer may wander from target before the drift loop corrects it.
     * Must stay above one CHUNK_BYTES (~21ms) — the playback side removes a whole chunk
     * at a time, so a tighter band would correct against read granularity rather than
     * against real drift — and below MIN_CUSHION_MS, per the note above.
     */
    const val DRIFT_TOLERANCE_MS = 30

    /**
     * An excursion this far from target is corrected in one go rather than at the drift
     * rate, which moves only ~10ms per second and would leave a real buffer excursion
     * audible for a minute or more.
     */
    const val SEEK_REARM_MS = 90

    /** Routing only settles once audio has actually been flowing for a while. */
    const val ROUTING_SETTLE_MS = 400

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
