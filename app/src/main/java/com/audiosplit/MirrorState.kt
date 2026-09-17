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
    /** Captured audio minus elapsed time over the last window; positive means a backlog. */
    val excessMs: Int = 0,
    /** How many times the mirror has skipped forward to shed a backlog. */
    val resyncs: Int = 0,
    /** Audio still queued in the output stack; -1 when the device won't report it. */
    val outputLatencyMs: Int = -1,
    /** Inaudible 1 ms trims made to hold sync. */
    val microTrims: Int = 0,
    /** Times the capture stream was reopened after a gap. */
    val restarts: Int = 0,
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
        _status.value = MirrorStatus(running = true, targetLabel = targetLabel)
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

    fun sync(excessMs: Int, resyncs: Int, outputLatencyMs: Int, microTrims: Int) {
        _status.update {
            it.copy(
                excessMs = excessMs,
                resyncs = resyncs,
                outputLatencyMs = outputLatencyMs,
                microTrims = microTrims,
            )
        }
    }

    fun restarted(restarts: Int) {
        _status.update { it.copy(restarts = restarts) }
    }

    fun level(peak: Float) {
        _status.update { it.copy(level = peak) }
    }

    fun error(message: String?) {
        _status.update { it.copy(error = message) }
    }
}
