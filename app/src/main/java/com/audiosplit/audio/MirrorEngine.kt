package com.audiosplit.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
 * Capture and playback run on separate threads either side of a [PcmRing]. That split is
 * what makes the delay measurable: the ring's occupancy *is* the current latency, so the
 * playback thread can compare it against the target every iteration and correct. The two
 * ends are clocked by different crystals — the capture side by the system mixer, the
 * output side by the USB DAC — and a few dozen ppm between them is enough to drift a
 * two-hour movie out of sync, or overrun the buffer, if nothing closes the loop.
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
        /** Peak amplitude of the captured audio before gain, 0f..1f. Drives the level meter. */
        fun onLevel(peak: Float)

        /** What the OS actually routed us to, vs what we asked for. Re-reported on change. */
        fun onRouting(honored: Boolean, actual: OutputDevice?)

        fun onError(message: String)
    }

    @Volatile
    var delayMs: Int = 0

    @Volatile
    var gain: Float = 1.0f

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var playbackThread: Thread? = null

    private var ring: PcmRing? = null

    /**
     * Opens both streams and starts mirroring. Never throws: the Builder APIs throw rather
     * than returning an uninitialised object when the device refuses a configuration, and
     * this runs synchronously inside Service.onStartCommand, where an escaping exception
     * takes the whole process down.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts.
    fun start(initialDelayMs: Int, initialGain: Float) {
        check(!running) { "already running" }
        delayMs = initialDelayMs
        gain = initialGain

        var rec: AudioRecord? = null
        var trk: AudioTrack? = null
        try {
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
            val recordBytes = max(minRecord * 2, AudioSpec.msToBytes(200))

            rec = AudioRecord.Builder()
                .setAudioFormat(AudioSpec.captureFormat())
                .setBufferSizeInBytes(recordBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("capture stream did not initialise")
            }

            val minTrack = AudioTrack.getMinBufferSize(
                AudioSpec.SAMPLE_RATE, AudioSpec.OUT_CHANNEL_MASK, AudioSpec.ENCODING
            )
            // Deliberately tight. The delay cushion lives in the ring where we can measure
            // it; buffer hidden inside AudioTrack is latency we cannot account for.
            val trackBytes = max(minTrack, AudioSpec.msToBytes(80))

            trk = AudioTrack.Builder()
                .setAudioAttributes(AudioSpec.mirrorAttributes())
                .setAudioFormat(AudioSpec.playbackFormat())
                .setBufferSizeInBytes(trackBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (trk.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("output stream did not initialise")
            }

            // This is the whole trick, and the one part the ROM is free to ignore.
            val accepted = trk.setPreferredDevice(target)
            Log.i(TAG, "setPreferredDevice(${target.id}) accepted=$accepted")

            ring = PcmRing(AudioSpec.msToBytes(AudioSpec.MAX_DELAY_MS + 600))
            record = rec
            track = trk
            running = true

            rec.startRecording()
            trk.play()

            captureThread = thread(name = "audiosplit-capture") { captureLoop(rec) }
            playbackThread = thread(name = "audiosplit-playback") { playbackLoop(trk) }
        } catch (t: Throwable) {
            running = false
            record = null
            track = null
            ring = null
            rec?.runCatching { stop() }
            rec?.runCatching { release() }
            trk?.runCatching { stop() }
            trk?.runCatching { release() }
            Log.e(TAG, "start failed", t)
            listener.onError(describeStartFailure(t))
        }
    }

    private fun describeStartFailure(t: Throwable) = when (t) {
        is UnsupportedOperationException ->
            "This device refused to open an audio capture stream. If you granted capture " +
                "and then dismissed it from the status bar, try again."
        is SecurityException ->
            "Audio capture was denied. Grant the microphone permission and try again."
        else ->
            "Could not start the mirror: ${t.message ?: t::class.java.simpleName}"
    }

    fun stop() {
        running = false
        // Unblock both threads before joining: read() returns once recording stops, and
        // write() returns once the track is paused. Releasing a stream while another
        // thread is still inside it is a native-level use-after-free.
        record?.runCatching { stop() }
        track?.runCatching { pause(); flush() }

        captureThread?.join(1000)
        playbackThread?.join(1000)
        captureThread = null
        playbackThread = null

        record?.runCatching { release() }
        track?.runCatching { stop(); release() }
        record = null
        track = null
        ring = null
    }

    /** Reads capture into the ring as fast as the OS hands it over. Never blocks on output. */
    private fun captureLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(AudioSpec.CHUNK_BYTES)
        var chunk = 0
        try {
            while (running) {
                val read = rec.read(buf, 0, buf.size)
                if (read < 0) {
                    if (running) {
                        listener.onError(
                            "Capture stopped ($read). Another app may have taken the capture stream."
                        )
                    }
                    break
                }
                if (read == 0) continue

                // Peak is measured here, before gain, so the level meter answers "is the
                // OS handing us audio?" rather than "is the mirror volume up?". Only every
                // fifth chunk is scanned — the other four would be thrown away anyway.
                if (++chunk % LEVEL_EVERY_N_CHUNKS == 0) {
                    listener.onLevel(peakOf(buf, read))
                }

                ring?.write(buf, read)
            }
        } catch (t: Throwable) {
            if (running) listener.onError("Capture failed: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /**
     * Drains the ring to the output device, holding the ring's occupancy at the requested
     * delay.
     *
     * Three mechanisms, because the three problems have genuinely different shapes:
     *
     *  - PRIMING builds the initial cushion before any audio flows. That cushion is the
     *    delay.
     *  - A SLIDER MOVE is applied as an exact delta, never by re-deriving from measured
     *    occupancy. Occupancy swings by a whole chunk between reads, so any scheme that
     *    compares it against a band either ignores small moves (a dead zone on the one
     *    control the user tunes by ear) or chases its own read granularity. Applying the
     *    delta makes every 5ms step produce exactly 5ms.
     *  - DRIFT is tens of ppm between the capture clock and the DAC's own crystal, only
     *    audible over tens of minutes. It is corrected against a one-second *average*
     *    occupancy, which is what makes a band meaningful at all.
     */
    private fun playbackLoop(trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(AudioSpec.CHUNK_BYTES)
        val silence = ByteArray(AudioSpec.CHUNK_BYTES)
        val deadband = AudioSpec.msToBytes(AudioSpec.DRIFT_TOLERANCE_MS)
        val resyncThreshold = AudioSpec.msToBytes(AudioSpec.SEEK_REARM_MS)

        var bytesWritten = 0L
        var lastRoutedId = Int.MIN_VALUE
        var sinceRoutingCheck = 0
        var lastTarget = -1
        var primed = false
        /** Bytes still to be added (+) or removed (-) to honour a slider move or resync. */
        var pendingAdjust = 0
        var occupancySum = 0L
        var occupancySamples = 0
        var sinceDriftCheck = 0

        try {
            while (running) {
                val buffer = ring ?: break
                val targetBytes = currentTargetBytes()
                if (lastTarget < 0) {
                    lastTarget = targetBytes
                } else if (targetBytes != lastTarget) {
                    pendingAdjust += targetBytes - lastTarget
                    lastTarget = targetBytes
                }

                val occupancy = buffer.available

                // Build the cushion before streaming anything. This is the delay.
                if (!primed) {
                    // Priming chases the absolute target, so a slider move made while it
                    // runs is already accounted for — keeping the delta would double it.
                    pendingAdjust = 0
                    if (occupancy < targetBytes) {
                        bytesWritten += writeFully(
                            trk, silence, 0,
                            AudioSpec.alignToFrame(
                                minOf(targetBytes - occupancy, AudioSpec.CHUNK_BYTES)
                            )
                        )
                        continue
                    }
                    primed = true
                }

                // Apply a pending move a slice at a time; a single jump would be audible.
                if (pendingAdjust > 0) {
                    val slice = AudioSpec.alignToFrame(
                        minOf(pendingAdjust, AudioSpec.CHUNK_BYTES)
                    )
                    if (slice > 0) {
                        bytesWritten += writeFully(trk, silence, 0, slice)
                        pendingAdjust -= slice
                    } else {
                        pendingAdjust = 0
                    }
                    continue
                } else if (pendingAdjust < 0) {
                    val slice = AudioSpec.alignToFrame(
                        minOf(-pendingAdjust, AudioSpec.CHUNK_BYTES)
                    )
                    pendingAdjust += if (slice > 0) buffer.discard(slice) else -pendingAdjust
                }

                // Underrun guard: feed the DAC rather than let it run dry and click.
                // Triggers with a chunk still in hand, not at zero — by the time the ring
                // is actually empty the output has already glitched.
                if (occupancy < AudioSpec.CHUNK_BYTES) {
                    bytesWritten += writeFully(trk, silence, 0, CORRECTION_SLICE_BYTES)
                    continue
                }

                val n = buffer.read(buf, buf.size)
                if (n <= 0) continue

                val g = gain
                if (abs(g - 1.0f) >= 0.001f) applyGain(buf, n, g)
                bytesWritten += writeFully(trk, buf, 0, n)

                occupancySum += occupancy.toLong()
                occupancySamples++
                sinceDriftCheck += n

                if (pendingAdjust == 0 && sinceDriftCheck >= AudioSpec.msToBytes(DRIFT_CHECK_MS)) {
                    sinceDriftCheck = 0
                    val average = if (occupancySamples > 0) {
                        (occupancySum / occupancySamples).toInt()
                    } else {
                        occupancy
                    }
                    occupancySum = 0
                    occupancySamples = 0

                    val error = average - targetBytes
                    if (abs(error) > resyncThreshold) {
                        // A real excursion — an overflow, or the output stalled. Correcting
                        // this at the drift rate would leave it audible for a minute, so
                        // schedule the whole difference as one adjustment.
                        pendingAdjust = -error
                    } else if (abs(error) > deadband) {
                        pendingAdjust = -(if (error > 0) {
                            minOf(error, CORRECTION_SLICE_BYTES)
                        } else {
                            maxOf(error, -CORRECTION_SLICE_BYTES)
                        })
                    }
                }

                // Routing only settles once audio has genuinely been flowing, and it can
                // change underneath us if a device connects or drops mid-movie.
                sinceRoutingCheck += n
                if (bytesWritten >= AudioSpec.msToBytes(AudioSpec.ROUTING_SETTLE_MS) &&
                    sinceRoutingCheck >= AudioSpec.msToBytes(ROUTING_CHECK_MS)
                ) {
                    sinceRoutingCheck = 0
                    // A null read means the route is in transition or the device just
                    // went away — not that the ROM overrode us. Reporting it as an
                    // override would blame the device for a jostled cable, on the one
                    // diagnostic this whole app rests on.
                    val actual = trk.routedDevice
                    if (actual != null && actual.id != lastRoutedId) {
                        lastRoutedId = actual.id
                        listener.onRouting(actual.id == target.id, actual.toOutputDevice())
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) listener.onError("Mirror stopped: ${t.message ?: t::class.java.simpleName}")
        }
    }

    /** The delay, in bytes, floored so the output always has something to coast on. */
    private fun currentTargetBytes() = AudioSpec.alignToFrame(
        max(
            AudioSpec.msToBytes(delayMs.coerceIn(0, AudioSpec.MAX_DELAY_MS)),
            AudioSpec.msToBytes(AudioSpec.MIN_CUSHION_MS),
        )
    )

    /** AudioTrack is allowed to accept a partial write; loop until the chunk is gone. */
    private fun writeFully(trk: AudioTrack, data: ByteArray, offset: Int, length: Int): Int {
        var written = 0
        while (written < length && running) {
            val n = trk.write(data, offset + written, length - written)
            if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
            written += n
        }
        return written
    }

    /**
     * Scales 16-bit little-endian samples in place. Separate from the system volume on
     * purpose: the volume rocker moves the source app's Bluetooth output too, so it can't
     * be used to balance one ear against the other. Skipped entirely at unity gain.
     */
    private fun applyGain(buf: ByteArray, length: Int, gain: Float) {
        var i = 0
        while (i + 1 < length) {
            val sample = (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()).toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            buf[i] = (scaled and 0xFF).toByte()
            buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun peakOf(buf: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()).toInt()
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak / 32768f
    }

    private companion object {
        const val TAG = "MirrorEngine"

        /** ~100ms between level reports at 21ms per chunk. */
        const val LEVEL_EVERY_N_CHUNKS = 5

        /** Correct drift gradually; a single large jump would be audible. */
        const val CORRECTION_SLICE_BYTES = AudioSpec.CHUNK_BYTES / 2

        /** Drift accrues over minutes; checking once a second is ample. */
        const val DRIFT_CHECK_MS = 1000

        const val ROUTING_CHECK_MS = 1000
    }
}
