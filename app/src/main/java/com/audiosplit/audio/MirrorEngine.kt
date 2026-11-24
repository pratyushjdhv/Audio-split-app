package com.audiosplit.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioRouting
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
 * Two threads with a [PcmRing] between them. That split is load-bearing, and the reason
 * is a platform fact, not taste: for playback capture Android ignores the buffer size an
 * app asks for and allocates its minimum (measured: 250 ms requested, 64 ms granted), and
 * when that buffer fills it drops audio without telling anyone. The earlier single-loop
 * relay parked the delay slider's silence and every Bluetooth burst in that buffer, so it
 * sat full and overflowed on every hiccup — that was the choppiness — and restarting
 * only "cured" it because a fresh stream starts empty. Now the capture thread does
 * nothing but read and append to the ring, so the platform buffer stays near empty; the
 * delay and the burstiness live in the ring.
 *
 * Drift between the capture clock and the output clock is real but slow (tens of ppm).
 * It is judged ONLY on the ring's average fill over long windows, because instantaneous
 * fill swings by tens of ms on every A2DP burst and capture chunk, and it is corrected by
 * stretching or squeezing a chunk by a frame or two, never by cutting samples out. Both
 * earlier correctors failed exactly there: one reacted per chunk to burst noise; the
 * other read chunk-arrival jitter as lag and cut a millisecond out two or three times a
 * second, even with nothing playing.
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

        /** What the OS actually routed us to, vs what we asked for. */
        fun onRouting(honored: Boolean, actual: OutputDevice?)

        /**
         * Sync diagnostics, about once a second.
         * [driftMs]: how far the ring's average fill sat from where it belongs, as of the
         * last full drift window. [resyncs]: one-step recoveries after a real break (excess
         * cut, or drained delay put back as silence).
         * [outputLatencyMs]: audio still in flight inside the output stack.
         * [corrections]: frames stretched or squeezed so far to hold sync.
         */
        fun onSync(driftMs: Int, resyncs: Int, outputLatencyMs: Int, corrections: Int)

        /**
         * Nothing but exact digital silence for a while. Either nothing is playing or the
         * playing app blocks capture; the capture stream looks identical in both cases.
         */
        fun onSilence()

        fun onError(message: String)
    }

    @Volatile
    var delayMs: Int = 0

    @Volatile
    var gain: Float = 1.0f

    /** Whether to hold sync automatically once you've set the delay by ear. */
    @Volatile
    var autoSync: Boolean = true

    /**
     * Trade buffer headroom for latency. The mirror is always behind the source — it can
     * only play a sample after capturing it — so when the mirror is on the pair that needs
     * to be EARLIER, the delay slider is useless and the only thing left is to make the
     * pipeline itself shorter. Costs underrun headroom, which on A2DP can mean stutter.
     * Read at start(); buffer sizes are fixed when the streams are built.
     */
    @Volatile
    var lowLatency: Boolean = false

    @Volatile
    private var running = false

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var ring: PcmRing? = null

    @Volatile
    private var captureWorker: Thread? = null

    @Volatile
    private var playbackWorker: Thread? = null

    /** DIAG: longest single writeFully() in the current diag window. Playback thread only. */
    private var diagMaxWriteUs = 0L

    /** DIAG: one second of capture-side counters, see [captureStats]. */
    private class CaptureStats(val reads: Int, val silentChunks: Int, val maxReadMs: Int, val peak: Float)

    /**
     * DIAG: published by the capture thread once a second and logged by the playback thread,
     * so the capture thread never formats a string or calls into logd on its hot path.
     */
    @Volatile
    private var captureStats: CaptureStats? = null

    /** DIAG: frames read from the capture stream so far. Written only by the capture thread. */
    @Volatile
    private var framesCaptured = 0L

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
            val capture = buildCapture().also { rec = it }

            val minTrack = AudioTrack.getMinBufferSize(
                AudioSpec.SAMPLE_RATE, AudioSpec.OUT_CHANNEL_MASK, AudioSpec.ENCODING
            )
            // Kept modest so the output stack holds little unaccounted latency. Bursts are
            // absorbed by the ring now, not by this buffer.
            val trackBytes = if (lowLatency) {
                minTrack
            } else {
                max(minTrack, AudioSpec.msToBytes(80))
            }

            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioSpec.mirrorAttributes())
                .setAudioFormat(AudioSpec.playbackFormat())
                .setBufferSizeInBytes(trackBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                .build()
                .also { trk = it }

            if (output.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("output stream did not initialise")
            }

            // This is the whole trick, and the one part the ROM is free to ignore.
            val accepted = output.setPreferredDevice(target)
            Log.i(TAG, "setPreferredDevice(${target.id}) accepted=$accepted")
            Log.i(
                TAG,
                "DIAG start target=${target.type}/${target.id} '${target.productName}' " +
                    "delay=${initialDelayMs}ms gain=$initialGain lowLatency=$lowLatency " +
                    "autoSync=$autoSync trackBytes=$trackBytes (min $minTrack) " +
                    "trackFrames=${output.bufferSizeInFrames} trackRate=${output.sampleRate}"
            )
            // DIAG: event-driven, delivered on the main looper, so it never touches the
            // audio threads. Shows whether the route moves when the source app starts.
            output.addOnRoutingChangedListener(
                AudioRouting.OnRoutingChangedListener { r ->
                    val d = r.routedDevice
                    Log.i(TAG, "DIAG output routed -> ${d?.type}/${d?.id} '${d?.productName}'")
                },
                null,
            )

            // Smaller chunks mean the relay hands audio on sooner, at the cost of doing it
            // more often.
            val chunkBytes = if (lowLatency) AudioSpec.CHUNK_BYTES / 2 else AudioSpec.CHUNK_BYTES
            val pcmRing = PcmRing(AudioSpec.msToBytes(AudioSpec.RING_CAPACITY_MS))

            record = capture
            track = output
            ring = pcmRing
            running = true

            capture.startRecording()
            output.play()

            captureWorker = thread(name = "audiosplit-capture") {
                captureLoop(capture, pcmRing, chunkBytes)
            }
            playbackWorker = thread(name = "audiosplit-playback") {
                playbackLoop(output, pcmRing, chunkBytes)
            }
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

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before the service starts.
    private fun buildCapture(): AudioRecord {
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
        // A request, and on stock Android an ignored one: with a playback-capture config
        // the framework builds the record itself at the platform minimum (64 ms measured
        // against 250 ms asked). Kept in case a ROM honours it. Nothing may RELY on it —
        // that is what the ring is for — and the log line shows what was really granted.
        val requestedBytes = max(minRecord * 4, AudioSpec.msToBytes(250))

        val rec = AudioRecord.Builder()
            .setAudioFormat(AudioSpec.captureFormat())
            .setBufferSizeInBytes(requestedBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("capture stream did not initialise")
        }
        Log.i(
            TAG,
            "DIAG capture opened: requested ${AudioSpec.bytesToMs(requestedBytes)}ms, " +
                "granted ${rec.bufferSizeInFrames * 1000 / AudioSpec.SAMPLE_RATE}ms " +
                "(${rec.bufferSizeInFrames} frames, min ${AudioSpec.bytesToMs(minRecord)}ms)"
        )
        return rec
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
        Log.i(TAG, "DIAG stop requested")
        running = false
        // Unblock every thread before joining: read() returns once recording stops, the
        // ring wakes its waiting reader when closed, and write() returns once the track is
        // paused. Releasing a stream while another thread is still inside it is a
        // native-level use-after-free.
        record?.runCatching { stop() }
        ring?.close()
        track?.runCatching { pause(); flush() }

        captureWorker?.join(1000)
        playbackWorker?.join(1000)
        captureWorker = null
        playbackWorker = null

        record?.runCatching { release() }
        track?.runCatching { stop(); release() }
        record = null
        track = null
        ring = null
    }

    /**
     * Reads the capture stream and appends to the ring. Must never wait on anything but
     * read(): every millisecond spent elsewhere piles up in the platform buffer, which is
     * tiny and drops audio when full.
     */
    private fun captureLoop(rec: AudioRecord, ring: PcmRing, chunkBytes: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(chunkBytes)
        var silentSinceNanos = 0L
        var silenceReported = false
        var levelBytes = 0
        var levelPeak = 0f

        // DIAG: counters handed to the playback thread once a second as [captureStats].
        var statsSinceNanos = System.nanoTime()
        var dReads = 0
        var dSilentChunks = 0
        var dMaxReadUs = 0L
        var dPeak = 0f

        try {
            while (running) {
                val readStart = System.nanoTime()
                val read = rec.read(buf, 0, buf.size)
                val now = System.nanoTime()
                if (read < 0) {
                    Log.e(TAG, "DIAG capture read returned $read, running=$running")
                    if (running) {
                        listener.onError(
                            "Capture stopped ($read). Another app may have taken the capture stream."
                        )
                    }
                    break
                }

                // Peak is taken before gain, so the meter answers "is the OS handing us
                // audio?" rather than "is the mirror volume up?". No data at all counts as
                // silence too, so a ROM that returns nothing still reports it.
                val peak = if (read > 0) {
                    ring.write(buf, 0, read)
                    framesCaptured += read / AudioSpec.BYTES_PER_FRAME
                    Pcm.peak(buf, 0, read)
                } else {
                    0f
                }

                if (peak > 0f) {
                    silentSinceNanos = 0L
                    silenceReported = false
                } else if (silentSinceNanos == 0L) {
                    silentSinceNanos = now
                } else if (!silenceReported &&
                    (now - silentSinceNanos) / 1_000_000L >= AudioSpec.SILENCE_REPORT_MS
                ) {
                    silenceReported = true
                    listener.onSilence()
                }

                if (peak > levelPeak) levelPeak = peak
                levelBytes += read
                if (levelBytes >= AudioSpec.msToBytes(100)) {
                    listener.onLevel(levelPeak)
                    levelBytes = 0
                    levelPeak = 0f
                }

                dReads++
                val readUs = (now - readStart) / 1000L
                if (readUs > dMaxReadUs) dMaxReadUs = readUs
                if (peak == 0f) dSilentChunks++
                if (peak > dPeak) dPeak = peak
                if ((now - statsSinceNanos) / 1_000_000L >= DIAG_EVERY_MS) {
                    captureStats =
                        CaptureStats(dReads, dSilentChunks, (dMaxReadUs / 1000).toInt(), dPeak)
                    statsSinceNanos = now
                    dReads = 0
                    dSilentChunks = 0
                    dMaxReadUs = 0L
                    dPeak = 0f
                }

                if (read == 0) {
                    // Not seen on stock Android, which feeds exact zeros in real time when
                    // nothing plays. But a ROM that returns nothing must not make this
                    // thread spin: it runs at URGENT_AUDIO, above the system's own audio
                    // threads, and spinning would starve the Bluetooth HAL.
                    Thread.sleep(IDLE_BACKOFF_MS)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DIAG capture thread died, running=$running", t)
            if (running) listener.onError("Mirror stopped: ${t.message ?: t::class.java.simpleName}")
        }
        Log.i(TAG, "DIAG capture thread exited")
    }

    /**
     * Takes chunks from the ring and writes them to the pinned output. All the waiting on
     * the output side (A2DP drains in bursts) happens here, where the ring absorbs it.
     */
    private fun playbackLoop(trk: AudioTrack, ring: PcmRing, chunkBytes: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(chunkBytes)
        // Room for a chunk stretched by the most frames one correction may add.
        val stretched =
            ByteArray(chunkBytes + AudioSpec.MAX_STRETCH_FRAMES * AudioSpec.BYTES_PER_FRAME)
        val silence = ByteArray(chunkBytes)
        val chunkFrames = chunkBytes / AudioSpec.BYTES_PER_FRAME
        var bytesWritten = 0L
        var appliedDelayBytes = 0
        var routingReported = false
        val timestamp = AudioTimestamp()
        var outputLatencyMs = -1

        // Drift state, all in ring bytes. The reference is the average fill measured once
        // things settle; from then on the average should stay there, moved only by exactly
        // what the delay slider adds or removes.
        val startNanos = System.nanoTime()
        var referenceFill = -1L
        var windowStartNanos = 0L
        var windowSum = 0L
        var windowCount = 0
        var pendingFrames = 0 // > 0: squeeze frames out; < 0: stretch frames in
        var corrections = 0
        var resyncs = 0
        var driftMs = 0
        var lastSyncNanos = startNanos
        // Set while silence is being padded in for a stall that hasn't ended yet, so one
        // long stall counts and logs as one resync rather than one per pass.
        var stillPadding = false

        // DIAG: per-second counters, logged only. Capture-side figures arrive via
        // [captureStats] so all logging happens on this thread.
        val recordTimestamp = AudioTimestamp()
        var lastOverflowBytes = 0L
        var diagLastNanos = startNanos
        var dFillMin = Int.MAX_VALUE
        var dFillMax = 0
        var dFillSum = 0L
        var dFillCount = 0
        var dStarved = 0
        var dInjected = 0
        var dDropped = 0
        diagMaxWriteUs = 0L

        fun resetWindow() {
            windowStartNanos = 0L
            windowSum = 0L
            windowCount = 0
        }

        try {
            while (running) {
                // 1. The delay slider, applied as exact deltas half a chunk per pass, so a
                //    big move neither floods the output nor chops a word in one go. The
                //    reference moves by exactly the same amount, so a slider move is never
                //    mistaken for drift.
                val wanted = AudioSpec.alignToFrame(
                    AudioSpec.msToBytes(delayMs.coerceIn(0, AudioSpec.MAX_DELAY_MS))
                )
                if (wanted != appliedDelayBytes) {
                    val step = AudioSpec.alignToFrame(chunkBytes / 2)
                    val delta = if (wanted > appliedDelayBytes) {
                        val inject = minOf(wanted - appliedDelayBytes, step)
                        bytesWritten += writeFully(trk, silence, 0, inject)
                        dInjected += inject
                        inject
                    } else {
                        val dropped = ring.skip(minOf(appliedDelayBytes - wanted, step))
                        dDropped += dropped
                        -dropped
                    }
                    appliedDelayBytes += delta
                    if (referenceFill >= 0) referenceFill += delta
                    pendingFrames = 0
                    resetWindow()
                }

                // 2. Fill, sampled at the same point every pass (just before taking a
                //    chunk) so the long-window average is consistent with the reference.
                val fill = ring.available
                val now = System.nanoTime()
                if (fill < dFillMin) dFillMin = fill
                if (fill > dFillMax) dFillMax = fill
                dFillSum += fill
                dFillCount++

                // 3. A real break, either way. Far too much buffered (an output stall, a
                //    burst) is cut in one go; far too little (capture stalled and the delay
                //    drained away) is put back as silence in one go. Either way sync is right
                //    immediately instead of after minutes of stretching. Too little is only
                //    judged once a reference exists: before that, part of the delay
                //    legitimately sits in the output track rather than the ring.
                val expectedFill =
                    if (referenceFill >= 0) referenceFill else appliedDelayBytes + 2L * chunkBytes
                val offBy = fill - expectedFill
                val resyncBytes = AudioSpec.msToBytes(AudioSpec.RESYNC_MS)
                if (offBy > resyncBytes || (referenceFill >= 0 && -offBy > resyncBytes)) {
                    val padding = offBy < 0
                    val fixedBytes = if (padding) {
                        var missing = AudioSpec.alignToFrame((-offBy).toInt())
                        var written = 0
                        while (missing > 0 && running) {
                            val n = writeFully(trk, silence, 0, minOf(missing, silence.size))
                            if (n == 0) break
                            written += n
                            missing -= n
                        }
                        bytesWritten += written
                        written
                    } else {
                        ring.skip(offBy.toInt())
                    }
                    if (!(padding && stillPadding)) {
                        resyncs++
                        Log.w(
                            TAG,
                            "DIAG resync #$resyncs: fill ${AudioSpec.bytesToMs(fill)}ms vs " +
                                "expected ${AudioSpec.bytesToMs(expectedFill.toInt())}ms, " +
                                "${if (padding) "padded" else "cut"} " +
                                "${AudioSpec.bytesToMs(fixedBytes)}ms"
                        )
                    }
                    stillPadding = padding
                    pendingFrames = 0
                    resetWindow()
                    continue
                }

                // Off means a plain copy from now on, including abandoning a correction
                // that is part-way through.
                if (!autoSync && pendingFrames != 0) {
                    pendingFrames = 0
                    resetWindow()
                }

                // 4. Drift, judged only on the average over a long window.
                val settled = (now - startNanos) / 1_000_000L >= AudioSpec.DRIFT_SETTLE_MS
                if (settled && appliedDelayBytes == wanted && pendingFrames == 0) {
                    if (windowCount == 0) windowStartNanos = now
                    windowSum += fill
                    windowCount++
                    if ((now - windowStartNanos) / 1_000_000L >= AudioSpec.DRIFT_WINDOW_MS) {
                        val mean = windowSum / windowCount
                        if (referenceFill < 0) {
                            referenceFill = mean
                            Log.i(
                                TAG,
                                "DIAG drift reference fill ${AudioSpec.bytesToMs(mean.toInt())}ms"
                            )
                        } else {
                            val drift = mean - referenceFill
                            driftMs = AudioSpec.bytesToMs(drift.toInt())
                            if (autoSync && abs(driftMs) > AudioSpec.DRIFT_TOLERANCE_MS) {
                                pendingFrames = (drift / AudioSpec.BYTES_PER_FRAME).toInt()
                                Log.i(TAG, "DIAG drift ${driftMs}ms: correcting $pendingFrames frames")
                            }
                        }
                        resetWindow()
                    }
                }

                // 5. Relay one chunk.
                val got = ring.read(buf, 0, chunkBytes, AudioSpec.RING_WAIT_MS)
                if (got < chunkBytes && running) dStarved++
                if (got <= 0) continue
                stillPadding = false

                var out = buf
                var outLen = got
                if (pendingFrames != 0 && got == chunkBytes) {
                    val n = minOf(abs(pendingFrames), AudioSpec.MAX_STRETCH_FRAMES)
                    val outFrames = if (pendingFrames > 0) chunkFrames - n else chunkFrames + n
                    Pcm.stretch(buf, 0, chunkFrames, stretched, outFrames)
                    pendingFrames += if (pendingFrames > 0) -n else n
                    corrections += n
                    out = stretched
                    outLen = outFrames * AudioSpec.BYTES_PER_FRAME
                    // Measure afresh once the correction is done.
                    if (pendingFrames == 0) resetWindow()
                }
                Pcm.applyGain(out, 0, outLen, gain)
                bytesWritten += writeFully(trk, out, 0, outLen)

                // 6. Reporting only; none of this feeds back into the audio. On A2DP the
                //    in-flight figure swings on every packet, and reacting to it is exactly
                //    what made Bluetooth choppy before.
                if ((now - lastSyncNanos) / 1_000_000L >= SYNC_REPORT_MS) {
                    lastSyncNanos = now
                    outputLatencyMs = if (trk.getTimestamp(timestamp)) {
                        val framesWritten = bytesWritten / AudioSpec.BYTES_PER_FRAME
                        val inFlight = framesWritten - timestamp.framePosition
                        (inFlight * 1000L / AudioSpec.SAMPLE_RATE).toInt().coerceIn(0, 10_000)
                    } else {
                        -1
                    }
                    listener.onSync(driftMs, resyncs, outputLatencyMs, corrections)
                }

                // Latched once, after enough audio has flowed for the route to settle.
                // Polling it repeatedly would put a binder call on the audio thread every
                // time round. A null read means "not settled yet", not "overridden".
                if (!routingReported &&
                    bytesWritten >= AudioSpec.msToBytes(AudioSpec.ROUTING_SETTLE_MS)
                ) {
                    val actual = trk.routedDevice
                    if (actual != null) {
                        routingReported = true
                        Log.i(
                            TAG,
                            "DIAG routing latched: wanted ${target.type}/${target.id}, " +
                                "got ${actual.type}/${actual.id} '${actual.productName}'"
                        )
                        listener.onRouting(actual.id == target.id, actual.toOutputDevice())
                    }
                }

                // DIAG: one summary line per second, capture side included.
                //   platformBacklog -> audio waiting in the platform capture buffer; should
                //                      stay near a chunk. Near the buffer size = about to drop.
                //   fill            -> ring min/avg/max; the delay plus burst headroom
                //   overflow        -> the ring itself overflowed; should always be 0
                //   drift           -> last window's average fill vs the reference
                //   starved         -> less than a chunk waiting (capture stalled)
                //   underruns       -> the output track ran dry (cumulative)
                val diagMs = (now - diagLastNanos) / 1_000_000L
                if (diagMs >= DIAG_EVERY_MS) {
                    val avgFill = if (dFillCount > 0) (dFillSum / dFillCount).toInt() else 0
                    val cap = captureStats
                    val rec = record
                    val backlogMs = if (rec != null &&
                        rec.getTimestamp(recordTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                        AudioRecord.SUCCESS
                    ) {
                        val capturedNow = recordTimestamp.framePosition +
                            (System.nanoTime() - recordTimestamp.nanoTime) *
                            AudioSpec.SAMPLE_RATE / 1_000_000_000L
                        ((capturedNow - framesCaptured) * 1000L / AudioSpec.SAMPLE_RATE).toInt()
                    } else {
                        -1
                    }
                    val overflow = ring.overflowBytes
                    Log.i(
                        TAG,
                        "DIAG ${diagMs}ms | capture reads=${cap?.reads ?: "-"} " +
                            "silent=${cap?.silentChunks ?: "-"} maxRead=${cap?.maxReadMs ?: "-"}ms " +
                            "platformBacklog=${backlogMs}ms " +
                            "peak=${if (cap == null) "-" else "%.3f".format(cap.peak)} " +
                            "| ring fill=${AudioSpec.bytesToMs(dFillMin)}/" +
                            "${AudioSpec.bytesToMs(avgFill)}/${AudioSpec.bytesToMs(dFillMax)}ms " +
                            "overflow=${AudioSpec.bytesToMs((overflow - lastOverflowBytes).toInt())}ms " +
                            "| sync ref=${if (referenceFill < 0) "-" else AudioSpec.bytesToMs(referenceFill.toInt())}ms " +
                            "drift=${driftMs}ms pending=$pendingFrames corrections=$corrections " +
                            "resyncs=$resyncs " +
                            "| output starved=$dStarved maxWrite=${diagMaxWriteUs / 1000}ms " +
                            "inject=${AudioSpec.bytesToMs(dInjected)}ms " +
                            "drop=${AudioSpec.bytesToMs(dDropped)}ms " +
                            "underruns=${trk.underrunCount} inOutput=${outputLatencyMs}ms"
                    )
                    lastOverflowBytes = overflow
                    diagLastNanos = now
                    dFillMin = Int.MAX_VALUE
                    dFillMax = 0
                    dFillSum = 0L
                    dFillCount = 0
                    dStarved = 0
                    dInjected = 0
                    dDropped = 0
                    diagMaxWriteUs = 0L
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DIAG playback thread died, running=$running", t)
            if (running) listener.onError("Mirror stopped: ${t.message ?: t::class.java.simpleName}")
        }
        Log.i(TAG, "DIAG playback thread exited")
    }

    /** AudioTrack is allowed to accept a partial write; loop until the chunk is gone. */
    private fun writeFully(trk: AudioTrack, data: ByteArray, offset: Int, length: Int): Int {
        val start = System.nanoTime()
        var written = 0
        while (written < length && running) {
            val n = trk.write(data, offset + written, length - written)
            if (n < 0) throw IllegalStateException("AudioTrack.write returned $n")
            written += n
        }
        val us = (System.nanoTime() - start) / 1000L
        if (us > diagMaxWriteUs) diagMaxWriteUs = us
        return written
    }

    private companion object {
        const val TAG = "MirrorEngine"

        /** Backoff when capture has nothing for us, so the loop never spins hot. */
        const val IDLE_BACKOFF_MS = 5L

        /** How often sync figures go to the UI. getTimestamp is not free; once a second. */
        const val SYNC_REPORT_MS = 1000L

        /** DIAG: how often the one-line summaries go to logcat. */
        const val DIAG_EVERY_MS = 1000L
    }
}
