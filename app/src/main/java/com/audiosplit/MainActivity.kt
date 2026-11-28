package com.audiosplit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiosplit.audio.OutputDevice
import com.audiosplit.ui.AudioSplitTheme
import com.audiosplit.ui.MirrorActions
import com.audiosplit.ui.MirrorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioSplitTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MirrorRoute()
                }
            }
        }
    }
}

/** Connects the screen to the view model, the service and the two system dialogs. */
@Composable
private fun MirrorRoute(viewModel: MirrorViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorLauncher.start(context, result.resultCode, data, viewModel.uiState.value.selected)
        } else {
            MirrorState.error("Capture permission was declined, so there's nothing to copy.")
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The consent dialog has to wait until this one is gone, or it never appears.
        if (MirrorLauncher.canCapture(context)) {
            consent.launch(MirrorLauncher.captureIntent(context))
        } else {
            MirrorState.error(MirrorLauncher.MIC_NEEDED)
        }
    }

    val actions = remember(viewModel, context, consent, permissions) {
        object : MirrorActions {
            override fun start() {
                viewModel.stopToneTest()
                val missing = MirrorLauncher.missingPermissions(context)
                if (missing.isEmpty()) {
                    consent.launch(MirrorLauncher.captureIntent(context))
                } else {
                    permissions.launch(missing.toTypedArray())
                }
            }

            override fun pause() = MirrorService.pause(context)
            override fun resume() = MirrorService.resume(context)
            override fun stop() = MirrorService.stop(context)
            override fun dismissError() = MirrorState.error(null)
            override fun changePlayingNow() = openOutputSwitcher(context)
            override fun selectDevice(device: OutputDevice) = viewModel.selectDevice(device)
            override fun setDelay(delayMs: Int) = viewModel.setDelay(delayMs)
            override fun nudgeDelay(byMs: Int) = viewModel.nudgeDelay(byMs)
            override fun saveDelay() = viewModel.saveDelay()
            override fun setGain(gain: Float) = viewModel.setGain(gain)
            override fun saveGain() = viewModel.saveGain()
            override fun setAutoSync(on: Boolean) = viewModel.setAutoSync(on)
            override fun setLowLatency(on: Boolean) = viewModel.setLowLatency(on)
            override fun toggleToneTest() = viewModel.toggleToneTest()
            override fun addTile() = MirrorTileService.requestAdd(context)
        }
    }

    MirrorScreen(
        state = state,
        actions = actions,
        canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    )
}

/**
 * Only the system switcher can move another app's audio; no API lets us do it on their
 * behalf. The output-switcher action isn't in the SDK, so try it by name and fall back to
 * the volume panel, then sound settings.
 */
private fun openOutputSwitcher(context: Context) {
    listOf(
        "android.settings.MEDIA_OUTPUT",
        Settings.Panel.ACTION_VOLUME,
        Settings.ACTION_SOUND_SETTINGS,
    ).firstOrNull { action ->
        runCatching { context.startActivity(Intent(action)) }.isSuccess
    }
}
