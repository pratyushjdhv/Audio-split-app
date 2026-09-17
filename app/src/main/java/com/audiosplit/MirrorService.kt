package com.audiosplit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Owns the capture session for as long as the mirror runs. It has to be a foreground
 * service with type mediaProjection — from Android 14 the projection token is refused
 * outright unless one is already running when you redeem it.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var engine: MirrorEngine? = null
    private val main = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture from the system UI.
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                engine?.let { e ->
                    if (intent.hasExtra(EXTRA_DELAY_MS)) e.delayMs = intent.getIntExtra(EXTRA_DELAY_MS, 0)
                    if (intent.hasExtra(EXTRA_GAIN)) e.gain = intent.getFloatExtra(EXTRA_GAIN, 1f)
                    if (intent.hasExtra(EXTRA_AUTO_SYNC)) {
                        e.autoSync = intent.getBooleanExtra(EXTRA_AUTO_SYNC, true)
                    }
                }
                return START_STICKY
            }
        }

        if (intent == null) {
            // Restarted by the system without a projection token — it can't be re-derived.
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
        val delayMs = intent.getIntExtra(EXTRA_DELAY_MS, 0)
        val gain = intent.getFloatExtra(EXTRA_GAIN, 1f)
        val autoSync = intent.getBooleanExtra(EXTRA_AUTO_SYNC, true)
        val lowLatency = intent.getBooleanExtra(EXTRA_LOW_LATENCY, false)

        val target = OutputDevices.find(this, deviceId)
        if (data == null || target == null) {
            MirrorState.error("The chosen output device is no longer connected.")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = manager.getMediaProjection(resultCode, data)
        if (mp == null) {
            MirrorState.error("Screen capture permission was not granted.")
            stopSelf()
            return START_NOT_STICKY
        }
        // Mandatory from Android 14 — the projection throws if used without a callback.
        mp.registerCallback(projectionCallback, main)
        projection = mp

        MirrorState.error(null)
        MirrorState.started(target.toOutputDevice().label)

        try {
            engine = MirrorEngine(mp, target, object : MirrorEngine.Listener {
                override fun onLevel(peak: Float) = MirrorState.level(peak)

                override fun onRouting(honored: Boolean, actual: OutputDevice?) =
                    MirrorState.routing(honored, actual)

                override fun onSync(
                    excessMs: Int,
                    resyncs: Int,
                    outputLatencyMs: Int,
                    microTrims: Int,
                ) = MirrorState.sync(excessMs, resyncs, outputLatencyMs, microTrims)

                override fun onRestart(restarts: Int) = MirrorState.restarted(restarts)

                override fun onError(message: String) {
                    MirrorState.error(message)
                    main.post { stopSelf() }
                }
            }).also {
                it.autoSync = autoSync
                it.lowLatency = lowLatency
                it.start(delayMs, gain)
            }
        } catch (t: Throwable) {
            MirrorState.error("Could not start the mirror: ${t.message ?: t::class.java.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
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

    private fun startForegroundNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio mirror", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while audio is being mirrored to a second headset." }
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing audio")
            .setContentText("Mirroring playback to the second headphones.")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    companion object {
        private const val CHANNEL_ID = "audio_mirror"
        private const val NOTIFICATION_ID = 42

        const val ACTION_STOP = "com.audiosplit.STOP"
        const val ACTION_UPDATE = "com.audiosplit.UPDATE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_AUTO_SYNC = "auto_sync"
        const val EXTRA_LOW_LATENCY = "low_latency"

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
            val intent = Intent(context, MirrorService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_DELAY_MS, delayMs)
                .putExtra(EXTRA_GAIN, gain)
                .putExtra(EXTRA_AUTO_SYNC, autoSync)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }
}
