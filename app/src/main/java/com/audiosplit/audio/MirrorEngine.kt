package com.audiosplit.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
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
 * to whatever Android picked as the default output, and we push a second copy to the
 * other pair of headphones, so two people hear the same thing.
 *
 * The relay is deliberately one blocking loop, not a buffered pipeline. read() paces
 * itself to the capture clock, write() paces itself to the output clock, and the capture
 * buffer absorbs the difference; at delay 0 neither the inject nor the drop branch below
 * ever fires, so the audio passes through completely untouched.
 *
 * That last property is the whole point, and it is why this is NOT a control loop. An
 * earlier version measured buffer occupancy and corrected it continuously to compensate
 * for clock drift. On Bluetooth that is actively harmful: A2DP drains in bursts, so
 * occupancy swings hard on every packet, and any loop watching it reads normal bursty
 * behaviour as an emergency and starts splicing in silence. The result was continuous
 * choppiness in exchange for fixing a drift problem that takes tens of minutes to become
 * audible. If drift ever does need handling, it has to be measured over minutes and
 * corrected in single frames — never reactively, per chunk.
 *
 * The delay knob exists because Bluetooth is slow. Which side needs holding back depends
 * on which pair Android chose as the default: mirroring to the wired pair usually wants
 * 150-250ms, mirroring to Bluetooth usually wants 0.
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

        /**
         * Sync diagnostics, once per BACKLOG_WINDOW_MS.
         * [excessMs] is captured audio minus wall-clock time over the window: positive
         * means a backlog arrived. [outputLatencyMs] is how much audio is still in flight
         * inside the output stack, which is where slow lip-sync drift shows up.
         */
        fun onSync(excessMs: Int, resyncs: Int, outputLatencyMs: Int, microTrims: Int)

        fun onError(message: String)
    }

    @Volatile
    var delayMs: Int = 0

    @Volatile
    var gain: Float = 1.0f

    /** Whether to hold sync automatically once you've set the delay by ear. */
    @Volatile
    var autoSync: Boolean = true

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var worker: Thread? = null

    /** How much silence we have injected, i.e. the delay currently in effect. */
    private var appliedDelayBytes = 0

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
            val recordBytes = max(minRecord * 4, AudioSpec.msToBytes(250))

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
            // Kept modest so the output stack holds little unaccounted latency. Not a
            // suspect for the transition glitches: too small a buffer here would starve
            // A2DP during steady playback too, and it doesn't.
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

            record = rec
            track = trk
            appliedDelayBytes = 0
            running = true

            rec.startRecording()
            trk.play()

            worker = thread(name = "audiosplit-pump") { pump(rec, trk) }
        } catch (t: Throwable) {
            running = false
            record = null
            track = null
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

        worker?.join(1000)
        worker = null

        record?.runCatching { release() }
        track?.runCatching { stop(); release() }
        record = null
        track = null
    }

    private fun pump(rec: AudioRecord, trk: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(AudioSpec.CHUNK_BYTES)
        val silence = ByteArray(AudioSpec.CHUNK_BYTES)
        var routingReported = false
        var sinceLevelReport = 0
        var bytesWritten = 0L

        // Backlog tracking. When the source app stops and restarts — a Short ending, a
        // track change — the capture side keeps buffering while this loop is parked in a
        // blocking write. On resume that backlog arrives in a burst, and a relay that
        // faithfully plays everything it is handed will play it late, then lose chunks to
        // capture-buffer overrun. Dropped chunks jump the audio forward, which is what
        // "sped up and choppy" actually is.
        //
        // Measured against the wall clock rather than against any buffer level: capture
        // runs at SAMPLE_RATE, so delivered audio should equal elapsed time. Anything more
        // is lag, by definition. Deliberately NOT a reading of the output side — A2DP
        // drains in bursts, and treating that as a signal is what broke Bluetooth before.
        //
        // Kept as a running total since start, not per window. Each transition might only
        // add 50-100ms, which no per-window test would ever flag, but they accumulate and
        // never come back on their own. That slow accumulation is exactly what gets
        // noticed as lip sync drifting an hour into a film.
        val startNanos = System.nanoTime()
        var totalBytesRead = 0L
        var correctedMs = 0L
        var lastCheckNanos = startNanos
        var resyncDebtBytes = 0
        var resyncs = 0
        var microTrims = 0
        var checks = 0
        var outputLatencyMs = -1
        val timestamp = AudioTimestamp()

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
                if (read == 0) {
                    // No data because nothing is playing — you paused the video. This
                    // thread runs at THREAD_PRIORITY_URGENT_AUDIO, above the system's own
                    // audio threads, so spinning here doesn't just waste a core: it starves
                    // the Bluetooth audio HAL and makes everything choppy for as long as
                    // the pause lasts. Yield instead; a few ms of latency while nothing is
                    // playing costs nothing.
                    Thread.sleep(IDLE_BACKOFF_MS)
                    continue
                }

                totalBytesRead += read

                var offset = 0
                var length = read

                // Skip forward through a backlog we already decided to shed.
                if (resyncDebtBytes > 0) {
                    val skip = AudioSpec.alignToFrame(minOf(resyncDebtBytes, length))
                    offset += skip
                    length -= skip
                    resyncDebtBytes -= skip
                }

                // Converge on the requested delay a bit at a time. Jumping straight there
                // would either overrun the capture buffer (injecting) or chop a word in
                // half (dropping), and this runs while someone is listening. At delay 0
                // both branches are skipped entirely.
                val wanted = AudioSpec.alignToFrame(
                    AudioSpec.msToBytes(delayMs.coerceIn(0, AudioSpec.MAX_DELAY_MS))
                )
                val step = AudioSpec.CHUNK_BYTES / 2
                if (appliedDelayBytes < wanted) {
                    val inject = AudioSpec.alignToFrame(minOf(wanted - appliedDelayBytes, step))
                    bytesWritten += writeFully(trk, silence, 0, inject)
                    appliedDelayBytes += inject
                } else if (appliedDelayBytes > wanted) {
                    val drop = AudioSpec.alignToFrame(
                        minOf(appliedDelayBytes - wanted, minOf(step, length))
                    )
                    offset += drop
                    length -= drop
                    appliedDelayBytes -= drop
                }

                if (length > 0) {
                    // Peak is taken before gain, so the level meter answers "is the OS
                    // handing us audio?" rather than "is the mirror volume up?".
                    val peak = applyGainAndPeak(buf, offset, length, gain)
                    bytesWritten += writeFully(trk, buf, offset, length)

                    sinceLevelReport += length
                    if (sinceLevelReport >= AudioSpec.msToBytes(100)) {
                        sinceLevelReport = 0
                        listener.onLevel(peak)
                    }
                }

                val nowNanos = System.nanoTime()
                if ((nowNanos - lastCheckNanos) / 1_000_000L >= AudioSpec.BACKLOG_CHECK_MS) {
                    lastCheckNanos = nowNanos
                    val deliveredMs = totalBytesRead / BYTES_PER_MS
                    val elapsedMs = (nowNanos - startNanos) / 1_000_000L
                    // Everything captured but not yet heard, minus what we've already
                    // skipped. This is how far behind live the mirror has fallen.
                    val lagMs = (deliveredMs - elapsedMs - correctedMs).toInt()
                    if (lagMs > AudioSpec.BACKLOG_RESYNC_MS) {
                        // A real break — a pause, a seek. Too big to trim away; take the
                        // cut and be back in sync now.
                        resyncDebtBytes += AudioSpec.alignToFrame(AudioSpec.msToBytes(lagMs))
                        correctedMs += lagMs.toLong()
                        resyncs++
                    } else if (autoSync && lagMs > AudioSpec.MICRO_DEADBAND_MS) {
                        // Ordinary drift. Shave a millisecond and come back in 250 ms. Far
                        // too small to hear, and because it repeats, the error never gets
                        // the chance to grow into something that would need a jump.
                        val trim = minOf(lagMs, AudioSpec.MICRO_CORRECT_MS)
                        resyncDebtBytes += AudioSpec.alignToFrame(AudioSpec.msToBytes(trim))
                        correctedMs += trim.toLong()
                        microTrims++
                    } else if (lagMs < 0) {
                        // Capture delivered less than real time, which means the source
                        // simply wasn't playing. That is not credit to bank: left to
                        // accumulate, a minute of paused video would push the total so far
                        // negative that no real backlog could ever reach the threshold
                        // again, silently disabling the correction for the whole session.
                        correctedMs += lagMs.toLong()
                    }

                    // How much audio is still queued inside the output stack. Only ever
                    // REPORTED, never fed back into a correction: on A2DP this figure
                    // swings on every packet, and reacting to it per chunk is exactly what
                    // made Bluetooth choppy.
                    if (checks++ % TIMESTAMP_EVERY_N_CHECKS == 0) {
                        outputLatencyMs = if (trk.getTimestamp(timestamp)) {
                            val framesWritten = bytesWritten / AudioSpec.BYTES_PER_FRAME
                            val inFlight = framesWritten - timestamp.framePosition
                            (inFlight * 1000L / AudioSpec.SAMPLE_RATE).toInt().coerceIn(0, 10_000)
                        } else {
                            -1
                        }
                    }
                    listener.onSync(lagMs, resyncs, outputLatencyMs, microTrims)
                }

                // Latched once, after enough audio has flowed for the route to settle.
                // Polling it repeatedly would put a binder call on the audio thread every
                // time round, and this thread must never block on anything but the two
                // audio streams. A null read means "not settled yet", not "overridden".
                if (!routingReported &&
                    bytesWritten >= AudioSpec.msToBytes(AudioSpec.ROUTING_SETTLE_MS)
                ) {
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
     * Scales 16-bit little-endian samples in place and returns the peak of the ORIGINAL
     * samples. Gain is separate from the system volume on purpose: the volume rocker moves
     * the source app's output too, so it can't balance one pair against the other.
     */
    private fun applyGainAndPeak(buf: ByteArray, offset: Int, length: Int, gain: Float): Float {
        var peak = 0
        val unity = abs(gain - 1.0f) < 0.001f
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val sample = (((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()).toInt()
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            if (!unity) {
                val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
                buf[i] = (scaled and 0xFF).toByte()
                buf[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            i += 2
        }
        return peak / 32768f
    }

    private companion object {
        const val TAG = "MirrorEngine"

        /** Bytes of PCM per millisecond, for the lag arithmetic. */
        const val BYTES_PER_MS = (AudioSpec.SAMPLE_RATE / 1000) * AudioSpec.BYTES_PER_FRAME

        /** Backoff when capture has nothing for us, so the loop never spins hot. */
        const val IDLE_BACKOFF_MS = 5L

        /** getTimestamp is a relatively costly call; sample it far less often than the lag. */
        const val TIMESTAMP_EVERY_N_CHECKS = 4
    }
}
