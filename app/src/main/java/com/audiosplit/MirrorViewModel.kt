package com.audiosplit

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiosplit.audio.AudioSpec
import com.audiosplit.audio.DefaultOutput
import com.audiosplit.audio.OutputDevice
import com.audiosplit.audio.OutputDevices
import com.audiosplit.audio.ToneTester
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the source app's audio is going, as far as the system will say. */
sealed interface PlayingNow {
    data object Checking : PlayingNow
    data object Unknown : PlayingNow
    data class Known(val device: OutputDevice) : PlayingNow
}

data class MirrorUiState(
    val status: MirrorStatus = MirrorStatus(),
    val devices: List<OutputDevice> = emptyList(),
    /** Where the copy goes. While a session runs, always the session's own target. */
    val selected: OutputDevice? = null,
    val playingNow: PlayingNow = PlayingNow.Checking,
    val delayMs: Int = 0,
    val gain: Float = 1f,
    val autoSync: Boolean = true,
    val lowLatency: Boolean = false,
    /** Whether the routing test sits on top of the screen; decided once per visit. */
    val showTestCard: Boolean = true,
    val toneRunning: Boolean = false,
    val toneResults: List<ToneTester.Result> = emptyList(),
) {
    private val playingDevice: OutputDevice?
        get() = (playingNow as? PlayingNow.Known)?.device

    /** Both copies would land in one pair and the other person would hear nothing. */
    val collision: Boolean
        get() = selected != null && playingDevice?.id == selected.id

    /**
     * The copy is on the slow (Bluetooth) side. The delay can only ADD delay, so it can't
     * pull a late copy back into line; the player should drive Bluetooth instead, because it
     * shifts its own picture to match whatever output it plays to.
     */
    val copyOnSlowSide: Boolean
        get() = selected?.isBluetooth == true && playingDevice?.isBluetooth != true

    val canStart: Boolean
        get() = selected != null && !status.running
}

/**
 * State for the one screen. Owns the live device list, the copy target, the tuning values
 * and the routing test; the session itself lives in [MirrorService] and is read through
 * [MirrorState].
 */
