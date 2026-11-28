package com.audiosplit

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.audiosplit.audio.AudioSpec
import com.audiosplit.audio.OutputDevice

/**
 * Everything the app remembers between launches. Shared by the screen and by the Quick
 * Settings tile, which has to start a session with no screen open.
 *
 * The key names predate this class. Keep them, or an update loses people's settings.
 */
class MirrorSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyDelay()
    }

    /** Device id the copy last went to. Only meaningful while that connection lasts. */
    val savedDeviceId: Int
        get() = prefs.getInt(KEY_DEVICE, -1)

    var gain: Float
        get() = prefs.getFloat(KEY_GAIN, 1f)
        set(value) = prefs.edit { putFloat(KEY_GAIN, value) }

    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SYNC, value) }

    var lowLatency: Boolean
        get() = prefs.getBoolean(KEY_LOW_LATENCY, false)
        set(value) = prefs.edit { putBoolean(KEY_LOW_LATENCY, value) }

    /** The two-tone routing test has passed on this device at least once. */
    var testPassed: Boolean
        get() = prefs.getBoolean(KEY_TEST_PASSED, false)
        set(value) = prefs.edit { putBoolean(KEY_TEST_PASSED, value) }

    /**
     * Picks the output the copy goes to from what is connected right now.
     *
     * An explicit pick stands for as long as that device is around, and is found again by
     * identity after a replug or reboot renumbers it: AudioDeviceInfo ids are handed out per
     * connection, so a bare saved id can resolve to a completely different device. Without
     * an explicit pick (or while it is disconnected) the wired pair is preferred, since in
     * the common case Bluetooth already owns the system default.
     */
    fun resolveTarget(
        devices: List<OutputDevice>,
        currentId: Int = savedDeviceId,
    ): OutputDevice? {
        if (devices.isEmpty()) return null
        val current = devices.firstOrNull { it.id == currentId }
        val byIdentity = devices.firstOrNull(::matchesSaved)

        val explicit = if (prefs.getBoolean(KEY_USER_CHOSE, false)) {
            current?.takeIf(::matchesSaved) ?: byIdentity
        } else {
            null
        }
        return explicit
            ?: devices.firstOrNull { it.isWired }
            ?: current
            ?: byIdentity
            ?: devices.firstOrNull { it.isHeadphoneLike }
    }

    /** The user tapped this device. It stands until they tap another. */
    fun chooseDevice(device: OutputDevice) = persistDevice(device, userChose = true)

    /**
     * Records a device the app landed on by itself. Never promoted to a user choice, and never
     * allowed to overwrite one: a chosen pair that is briefly disconnected must still be
     * recognised when it comes back. Only its fresh id is recorded once it is back.
     */
    fun rememberAutomaticPick(device: OutputDevice) {
        val userChose = prefs.getBoolean(KEY_USER_CHOSE, false)
        when {
            !userChose -> persistDevice(device, userChose = false)
            matchesSaved(device) -> persistDevice(device, userChose = true)
        }
    }

    /**
     * Delay last used with this pair. Remembered per pair because the right value depends on
     * which side is the slow one. Bluetooth already runs late, so a copy sent there starts
     * at 0; a copy sent to a wire has to wait for the Bluetooth side.
     */
    fun delayFor(device: OutputDevice): Int =
        prefs.getInt(delayKey(device.type, device.address), defaultDelay(device))
            .coerceIn(0, AudioSpec.MAX_DELAY_MS)

    fun setDelay(device: OutputDevice, delayMs: Int) =
        prefs.edit { putInt(delayKey(device.type, device.address), delayMs) }

    private fun matchesSaved(device: OutputDevice): Boolean {
        val savedType = prefs.getInt(KEY_DEVICE_TYPE, -1)
        val savedAddress = prefs.getString(KEY_DEVICE_ADDRESS, "").orEmpty()
        return savedType != -1 && device.type == savedType &&
            (savedAddress.isEmpty() || device.address == savedAddress)
    }

    private fun persistDevice(device: OutputDevice, userChose: Boolean) = prefs.edit {
        putInt(KEY_DEVICE, device.id)
        putInt(KEY_DEVICE_TYPE, device.type)
        putString(KEY_DEVICE_ADDRESS, device.address)
        putBoolean(KEY_USER_CHOSE, userChose)
    }

    /** Up to 1.3 there was one delay for everything; it belongs to the pair saved back then. */
    private fun migrateLegacyDelay() {
        if (!prefs.contains(KEY_LEGACY_DELAY)) return
        val type = prefs.getInt(KEY_DEVICE_TYPE, -1)
        val address = prefs.getString(KEY_DEVICE_ADDRESS, "").orEmpty()
        val legacy = prefs.getInt(KEY_LEGACY_DELAY, DEFAULT_WIRED_DELAY_MS)
        prefs.edit {
            if (type != -1 && !prefs.contains(delayKey(type, address))) {
                putInt(delayKey(type, address), legacy)
            }
            remove(KEY_LEGACY_DELAY)
        }
    }

    private fun delayKey(type: Int, address: String) = "delay_${type}_$address"

    private fun defaultDelay(device: OutputDevice) =
        if (device.isBluetooth) 0 else DEFAULT_WIRED_DELAY_MS

    private companion object {
        const val PREFS = "audiosplit"
        const val KEY_DEVICE = "device_id"
        const val KEY_DEVICE_TYPE = "device_type"
        const val KEY_DEVICE_ADDRESS = "device_address"
        const val KEY_USER_CHOSE = "device_user_chose"
        const val KEY_LEGACY_DELAY = "delay_ms"
        const val KEY_GAIN = "gain"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_LOW_LATENCY = "low_latency"
        const val KEY_TEST_PASSED = "routing_test_passed"

        /** Roughly how far Bluetooth runs behind a wire. */
        const val DEFAULT_WIRED_DELAY_MS = 180
    }
}
