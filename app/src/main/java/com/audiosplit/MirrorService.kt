package com.audiosplit

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.audiosplit.audio.MirrorEngine
import com.audiosplit.audio.OutputDevice
import com.audiosplit.audio.OutputDevices
import com.audiosplit.audio.toOutputDevice

/**
 * Owns the capture session for as long as it lasts. It has to be a foreground service with
 * type mediaProjection — from Android 14 the projection token is refused outright unless one
 * is already running when you redeem it.
 *
 * Pausing tears down the [MirrorEngine] but keeps the [MediaProjection], so resuming builds a
 * fresh engine on the same projection with no consent dialog. A fresh start always needs one.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var engine: MirrorEngine? = null
    private val main = Handler(Looper.getMainLooper())

    // The session as it stands, so a resume rebuilds the engine exactly as it was plus any
    // changes made while paused.
    private var targetId = -1
    private var targetLabel: String? = null
    private var delayMs = 0
    private var gain = 1f
    private var autoSync = true
    private var lowLatency = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture from the system UI.
            main.post { stopSelf() }
        }
    }

    private val engineListener = object : MirrorEngine.Listener {
        override fun onLevel(peak: Float) = MirrorState.level(peak)

        override fun onRouting(honored: Boolean, actual: OutputDevice?) =
            MirrorState.routing(honored, actual)

        override fun onSync(driftMs: Int, resyncs: Int, outputLatencyMs: Int, corrections: Int) =
            MirrorState.sync(driftMs, resyncs, outputLatencyMs, corrections)

        override fun onSilence() = MirrorState.silence()

        override fun onError(message: String) {
            MirrorState.error(message)
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Restarted by the system without a projection token — it can't be re-derived.
            stopSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        if (action != null) {
            if (projection == null) {
                // A control with no session to act on: a slider update racing Stop, or a tile
                // tap racing the system revoking capture. Starting the service for it created
                // a service with nothing to do, so end it again.
                stopSelf()
                return START_NOT_STICKY
            }
            when (action) {
                ACTION_STOP -> stopSelf()
                ACTION_UPDATE -> applyUpdate(intent)
                ACTION_PAUSE -> pause()
                ACTION_RESUME -> resume()
            }
            return START_NOT_STICKY
        }

        if (projection != null) {
            // A second start while a session is live, e.g. a double tap. One session at a
            // time; the spare consent token is simply dropped.
            return START_NOT_STICKY
        }
        startSession(intent)
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val target = OutputDevices.find(this, intent.getIntExtra(EXTRA_DEVICE_ID, -1))
        delayMs = intent.getIntExtra(EXTRA_DELAY_MS, 0)
        gain = intent.getFloatExtra(EXTRA_GAIN, 1f)
        autoSync = intent.getBooleanExtra(EXTRA_AUTO_SYNC, true)
        lowLatency = intent.getBooleanExtra(EXTRA_LOW_LATENCY, false)
        targetLabel = target?.toOutputDevice()?.label

        // Before anything that can fail: a service started with startForegroundService has
        // to reach startForeground even if it is about to stop again.
        startForeground(
            MirrorNotification.ID,
            MirrorNotification.build(this, targetLabel, paused = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        if (data == null || target == null) {
            MirrorState.error("The chosen output device is no longer connected.")
            stopSelf()
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            MirrorState.error("Screen capture permission was not granted.")
            stopSelf()
            return
        }
        // Mandatory from Android 14 — the projection throws if used without a callback.
        mp.registerCallback(projectionCallback, main)
        projection = mp
        targetId = target.id

        MirrorState.error(null)
        MirrorState.started(target.id, targetLabel.orEmpty())
        startEngine(target)
    }

    private fun startEngine(target: AudioDeviceInfo) {
        val mp = projection ?: return
        try {
            engine = MirrorEngine(mp, target, engineListener).also {
                it.autoSync = autoSync
                it.lowLatency = lowLatency
                it.start(delayMs, gain)
            }
        } catch (t: Throwable) {
            MirrorState.error("Could not start the mirror: ${t.message ?: t::class.java.simpleName}")
            stopSelf()
        }
    }

    private fun applyUpdate(intent: Intent) {
        if (intent.hasExtra(EXTRA_DELAY_MS)) delayMs = intent.getIntExtra(EXTRA_DELAY_MS, delayMs)
        if (intent.hasExtra(EXTRA_GAIN)) gain = intent.getFloatExtra(EXTRA_GAIN, gain)
        if (intent.hasExtra(EXTRA_AUTO_SYNC)) {
            autoSync = intent.getBooleanExtra(EXTRA_AUTO_SYNC, autoSync)
        }
        engine?.let {
            it.delayMs = delayMs
            it.gain = gain
            it.autoSync = autoSync
        }
    }

    private fun pause() {
        val running = engine ?: return
        engine = null
        running.stop()
        MirrorState.paused()
        refreshNotification()
    }

    private fun resume() {
        if (engine != null) return
        // Ids hold while the device stays connected; a replug while paused gets a new one.
        val target = OutputDevices.find(this, targetId)
        if (target == null) {
            MirrorState.error("The headphones you were copying to are no longer connected.")
            stopSelf()
            return
        }
        MirrorState.error(null)
        // Before the engine starts, so its first reports land on a clean slate.
        MirrorState.resumed()
        refreshNotification()
        startEngine(target)
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(
            MirrorNotification.ID,
            MirrorNotification.build(this, targetLabel, paused = engine == null),
        )
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        MirrorState.stopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.audiosplit.STOP"
        const val ACTION_UPDATE = "com.audiosplit.UPDATE"
        const val ACTION_PAUSE = "com.audiosplit.PAUSE"
        const val ACTION_RESUME = "com.audiosplit.RESUME"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_DELAY_MS = "delay_ms"
        private const val EXTRA_GAIN = "gain"
        private const val EXTRA_AUTO_SYNC = "auto_sync"
        private const val EXTRA_LOW_LATENCY = "low_latency"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            deviceId: Int,
            delayMs: Int,
            gain: Float,
            autoSync: Boolean,
            lowLatency: Boolean,
        ) {
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_DELAY_MS, delayMs)
                .putExtra(EXTRA_GAIN, gain)
                .putExtra(EXTRA_AUTO_SYNC, autoSync)
                .putExtra(EXTRA_LOW_LATENCY, lowLatency)
            context.startForegroundService(intent)
        }

        fun update(context: Context, delayMs: Int, gain: Float, autoSync: Boolean) {
            send(
                context,
                Intent(context, MirrorService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_DELAY_MS, delayMs)
                    .putExtra(EXTRA_GAIN, gain)
                    .putExtra(EXTRA_AUTO_SYNC, autoSync),
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) =
            send(context, Intent(context, MirrorService::class.java).setAction(action))

        /**
         * Controls are only sent while a session holds the service in the foreground, which
         * is what makes a plain startService legal from the background. If the session ends
         * in the same instant, Android may refuse the start; there is nothing left to control
         * then, so that is not worth crashing over.
         */
        private fun send(context: Context, intent: Intent) {
            runCatching { context.startService(intent) }
        }
    }
}
