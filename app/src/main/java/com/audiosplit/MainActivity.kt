package com.audiosplit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiosplit.audio.AudioSpec
import com.audiosplit.audio.OutputDevice
import com.audiosplit.audio.OutputDevices
import com.audiosplit.audio.ToneTester
import com.audiosplit.ui.AudioSplitTheme
import com.audiosplit.ui.DeviceRow
import com.audiosplit.ui.LabelledSlider
import com.audiosplit.ui.LevelMeter
import com.audiosplit.ui.SectionCard
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioSplitTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MirrorScreen()
                }
            }
        }
    }
}

private const val PREFS = "audiosplit"
private const val KEY_DEVICE = "device_id"
private const val KEY_DEVICE_TYPE = "device_type"
private const val KEY_DEVICE_ADDRESS = "device_address"
private const val KEY_USER_CHOSE = "device_user_chose"
private const val KEY_DELAY = "delay_ms"
private const val KEY_GAIN = "gain"
private const val KEY_AUTO_SYNC = "auto_sync"
private const val KEY_LOW_LATENCY = "low_latency"
private const val TONE_DURATION_MS = 6000

@Composable
private fun MirrorScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val status by MirrorState.status.collectAsStateWithLifecycle()

    var devices by remember { mutableStateOf(OutputDevices.list(context)) }
    var selectedId by remember { mutableIntStateOf(prefs.getInt(KEY_DEVICE, -1)) }
    var delayMs by remember {
        mutableFloatStateOf(
            prefs.getInt(KEY_DELAY, 180).coerceIn(0, AudioSpec.MAX_DELAY_MS).toFloat()
        )
    }
    var gain by remember { mutableFloatStateOf(prefs.getFloat(KEY_GAIN, 1f)) }
    var autoSync by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_SYNC, true)) }
    var lowLatency by remember { mutableStateOf(prefs.getBoolean(KEY_LOW_LATENCY, false)) }
    var toneResults by remember { mutableStateOf<List<ToneTester.Result>>(emptyList()) }
    var toneRunning by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStart by remember { mutableStateOf(false) }

    // Keep the list live so plugging the dongle in mid-session just works.
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                devices = OutputDevices.list(context)
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                devices = OutputDevices.list(context)
            }
        }
        am.registerAudioDeviceCallback(callback, null)
        onDispose {
            am.unregisterAudioDeviceCallback(callback)
            ToneTester.stop()
        }
    }

    // Default to the wired pair if we can spot one — that's the mirror target in the
    // common case, since Bluetooth already owns the system default.
    //
    // Never runs while mirroring: moving the selection underneath a running mirror would
    // disable its Stop button. And an explicit pick is never overridden — the earlier
    // version re-applied the wired preference on every device-list change, which silently
    // undid a deliberate choice of the Bluetooth side every time the mirror stopped.
    LaunchedEffect(devices, status.running) {
        if (status.running || devices.isEmpty()) return@LaunchedEffect

        // AudioDeviceInfo ids are handed out per connection and are not stable across
        // reboots or replugs, so a bare saved id can resolve to a completely different
        // device. Match on what actually identifies the hardware.
        val savedType = prefs.getInt(KEY_DEVICE_TYPE, -1)
        val savedAddress = prefs.getString(KEY_DEVICE_ADDRESS, "").orEmpty()
        val userChose = prefs.getBoolean(KEY_USER_CHOSE, false)
        val current = devices.firstOrNull { it.id == selectedId }
        val byIdentity = devices.firstOrNull {
            savedType != -1 && it.type == savedType &&
                (savedAddress.isEmpty() || it.address == savedAddress)
        }

        val resolved = when {
            // An explicit pick stands for as long as that device is around...
            userChose && current != null -> current.id
            // ...and is re-found by identity after a replug or reboot renumbered it.
            userChose && byIdentity != null -> byIdentity.id
            else -> devices.firstOrNull { it.type in WIRED_TYPES }?.id
                ?: current?.id
                ?: byIdentity?.id
                ?: devices.firstOrNull { it.isHeadphoneLike }?.id
                ?: -1
        }

        if (resolved != selectedId) {
            selectedId = resolved
            // Record what we landed on for identity matching, but don't promote an
            // automatic pick into a user choice.
            devices.firstOrNull { it.id == resolved }?.let { persistDevice(prefs, it, userChose) }
        }
    }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data: Intent? = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            MirrorService.start(
                context, result.resultCode, data, selectedId,
                delayMs.roundToInt(), gain, autoSync, lowLatency,
            )
        } else {
            MirrorState.error("Capture permission was declined, so there's nothing to mirror.")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
        if (pendingStart) {
            pendingStart = false
            if (micGranted) {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                MirrorState.error(
                    "AudioSplit needs the microphone permission. Android gates audio capture " +
                        "behind it even though the mic itself is never opened."
                )
            }
        }
    }

    fun persistTuning() {
        prefs.edit()
            .putInt(KEY_DELAY, delayMs.roundToInt())
            .putFloat(KEY_GAIN, gain)
            .putBoolean(KEY_AUTO_SYNC, autoSync)
            .putBoolean(KEY_LOW_LATENCY, lowLatency)
            .apply()
    }

    LaunchedEffect(delayMs, gain, autoSync, status.running) {
        if (!status.running) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        MirrorService.update(context, delayMs.roundToInt(), gain, autoSync)
    }

    LaunchedEffect(toneRunning) {
        if (toneRunning) {
            kotlinx.coroutines.delay(TONE_DURATION_MS + 300L)
            toneRunning = false
        }
    }

    val selected = devices.firstOrNull { it.id == selectedId }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AudioSplit", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Play one thing, hear it on two headphones. Start whatever you're watching " +
                "in its own app, then mirror the sound to the second pair.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(status)

        SectionCard("Step 1 — send the second copy to") {
            if (devices.isEmpty()) {
                Text("No outputs found. Plug in the dongle and connect the Bluetooth headset.")
            }
            devices.forEach { device ->
                DeviceRow(
                    label = device.label,
                    detail = "id ${device.id}" + if (device.address.isNotEmpty()) " · ${device.address}" else "",
                    selected = device.id == selectedId,
                    enabled = !status.running,
                    onSelect = {
                        selectedId = device.id
                        persistDevice(prefs, device, userChose = true)
                    },
                )
            }
            Text(
                "Pick the pair that is NOT currently getting sound. Android already sends " +
                    "everything to the Bluetooth headset, so this is normally the wired one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Step 2 — check this device can do it") {
            Text(
                "Plays a low tone to the selected output and a high tone to the other " +
                    "headphones at the same time. Two different tones in two different ears " +
                    "means it works.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    if (toneRunning) {
                        ToneTester.stop()
                        toneRunning = false
                    } else {
                        val target = selectedId.let { OutputDevices.find(context, it) }
                        val other = devices.firstOrNull { it.id != selectedId && it.isHeadphoneLike }
                            ?.let { OutputDevices.find(context, it.id) }
                        if (target == null) {
                            MirrorState.error("Choose an output first.")
                        } else {
                            toneResults = emptyList()
                            toneRunning = true
                            val pairs = buildList {
                                add(target to 440.0)
                                if (other != null) add(other to 880.0)
                            }
                            ToneTester.start(pairs, durationMs = TONE_DURATION_MS) { results ->
                                toneResults = results
                            }
                        }
                    }
                },
                enabled = !status.running,
            ) {
                Text(if (toneRunning) "Stop tones" else "Test both outputs (6s)")
            }
            toneResults.filter { !it.isDefaultProbe }.forEach { result ->
                val verdict = when {
                    result.honored -> "routed correctly"
                    result.routedTo == null -> "no routing reported"
                    else -> "OVERRIDDEN → went to ${result.routedTo.label}"
                }
                Text(
                    "${result.requested?.label}: $verdict",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (result.honored) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            // Per-track routing working is only half the question. The source app plays to
            // the system default, so if that default IS the mirror target, both copies land
            // in the same ears and the other person hears nothing.
            toneResults.firstOrNull { it.isDefaultProbe }?.let { probe ->
                val collides = probe.routedTo != null && probe.routedTo.id == selectedId
                Text(
                    "your apps currently play to: ${probe.routedTo?.label ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (collides) {
                    Text(
                        "That is the same output you picked to mirror to, so both copies " +
                            "would go to one pair of headphones. Pick the other output.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard("Step 3 — tune it while it plays") {
            LabelledSlider(
                label = "Delay on the mirrored side",
                value = delayMs,
                valueText = "${delayMs.roundToInt()} ms",
                range = 0f..AudioSpec.MAX_DELAY_MS.toFloat(),
                steps = (AudioSpec.MAX_DELAY_MS / 5) - 1,
                hint = "Nudge this until you both hear the same moment. Mirroring to the " +
                    "wired pair usually needs 150-250 ms; mirroring to Bluetooth usually " +
                    "needs 0.",
                onChange = { delayMs = it },
                onChangeFinished = ::persistTuning,
            )
            LabelledSlider(
                label = "Volume of the mirrored side",
                value = gain,
                valueText = "${(gain * 100).roundToInt()}%",
                range = 0f..2f,
                steps = 39,
                hint = "Separate from the volume buttons, which move both headphones at once.",
                onChange = { gain = it },
                onChangeFinished = ::persistTuning,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hold the sync automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Trims a millisecond at a time so the delay you set by ear stays " +
                            "put over a long film. Too small to hear. Turn it off if " +
                            "anything sounds wrong and the mirror goes back to a plain copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoSync,
                    onCheckedChange = { autoSync = it; persistTuning() },
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Shortest possible delay", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Use when the mirrored pair is the one running LATE and the slider " +
                            "can't help, because it only ever adds. Cuts the mirror's own " +
                            "buffering to the minimum. May stutter on Bluetooth — if it " +
                            "does, turn it back off. Takes effect next time you start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = lowLatency,
                    enabled = !status.running,
                    onCheckedChange = { lowLatency = it; persistTuning() },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    if (status.running) {
                        MirrorService.stop(context)
                    } else {
                        ToneTester.stop()
                        toneRunning = false
                        val needed = buildList {
                            if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (needed.isEmpty()) {
                            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        } else {
                            // The projection prompt has to wait until the permission dialog
                            // is gone, or the second dialog never appears.
                            pendingStart = true
                            permissionLauncher.launch(needed.toTypedArray())
                        }
                    }
                },
                // Must stay enabled while running: if the dongle is pulled mid-movie the
                // selected device disappears, and a disabled Stop leaves a mirror that
                // can only be killed from the notification.
                enabled = status.running || selected != null,
                modifier = Modifier.widthIn(min = 160.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text(if (status.running) "Stop sharing" else "Start sharing")
            }
            Text(
                if (selected != null) "→ ${selected.label}" else "No output selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "Android asks for screen-capture permission because that's the same permission " +
                "that covers capturing audio. Nothing on screen is recorded or leaves the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(status: MirrorStatus) {
    SectionCard(if (status.running) "Sharing" else "Not sharing") {
        if (status.running) {
            Text(
                "Mirroring to ${status.targetLabel}",
                style = MaterialTheme.typography.bodyLarge,
            )
            when (status.verdict) {
                RoutingVerdict.HONORED -> Text(
                    "Routing confirmed by the system.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                RoutingVerdict.OVERRIDDEN -> Text(
                    "This device ignored the request and sent the copy to " +
                        "${status.actualLabel}. Both people are hearing the same output — " +
                        "per-track routing is blocked here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                RoutingVerdict.UNKNOWN -> Text(
                    "Waiting for the system to report where the audio went…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LevelMeter(level = status.level, active = true)

            // Sync diagnostics. Worth surfacing because the two failure modes look
            // identical from the sofa but have different causes: a jump in "skipped
            // forward" is the mirror shedding a backlog (a video ending, a track change),
            // while "in output" creeping up over time is lip-sync drift.
            Text(
                buildString {
                    append("in output: ")
                    append(if (status.outputLatencyMs < 0) "n/a" else "${status.outputLatencyMs} ms")
                    append("   ·   trims: ${status.microTrims}")
                    append("   ·   skipped forward: ${status.resyncs}x")
                    if (status.excessMs > 20) append("   ·   catching up ${status.excessMs} ms")
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (status.level > 0.001f) "Receiving audio." else
                    "Silent. If something is playing, that app blocks audio capture (Netflix and " +
                        "friends do). Try the browser or a local file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Start playback in the other app first, then come back and hit start.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        status.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun persistDevice(
    prefs: android.content.SharedPreferences,
    device: OutputDevice,
    userChose: Boolean,
) {
    prefs.edit()
        .putInt(KEY_DEVICE, device.id)
        .putInt(KEY_DEVICE_TYPE, device.type)
        .putString(KEY_DEVICE_ADDRESS, device.address)
        .putBoolean(KEY_USER_CHOSE, userChose)
        .apply()
}

private val WIRED_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
)
