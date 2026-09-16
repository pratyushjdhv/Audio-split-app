package com.audiosplit.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioTrack
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
 */
object ToneTester {

    data class Result(
        val requested: OutputDevice,
        val requestAccepted: Boolean,
        val routedTo: OutputDevice?,
    ) {
        val honored: Boolean get() = routedTo?.id == requested.id
    }

    @Volatile
    private var active: List<AudioTrack> = emptyList()

    val isPlaying: Boolean get() = active.isNotEmpty()

    /**
     * Starts one tone per device and reports, per device, whether the OS honoured the
     * routing request. Tones stop on [stop] or after [durationMs].
     */
    fun start(
        devices: List<Pair<AudioDeviceInfo, Double>>,
        durationMs: Int,
        onResults: (List<Result>) -> Unit,
    ) {
        stop()

        val tracks = devices.map { (device, freq) -> buildTrack(device, freq) }
        active = tracks.map { it.first }

        tracks.forEach { (track, _) -> track.play() }

        thread(name = "audiosplit-tone") {
            // routedDevice only becomes meaningful once the stream is actually flowing.
            Thread.sleep(400)
            val results = tracks.mapIndexed { index, (track, accepted) ->
                Result(
                    requested = devices[index].first.toOutputDevice(),
                    requestAccepted = accepted,
                    routedTo = track.routedDevice?.toOutputDevice(),
                )
            }
            onResults(results)

            val deadline = System.currentTimeMillis() + durationMs - 400
            while (System.currentTimeMillis() < deadline && isPlaying) {
                Thread.sleep(100)
            }
            stop()
        }
    }

    fun stop() {
        val tracks = active
        active = emptyList()
        tracks.forEach { track ->
            runCatching { track.pause(); track.flush(); track.stop(); track.release() }
        }
    }

    private fun buildTrack(device: AudioDeviceInfo, frequencyHz: Double): Pair<AudioTrack, Boolean> {
        val pcm = sineWave(frequencyHz)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(AudioSpec.playbackFormat())
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.setLoopPoints(0, pcm.size / AudioSpec.BYTES_PER_FRAME, -1)
        val accepted = track.setPreferredDevice(device)
        return track to accepted
    }

    /** Exactly one second of tone, looped, so the loop point lands on a zero crossing. */
    private fun sineWave(frequencyHz: Double): ByteArray {
        // Round to a whole number of cycles per second so looping doesn't click.
        val cycles = frequencyHz.toInt().coerceAtLeast(1)
        val frames = AudioSpec.SAMPLE_RATE
        val out = ByteArray(frames * AudioSpec.BYTES_PER_FRAME)
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
}
