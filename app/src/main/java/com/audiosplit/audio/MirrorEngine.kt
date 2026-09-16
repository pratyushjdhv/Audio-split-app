package com.audiosplit.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Captures whatever the rest of the system is playing and re-plays it to one specific
 * output device.
 *
 * The idea in one line: the source app (browser, Stremio, a music player) keeps playing
 * to whatever Android picked as the default output — with a Bluetooth headset connected
 * that's the Bluetooth headset. We capture the same PCM and push a second copy to the
 * wired dongle, so two people hear the same thing on two different headphones.
 *
 * The delay knob exists because Bluetooth is slow. A2DP typically runs 150-250ms behind
 * the wire, so the wired copy has to be held back to match. It's adjustable while
 * running because the only way to get it right is to nudge it until it sounds right.
 */
class MirrorEngine(
    private val projection: MediaProjection,
    private val target: AudioDeviceInfo,
    private val listener: Listener,
) {

    interface Listener {
        /** Peak amplitude of the last chunk, 0f..1f. Drives the level meter. */
        fun onLevel(peak: Float)

        /** What the OS actually routed us to, vs what we asked for. */
        fun onRouting(honored: Boolean, actual: OutputDevice?)

        fun onError(message: String)
    }

    @Volatile
    var delayMs: Int = 0

    @Volatile
    var gain: Float = 1.0f

    @Volatile
    private var running = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    /** How much silence we've injected so far, i.e. the delay currently in effect. */
    private var appliedDelayBytes = 0

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts.
    fun start(initialDelayMs: Int, initialGain: Float) {
        check(!running) { "already running" }
        delayMs = initialDelayMs
        gain = initialGain

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            // Without this we'd capture our own output and feed it back into itself.
            .excludeUid(Process.myUid())
            .build()

        val minRecord = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE, AudioSpec.IN_CHANNEL_MASK, AudioSpec.ENCODING
        )
        // Generous capture buffer: the playback side blocks while we inject delay, and an
        // overrun here is a permanent audio dropout rather than a recoverable hiccup.
        val recordBytes = max(minRecord * 4, AudioSpec.msToBytes(250))

        val rec = AudioRecord.Builder()
            .setAudioFormat(AudioSpec.captureFormat())
            .setBufferSizeInBytes(recordBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            listener.onError("Could not open the playback capture stream.")
            return
        }

        val minTrack = AudioTrack.getMinBufferSize(
            AudioSpec.SAMPLE_RATE, AudioSpec.OUT_CHANNEL_MASK, AudioSpec.ENCODING
        )
        // Deliberately tight. Every millisecond of output buffer is a millisecond we can
        // never claw back if the wired side ends up lagging Bluetooth instead of leading it.
        val trackBytes = max(minTrack, AudioSpec.msToBytes(80))

        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(AudioSpec.playbackFormat())
            .setBufferSizeInBytes(trackBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (trk.state != AudioTrack.STATE_INITIALIZED) {
            rec.release()
            trk.release()
            listener.onError("Could not open the output stream.")
            return
        }

        // This is the whole trick, and the one part the ROM is free to ignore.
        val accepted = trk.setPreferredDevice(target)
        Log.i(TAG, "setPreferredDevice(${target.id}) accepted=$accepted")

        record = rec
        track = trk
        appliedDelayBytes = 0
        running = true

        rec.startRecording()
        trk.play()

        worker = thread(name = "audiosplit-pump", priority = Thread.MAX_PRIORITY) { pump(rec, trk) }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
        record?.runCatching { stop(); release() }
        track?.runCatching { pause(); flush(); stop(); release() }
        record = null
        track = null
    }

    private fun pump(rec: AudioRecord, trk: AudioTrack) {
        val buf = ByteArray(AudioSpec.CHUNK_BYTES)
        val silence = ByteArray(AudioSpec.CHUNK_BYTES)
        var routingReported = false
        var sinceLevelReport = 0

        try {
            while (running) {
                val read = rec.read(buf, 0, buf.size)
                if (read < 0) {
                    listener.onError("Capture read failed ($read). Another app may have taken the capture stream.")
                    break
                }
                if (read == 0) continue

                var offset = 0
                var length = read

                // Converge on the requested delay a bit at a time. Jumping straight there
                // would either overrun the capture buffer (injecting) or chop a word in
                // half (dropping), and this runs while someone is listening.
                val wanted = alignToFrame(AudioSpec.msToBytes(delayMs.coerceIn(0, AudioSpec.MAX_DELAY_MS)))
                val step = AudioSpec.CHUNK_BYTES / 2
                if (appliedDelayBytes < wanted) {
                    val inject = minOf(wanted - appliedDelayBytes, step)
                    writeFully(trk, silence, 0, alignToFrame(inject))
                    appliedDelayBytes += alignToFrame(inject)
                } else if (appliedDelayBytes > wanted) {
                    val drop = alignToFrame(minOf(appliedDelayBytes - wanted, minOf(step, length)))
                    offset += drop
                    length -= drop
                    appliedDelayBytes -= drop
                }

                if (length > 0) {
                    val peak = applyGainAndPeak(buf, offset, length, gain)
                    writeFully(trk, buf, offset, length)

                    sinceLevelReport += length
                    if (sinceLevelReport >= AudioSpec.msToBytes(100)) {
                        sinceLevelReport = 0
                        listener.onLevel(peak)
                    }
                }

                if (!routingReported) {
                    val actual = trk.routedDevice
                    if (actual != null) {
                        routingReported = true
                        listener.onRouting(actual.id == target.id, actual.toOutputDevice())
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) listener.onError("Mirror stopped: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /** AudioTrack is allowed to accept a partial write; loop until the chunk is gone. */
    private fun writeFully(trk: AudioTrack, data: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length && running) {
            val n = trk.write(data, offset + written, length - written)
            if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
            written += n
        }
    }

    /**
     * Scales 16-bit little-endian samples in place and returns the peak level.
     * The gain is separate from the system volume on purpose: the volume rocker moves the
     * source app's Bluetooth output too, so it can't be used to balance one ear against
     * the other.
     */
    private fun applyGainAndPeak(buf: ByteArray, offset: Int, length: Int, gain: Float): Float {
        var peak = 0
        var i = offset
        val end = offset + length - 1
        val unity = abs(gain - 1.0f) < 0.001f
        while (i < end) {
            val sample = (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()).toInt()
            val scaled = if (unity) sample else {
                (sample * gain).toInt().coerceIn(-32768, 32767)
            }
            if (!unity) {
                buf[i] = (scaled and 0xFF).toByte()
                buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            val magnitude = abs(scaled)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak / 32768f
    }

    private fun alignToFrame(bytes: Int) = bytes - (bytes % AudioSpec.BYTES_PER_FRAME)

    private companion object {
        const val TAG = "MirrorEngine"
    }
}
