package com.audiosplit.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.audiosplit.MirrorStatus
import com.audiosplit.MirrorUiState
import com.audiosplit.PlayingNow
import com.audiosplit.RoutingVerdict
import com.audiosplit.audio.AudioSpec
import com.audiosplit.audio.OutputDevice
import com.audiosplit.audio.ToneTester
import kotlin.math.roundToInt

/** Everything the screen can ask for. Defaults do nothing, which is all a preview needs. */
@Stable
interface MirrorActions {
    fun start() {}
    fun pause() {}
    fun resume() {}
    fun stop() {}
    fun dismissError() {}
    fun changePlayingNow() {}
    fun selectDevice(device: OutputDevice) {}
    fun setDelay(delayMs: Int) {}
    fun nudgeDelay(byMs: Int) {}
    fun saveDelay() {}
    fun setGain(gain: Float) {}
    fun saveGain() {}
    fun setAutoSync(on: Boolean) {}
    fun setLowLatency(on: Boolean) {}
    fun toggleToneTest() {}
    fun addTile() {}
}

/**
 * The one screen, top to bottom: what is happening and the button for it, a one-time
 * routing check, which pair gets the copy, the sync controls, and everything else folded
 * away under Advanced.
 */
@Composable
fun MirrorScreen(
    state: MirrorUiState,
    actions: MirrorActions,
    canAddTile: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AudioSplit", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        StatusCard(state, actions)
        if (state.showTestCard) {
            SectionCard("One-time check") { RoutingTest(state, actions) }
        }
        HeadphonesCard(state, actions)
        SyncCard(state, actions)
        AdvancedSection(state, actions, canAddTile)
        Caption(
            "Android calls the permission “screen capture” because it also covers " +
                "audio. Nothing on screen is recorded, and nothing leaves the device."
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(state: MirrorUiState, actions: MirrorActions) {
    val status = state.status
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(lit = status.sharing, paused = status.paused)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    status.sharing -> "Sharing"
                    status.paused -> "Paused"
                    else -> "Not sharing"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            status.sharing -> {
                Text("Copying to ${status.targetLabel}", style = MaterialTheme.typography.bodyLarge)
                LevelMeter(status.level)
                Caption(
                    // Android hands the mirror exact zeros both when nothing is playing and
                    // when the playing app blocks capture, so this can't tell those apart and
                    // mustn't pretend to.
                    if (status.silent) {
                        "Nothing is coming through. Start your video and this picks it up by " +
                            "itself. Netflix, Prime and Disney+ block copying; browsers, " +
                            "Stremio and local files work."
                    } else {
                        "Receiving audio."
                    }
                )
                if (status.verdict == RoutingVerdict.OVERRIDDEN) {
                    Notice(
                        "This device sent the copy to ${status.actualLabel} instead, so both " +
                            "people hear the same pair. It won't split audio between two outputs.",
                        MaterialTheme.colorScheme.error,
                    )
                }
            }

            status.paused -> Caption(
                "Nothing is being copied to ${status.targetLabel}. Resume carries on " +
                    "straight away, with no permission prompt."
            )

            state.selected != null -> Caption(
                "Start your video, then tap Start. The copy goes to ${state.selected.label}."
            )

            else -> Caption("Connect both pairs of headphones to begin.")
        }

        status.error?.let {
            Notice(it, MaterialTheme.colorScheme.error, onDismiss = actions::dismissError)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val big = Modifier
                .weight(1f)
                .height(52.dp)
            when {
                status.sharing -> {
                    FilledTonalButton(onClick = actions::pause, modifier = big) { Text("Pause") }
                    OutlinedButton(onClick = actions::stop, modifier = big) { Text("Stop") }
                }

                status.paused -> {
                    Button(onClick = actions::resume, modifier = big) { Text("Resume") }
                    OutlinedButton(onClick = actions::stop, modifier = big) { Text("Stop") }
                }

                else -> Button(onClick = actions::start, enabled = state.canStart, modifier = big) {
                    Text("Start sharing")
                }
            }
        }
    }
}

