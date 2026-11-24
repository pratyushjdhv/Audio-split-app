package com.audiosplit

import com.audiosplit.audio.OutputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the mirror's routing request ended up. */
enum class RoutingVerdict { UNKNOWN, HONORED, OVERRIDDEN }

data class MirrorStatus(
    val running: Boolean = false,
    val targetLabel: String? = null,
    val verdict: RoutingVerdict = RoutingVerdict.UNKNOWN,
    val actualLabel: String? = null,
    val level: Float = 0f,
    val error: String? = null,
    /** How far the mirror's buffered audio had drifted from where it belongs, last check. */
    val driftMs: Int = 0,
    /** Times the mirror snapped back into sync after a real break (a cut, or silence put back). */
    val resyncs: Int = 0,
    /** Audio still queued in the output stack; -1 when the device won't report it. */
    val outputLatencyMs: Int = -1,
    /** Frames stretched or squeezed, a sample at a time, to hold sync. */
    val corrections: Int = 0,
    /**
     * Nothing but digital silence for a while: nothing is playing, or the app playing
     * blocks capture. The capture stream can't tell those two apart.
     */
    val silent: Boolean = false,
)

/**
 * Single source of truth shared between the foreground service (which owns the audio)
 * and the UI (which only ever reads it). Deliberately a plain object rather than a bound
 * service — there's exactly one mirror and exactly one screen.
 *
 * Mutators use update {} rather than assignment because the capture and playback threads
 * both post here. A plain read-copy-write can drop a concurrent change, and the routing
 * verdict is edge-triggered — it is only re-sent when the route actually changes, so
 * losing it once loses it for the rest of the session.
 */
object MirrorState {
    private val _status = MutableStateFlow(MirrorStatus())
    val status: StateFlow<MirrorStatus> = _status.asStateFlow()

    fun started(targetLabel: String) {
        // Silent until the first real sample arrives, so a mirror started before the video
        // never claims to be receiving audio.
        _status.value = MirrorStatus(running = true, targetLabel = targetLabel, silent = true)
    }

    fun stopped() {
        _status.update { it.copy(running = false, level = 0f) }
    }

    fun routing(honored: Boolean, actual: OutputDevice?) {
        _status.update {
            it.copy(
                verdict = if (honored) RoutingVerdict.HONORED else RoutingVerdict.OVERRIDDEN,
                actualLabel = actual?.label,
            )
        }
    }

    fun sync(driftMs: Int, resyncs: Int, outputLatencyMs: Int, corrections: Int) {
        _status.update {
            it.copy(
                driftMs = driftMs,
                resyncs = resyncs,
                outputLatencyMs = outputLatencyMs,
                corrections = corrections,
            )
        }
    }

    fun level(peak: Float) {
        // Only real audio ends the silent state; a quiet reading during it changes nothing.
        _status.update { it.copy(level = peak, silent = it.silent && peak <= 0f) }
    }

    fun silence() {
        _status.update { it.copy(level = 0f, silent = true) }
    }

    fun error(message: String?) {
        _status.update { it.copy(error = message) }
    }
}
