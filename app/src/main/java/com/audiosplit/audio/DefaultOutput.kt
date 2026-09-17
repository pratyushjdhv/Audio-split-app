package com.audiosplit.audio

import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * Works out which device the rest of the system is currently playing to.
 *
 * There is no API that simply asks. So we open a silent stream with the same attributes a
 * media app would use, decline to pin it anywhere, let it settle, and ask where it landed —
 * whatever Android chose for it is what it would choose for VLC or the browser.
 *
 * Worth knowing because the mirror's delay slider can only ADD delay. The player shifts its
 * own picture to match whatever output IT drives, so the slow pair belongs on the player
 * and the mirror belongs on the fast one. Getting that backwards leaves a delay the slider
 * cannot reach, and this is how the app can notice and say so.
 */
object DefaultOutput {

    /** Plays ~half a second of silence to find the current default, then cleans up. */
    fun detect(onResult: (OutputDevice?) -> Unit) {
        thread(name = "audiosplit-default-probe") {
            var track: AudioTrack? = null
            try {
                val frames = AudioSpec.SAMPLE_RATE / 2
                // Not pure silence: an all-zero stream can be optimised away before it is
                // ever routed, and then there is nothing to ask about. One LSB of dither
                // is about -90 dBFS — inaudible on anything, but unmistakably real audio.
                val silence = ByteArray(frames * AudioSpec.BYTES_PER_FRAME)
                for (i in silence.indices step 2) {
                    silence[i] = if ((i / 2) % 2 == 0) 1 else 0
                }
                val built = AudioTrack.Builder()
                    .setAudioAttributes(AudioSpec.mirrorAttributes())
                    .setAudioFormat(AudioSpec.playbackFormat())
                    .setBufferSizeInBytes(silence.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = built
                if (built.state != AudioTrack.STATE_INITIALIZED) {
                    onResult(null)
                    return@thread
                }
                built.write(silence, 0, silence.size)
                built.setLoopPoints(0, frames, -1)
                built.play()
                // Routing is only meaningful once the stream is actually flowing.
                Thread.sleep(SETTLE_MS)
                onResult(built.routedDevice?.toOutputDevice())
            } catch (t: Throwable) {
                onResult(null)
            } finally {
                track?.runCatching { pause(); flush(); stop(); release() }
            }
        }
    }

    private const val SETTLE_MS = 450L
}