@Composable
private fun RoutingTest(state: MirrorUiState, actions: MirrorActions) {
    Caption(
        "Plays a low tone to the copy pair and a high tone to the other pair at the same " +
            "time. Two different tones in two different ears means this device can split audio."
    )
    OutlinedButton(onClick = actions::toggleToneTest, enabled = !state.status.running) {
        Text(if (state.toneRunning) "Stop tones" else "Play test tones (6 s)")
    }

    val results = state.toneResults
    results.forEach { result ->
        Text(
            when {
                result.honored -> "✓ ${result.requested.label}"
                result.routedTo == null -> "? ${result.requested.label}: the system didn't say where it went"
                else -> "✗ ${result.requested.label}: went to ${result.routedTo.label} instead"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (result.honored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    when {
        results.isEmpty() -> Unit
        results.size < 2 -> Caption("Connect the other pair too, to test both at once.")
        results.all { it.honored } -> Caption(
            "It works. You never need to run this again.",
            color = MaterialTheme.colorScheme.primary,
        )

        else -> Caption(
            "This device won't send audio to two places at once, and no app setting can " +
                "change that. See “If the test fails” in the README.",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun HeadphonesCard(state: MirrorUiState, actions: MirrorActions) {
    val running = state.status.running
    SectionCard("Headphones") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("Your video plays to")
                Text(
                    when (val now = state.playingNow) {
                        PlayingNow.Checking -> "Checking…"
                        PlayingNow.Unknown -> "Couldn't tell. This device won't say."
                        is PlayingNow.Known -> now.device.label
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            TextButton(onClick = actions::changePlayingNow, enabled = !running) { Text("Change") }
        }

        HorizontalDivider()

        Caption("Send a copy to")
        if (state.devices.isEmpty()) {
            Text(
                "No headphones found. Connect the Bluetooth pair and plug in the wired pair.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column {
            state.devices.forEach { device ->
                DeviceRow(
                    label = device.label,
                    selected = device.id == state.selected?.id,
                    enabled = !running,
                    onSelect = { actions.selectDevice(device) },
                )
            }
        }

        when {
            state.collision -> Notice(
                "Your video already plays there, so the other person would hear nothing. " +
                    "Pick the other pair.",
                MaterialTheme.colorScheme.error,
            )

            running -> Caption("Stop sharing to switch headphones.")

            state.copyOnSlowSide -> Notice(
                "The copy is going to Bluetooth, the slower pair, and the delay can only hold " +
                    "audio back, not speed it up. Tap Change to send your video to Bluetooth, " +
                    "then copy to the wired pair.",
                MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun SyncCard(state: MirrorUiState, actions: MirrorActions) {
    SectionCard("Sync") {
        DelayControl(
            label = "Delay on the copy",
            valueMs = state.delayMs,
            maxMs = AudioSpec.MAX_DELAY_MS,
            stepMs = DELAY_STEP_MS,
            onChange = actions::setDelay,
            onChangeFinished = actions::saveDelay,
            onNudge = actions::nudgeDelay,
        )
        Caption(
            "Nudge it while the video plays until you both hear the same moment. Copying to " +
                "wired: start near 180 ms. Copying to Bluetooth: start at 0."
        )
        LabelledSlider(
            label = "Copy volume",
            value = state.gain,
            valueText = "${(state.gain * 100).roundToInt()}%",
            range = 0f..2f,
            // Whole 5% steps, so the figure doesn't flicker through odd values while dragging.
            onChange = { actions.setGain((it * 20).roundToInt() / 20f) },
            onChangeFinished = actions::saveGain,
        )
        Caption("The volume buttons change both pairs. This changes only the copy.")
    }
}

@Composable
private fun AdvancedSection(state: MirrorUiState, actions: MirrorActions, canAddTile: Boolean) {
    val status = state.status
    ExpandableSection("Advanced") {
        SwitchRow(
            title = "Hold the sync automatically",
            description = "Checks every 10 seconds and corrects by stretching the sound a " +
                "sample at a time, which you can't hear. Off: a plain copy that may slowly drift.",
            checked = state.autoSync,
            onCheckedChange = actions::setAutoSync,
        )
        SwitchRow(
            title = "Shortest possible delay",
            description = "For when the copy runs late and the slider can't help. May " +
                "stutter on Bluetooth. Applies the next time you start.",
            checked = state.lowLatency,
            onCheckedChange = actions::setLowLatency,
            enabled = !status.running,
        )

        if (!state.showTestCard) {
            HorizontalDivider()
            Text("Routing test", style = MaterialTheme.typography.bodyMedium)
            RoutingTest(state, actions)
        }

        HorizontalDivider()
        Text("Quick Settings tile", style = MaterialTheme.typography.bodyMedium)
        Caption("Start, pause and resume from the pull-down shade without opening the app.")
        if (canAddTile) {
            OutlinedButton(onClick = actions::addTile) { Text("Add to Quick Settings") }
        } else {
            Caption("Open the Quick Settings edit screen and drag the AudioSplit tile in.")
        }

        if (status.sharing) {
            HorizontalDivider()
            // Worth showing because the failure modes sound identical but have different
            // causes: a jump in "resynced" is recovery from a real break, while "drift" is
            // the slow clock mismatch the sample-at-a-time corrections hold in check.
            Text("Sync details", style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append("in output: ")
                    append(if (status.outputLatencyMs < 0) "n/a" else "${status.outputLatencyMs} ms")
                    append(" · drift: ${status.driftMs} ms")
                    append(" · corrected: ${status.corrections * 1000 / AudioSpec.SAMPLE_RATE} ms")
                    append(" · resynced: ${status.resyncs}x")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val DELAY_STEP_MS = 5

private val previewBluetooth = OutputDevice(3, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4", "00:11:22")
private val previewWired = OutputDevice(7, AudioDeviceInfo.TYPE_USB_HEADSET, "USB-C to 3.5mm", "card=1")
private val previewSpeaker = OutputDevice(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "")

private val previewState = MirrorUiState(
    devices = listOf(previewBluetooth, previewWired, previewSpeaker),
    selected = previewWired,
    playingNow = PlayingNow.Known(previewBluetooth),
    delayMs = 180,
    showTestCard = true,
)

@Composable
private fun PreviewFrame(state: MirrorUiState) {
    AudioSplitTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            MirrorScreen(state, object : MirrorActions {}, canAddTile = true)
        }
    }
}

@Preview(name = "Off, first visit", heightDp = 1500)
@Composable
private fun MirrorScreenOffPreview() = PreviewFrame(previewState)

@Preview(name = "Sharing", heightDp = 1200)
@Composable
private fun MirrorScreenSharingPreview() = PreviewFrame(
    previewState.copy(
        showTestCard = false,
        status = MirrorStatus(
            running = true,
            targetId = previewWired.id,
            targetLabel = previewWired.label,
            verdict = RoutingVerdict.HONORED,
            level = 0.6f,
        ),
    )
)

@Preview(name = "Paused, test passed", heightDp = 1200)
@Composable
private fun MirrorScreenPausedPreview() = PreviewFrame(
    previewState.copy(
        showTestCard = false,
        toneResults = listOf(
            ToneTester.Result(previewWired, previewWired),
            ToneTester.Result(previewBluetooth, previewBluetooth),
        ),
        status = MirrorStatus(
            running = true,
            paused = true,
            targetId = previewWired.id,
            targetLabel = previewWired.label,
        ),
    )
)
