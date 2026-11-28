package com.audiosplit.audio

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a distinct tone to each of two output devices at the same time.
 *
 * This is the experiment the whole app rests on. If a low tone comes out of the wired
 * earphones while a high tone comes out of the Bluetooth headset, this device honours
 * per-track device routing and the mirror will work. If both tones land in the same ear,
 * the ROM's audio policy is overriding us and no amount of app code fixes it.
 *
 * Per-track routing working is necessary but not sufficient: the source app plays to
 * whatever Android picked as the default, and if that is the mirror's target both copies
 * land in one pair of ears. That half of the question is answered by [DefaultOutput],
 * which the screen shows next to the device list.
 *
 * Tones are built with the mirror's own AudioAttributes, so a pass certifies the
 * configuration the mirror actually opens rather than a lookalike.
 */
object ToneTester {

    data class Result(
        val requested: OutputDevice,
        val routedTo: OutputDevice?,
    ) {
        val honored: Boolean get() = routedTo?.id == requested.id
    }

    @Volatile
    private var active: List<AudioTrack> = emptyList()

    /** Distinguishes runs so a superseded one cannot release the live run's tracks. */
    private val generation = AtomicInteger(0)

    /**
     * Starts one tone per device and reports where each landed. Tones stop on [stop] or
     * after [durationMs].
     */
    fun start(
        devices: List<Pair<AudioDeviceInfo, Double>>,
        durationMs: Int,
        onResults: (List<Result>) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        stopTracks()

        val pinned = devices.map { (device, freq) -> device to buildTrack(device, freq) }

        val all = pinned.map { it.second }
        active = all
        all.forEach { runCatching { it.play() } }

        thread(name = "audiosplit-tone") {
            // routedDevice only becomes meaningful once the stream is actually flowing.
            Thread.sleep(SETTLE_MS)
            if (generation.get() != gen) return@thread

            val results = pinned.map { (device, track) ->
                Result(
                    requested = device.toOutputDevice(),
                    routedTo = track.routedDevice?.toOutputDevice(),
                )
            }
            onResults(results)

            var elapsed = SETTLE_MS
            while (elapsed < durationMs && generation.get() == gen) {
                Thread.sleep(100)
                elapsed += 100
            }
            if (generation.get() == gen) stop()
        }
    }

    fun stop() {
        generation.incrementAndGet()
        stopTracks()
    }

    private fun stopTracks() {
        val tracks = active
        active = emptyList()
        tracks.forEach { track ->
            runCatching { track.pause(); track.flush(); track.stop(); track.release() }
        }
    }

    private fun buildTrack(device: AudioDeviceInfo, frequencyHz: Double): AudioTrack {
        val pcm = sineWave(frequencyHz)

        val track = AudioTrack.Builder()
            // Exactly what the mirror opens, or the test certifies the wrong thing.
            .setAudioAttributes(AudioSpec.mirrorAttributes())
            .setAudioFormat(AudioSpec.playbackFormat())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, pcm.size / AudioSpec.BYTES_PER_FRAME, -1)
        track.setPreferredDevice(device)
        return track
    }

    /** Exactly one second of tone, looped, so the loop point lands on a zero crossing. */
    private fun sineWave(frequencyHz: Double): ByteArray {
        val frames = AudioSpec.SAMPLE_RATE
        val out = ByteArray(frames * AudioSpec.BYTES_PER_FRAME)
        // A whole number of cycles per second means the loop wraps at the same phase.
        val cycles = frequencyHz.toInt().coerceAtLeast(1)
        var i = 0
        for (frame in 0 until frames) {
            val angle = 2.0 * PI * cycles * frame / frames
            val value = (sin(angle) * 0.35 * Short.MAX_VALUE).toInt().toShort().toInt()
            repeat(AudioSpec.CHANNEL_COUNT) {
                out[i] = (value and 0xFF).toByte()
                out[i + 1] = ((value shr 8) and 0xFF).toByte()
                i += 2
            }
        }
        return out
    }

    private const val SETTLE_MS = 500L
}
