package com.audiosplit.audio

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

    fun msToBytes(ms: Int): Int {
        val frames = (SAMPLE_RATE.toLong() * ms / 1000L).toInt()
        return frames * BYTES_PER_FRAME
    }

    fun bytesToMs(bytes: Int): Int {
        val frames = bytes / BYTES_PER_FRAME
        return (frames.toLong() * 1000L / SAMPLE_RATE).toInt()
    }

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
}
