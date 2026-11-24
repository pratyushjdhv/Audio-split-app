package com.audiosplit.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Sample-level operations on 16-bit little-endian interleaved PCM in [AudioSpec] format. */
internal object Pcm {

    /** Peak magnitude of the samples in the range, 0f..1f. Exactly 0f means digital silence. */
    fun peak(buf: ByteArray, offset: Int, length: Int): Float {
        var peak = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val magnitude = abs(sampleAt(buf, i))
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak / 32768f
    }

    /**
     * Scales samples in place. Separate from the system volume on purpose: the volume rocker
     * moves the source app's output too, so it can't balance one pair against the other.
     */
    fun applyGain(buf: ByteArray, offset: Int, length: Int, gain: Float) {
        if (abs(gain - 1.0f) < 0.001f) return
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            putSample(buf, i, (sampleAt(buf, i) * gain).toInt())
            i += 2
        }
    }

    /**
     * Resamples [frames] frames of [src] into [outFrames] frames of [dst] by linear
     * interpolation, for drift correction. [outFrames] is [frames] plus or minus a couple.
     *
     * This replaces cutting a millisecond out of the stream, which is a step in the
     * waveform and an audible click every time. Here the first and last frames are kept
     * exactly, so the chunk still joins its neighbours seamlessly, and the frame or two
     * gained or lost is spread across the whole chunk: about a 0.1% speed change for 21 ms.
     */
    fun stretch(src: ByteArray, srcOffset: Int, frames: Int, dst: ByteArray, outFrames: Int) {
        require(frames >= 2 && outFrames >= 2) { "stretch needs at least 2 frames each side" }
        val channels = AudioSpec.CHANNEL_COUNT
        val step = (frames - 1).toDouble() / (outFrames - 1)
        for (j in 0 until outFrames) {
            val position = j * step
            val i0 = position.toInt().coerceAtMost(frames - 1)
            val i1 = minOf(i0 + 1, frames - 1)
            val frac = position - i0
            for (c in 0 until channels) {
                val s0 = sampleAt(src, srcOffset + (i0 * channels + c) * 2)
                val s1 = sampleAt(src, srcOffset + (i1 * channels + c) * 2)
                putSample(dst, (j * channels + c) * 2, (s0 + (s1 - s0) * frac).roundToInt())
            }
        }
    }

    private fun sampleAt(buf: ByteArray, i: Int): Int =
        ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()

    private fun putSample(buf: ByteArray, i: Int, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        buf[i] = (v and 0xFF).toByte()
        buf[i + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
