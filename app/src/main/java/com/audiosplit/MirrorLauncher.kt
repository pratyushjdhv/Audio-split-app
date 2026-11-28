package com.audiosplit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.audiosplit.audio.OutputDevice
import com.audiosplit.audio.OutputDevices
import com.audiosplit.audio.ToneTester

/**
 * The one way a sharing session starts, whether from the screen or the Quick Settings tile.
 *
 * Every fresh session needs the system's screen-capture consent: the token it returns
 * can't be kept and reused. Pausing keeps the session, so resuming needs none.
 */
object MirrorLauncher {

    const val MIC_NEEDED =
        "AudioSplit needs the microphone permission. Android gates audio capture behind it " +
            "even though the mic itself is never opened."

    /**
     * Permissions to ask for before the consent dialog. Only RECORD_AUDIO is required; the
     * notification permission just keeps the Pause and Stop buttons visible.
     */
    fun missingPermissions(context: Context): List<String> = buildList {
        if (!context.granted(Manifest.permission.RECORD_AUDIO)) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun canCapture(context: Context) = context.granted(Manifest.permission.RECORD_AUDIO)

    /**
     * The consent dialog. From Android 14 it is pinned to the whole display: otherwise the
     * dialog also offers to share "a single app", a choice that means nothing for audio and
     * only makes people stop and wonder.
     */
    fun captureIntent(context: Context): Intent {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /** Where a session started now would send the copy, from the saved choice. */
    fun resolveTarget(context: Context): OutputDevice? =
        MirrorSettings(context).resolveTarget(OutputDevices.list(context))

    /**
     * Starts a session with consent already granted. [target] is the screen's current
     * selection; without one (the tile) the saved choice is resolved against what is
     * connected now.
     */
    fun start(context: Context, resultCode: Int, data: Intent, target: OutputDevice? = null) {
        val settings = MirrorSettings(context)
        val device = target ?: settings.resolveTarget(OutputDevices.list(context))
        if (device == null) {
            MirrorState.error("No headphones to copy to. Connect both pairs and try again.")
            return
        }
        ToneTester.stop()
        MirrorService.start(
            context,
            resultCode = resultCode,
            data = data,
            deviceId = device.id,
            delayMs = settings.delayFor(device),
            gain = settings.gain,
            autoSync = settings.autoSync,
            lowLatency = settings.lowLatency,
        )
    }

    private fun Context.granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