@OptIn(FlowPreview::class)
class MirrorViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = MirrorSettings(app)
    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val local = MutableStateFlow(
        MirrorUiState(
            gain = settings.gain,
            autoSync = settings.autoSync,
            lowLatency = settings.lowLatency,
            // Fixed for this visit, so the card doesn't jump into Advanced the moment the
            // test passes, taking its result with it.
            showTestCard = !settings.testPassed,
        )
    )

    val uiState: StateFlow<MirrorUiState> =
        combine(local, MirrorState.status) { state, status -> state.copy(status = status) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                local.value.copy(status = MirrorState.status.value),
            )

    // Keeps the list live, so plugging the dongle in mid-session just works.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            refreshDevices()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            refreshDevices()
    }

    /** Lets a newer default-output probe win over a slower, older one. */
    private val probeRun = AtomicInteger()
    private var toneTimer: Job? = null

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)

        viewModelScope.launch {
            combine(
                local.map { it.devices }.distinctUntilChanged(),
                MirrorState.status.map { if (it.running) it.targetId else null }.distinctUntilChanged(),
            ) { devices, sessionTarget -> devices to sessionTarget }
                .collect { (devices, sessionTarget) ->
                    if (sessionTarget == null) resolveSelection(devices) else followSession(devices, sessionTarget)
                    // During a session too: skipping it there meant the screen said "couldn't
                    // tell" when it had simply never looked.
                    probeDefaultOutput()
                }
        }

        // Live tuning: the slider moves the running mirror as it is dragged.
        viewModelScope.launch {
            local.map { Tuning(it.delayMs, it.gain, it.autoSync) }
                .distinctUntilChanged()
                .debounce(TUNING_DEBOUNCE_MS)
                .collect { t ->
                    if (MirrorState.status.value.running) {
                        MirrorService.update(getApplication(), t.delayMs, t.gain, t.autoSync)
                    }
                }
        }
    }

    fun selectDevice(device: OutputDevice) {
        if (MirrorState.status.value.running) return
        settings.chooseDevice(device)
        local.update { it.copy(selected = device, delayMs = settings.delayFor(device)) }
    }

    fun setDelay(delayMs: Int) {
        local.update { it.copy(delayMs = delayMs.coerceIn(0, AudioSpec.MAX_DELAY_MS)) }
    }

    fun nudgeDelay(byMs: Int) {
        setDelay(local.value.delayMs + byMs)
        saveDelay()
    }

    fun saveDelay() {
        val state = local.value
        state.selected?.let { settings.setDelay(it, state.delayMs) }
    }

    fun setGain(gain: Float) = local.update { it.copy(gain = gain) }

    fun saveGain() {
        settings.gain = local.value.gain
    }

    fun setAutoSync(on: Boolean) {
        settings.autoSync = on
        local.update { it.copy(autoSync = on) }
    }

    fun setLowLatency(on: Boolean) {
        settings.lowLatency = on
        local.update { it.copy(lowLatency = on) }
    }

    /**
     * A low tone to the copy target and a high tone to the other headphones at once. Two
     * different tones in two different ears means this device honours per-track routing.
     */
    fun toggleToneTest() {
        if (local.value.toneRunning) {
            stopToneTest()
            return
        }
        val app = getApplication<Application>()
        val state = local.value
        val target = state.selected?.let { OutputDevices.find(app, it.id) }
        if (target == null) {
            MirrorState.error("Choose where the copy should go first.")
            return
        }
        val other = state.devices
            .firstOrNull { it.id != target.id && it.isHeadphoneLike }
            ?.let { OutputDevices.find(app, it.id) }

        local.update { it.copy(toneRunning = true, toneResults = emptyList()) }
        val tones = buildList {
            add(target to LOW_TONE_HZ)
            if (other != null) add(other to HIGH_TONE_HZ)
        }
        ToneTester.start(tones, durationMs = TONE_DURATION_MS) { results ->
            // One pair alone proves nothing about playing to two at once.
            if (results.size >= 2 && results.all { it.honored }) settings.testPassed = true
            local.update { it.copy(toneResults = results) }
        }
        toneTimer?.cancel()
        toneTimer = viewModelScope.launch {
            delay(TONE_DURATION_MS + 300L)
            local.update { it.copy(toneRunning = false) }
        }
    }

    fun stopToneTest() {
        ToneTester.stop()
        toneTimer?.cancel()
        local.update { it.copy(toneRunning = false) }
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        ToneTester.stop()
    }

    private fun refreshDevices() {
        local.update { it.copy(devices = OutputDevices.list(getApplication())) }
    }

    /** No session: settle on a target from the saved choice and what is connected. */
    private fun resolveSelection(devices: List<OutputDevice>) {
        val current = local.value.selected
        val resolved = settings.resolveTarget(devices, current?.id ?: settings.savedDeviceId)
        if (resolved?.id == current?.id) return
        resolved?.let(settings::rememberAutomaticPick)
        local.update {
            it.copy(selected = resolved, delayMs = resolved?.let(settings::delayFor) ?: it.delayMs)
        }
    }

    /**
     * A session is running, possibly started from the tile: show its target as the
     * selection, so the delay slider tunes and saves for the pair actually in use.
     */
    private fun followSession(devices: List<OutputDevice>, targetId: Int) {
        val target = devices.firstOrNull { it.id == targetId } ?: return
        if (target.id == local.value.selected?.id) return
        local.update { it.copy(selected = target, delayMs = settings.delayFor(target)) }
    }

    private fun probeDefaultOutput() {
        val run = probeRun.incrementAndGet()
        DefaultOutput.detect { device ->
            if (probeRun.get() != run) return@detect
            local.update {
                it.copy(playingNow = device?.let(PlayingNow::Known) ?: PlayingNow.Unknown)
            }
        }
    }

    private data class Tuning(val delayMs: Int, val gain: Float, val autoSync: Boolean)

    private companion object {
        const val TUNING_DEBOUNCE_MS = 50L
        const val TONE_DURATION_MS = 6000
        const val LOW_TONE_HZ = 440.0
        const val HIGH_TONE_HZ = 880.0
    }
}
