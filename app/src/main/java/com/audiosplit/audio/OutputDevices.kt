package com.audiosplit.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A stable, UI-friendly snapshot of one output device. */
data class OutputDevice(
    val id: Int,
    val type: Int,
    val productName: String,
    val address: String,
) {
    val label: String
        get() {
            val kind = OutputDevices.typeLabel(type)
            val name = productName.trim()
            return if (name.isEmpty() || name.equals(kind, ignoreCase = true)) kind else "$name — $kind"
        }

    /** True for the things you'd actually put in your ears. */
    val isHeadphoneLike: Boolean
        get() = type in OutputDevices.HEADPHONE_TYPES
}

object OutputDevices {

    val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    /** Devices worth offering as a mirror target. Excludes HDMI, telephony, etc. */
    private val OFFERED_TYPES = HEADPHONE_TYPES + setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    fun list(context: Context): List<OutputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in OFFERED_TYPES }
            .map { it.toOutputDevice() }
            .distinctBy { it.id }
            .sortedBy { OFFERED_TYPES.indexOf(it.type) }
    }

    /** Re-resolves a saved device id against the live device list. */
    fun find(context: Context, id: Int): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
    }

    fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset (3.5mm)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones (3.5mm)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-C audio device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        else -> "Output type $type"
    }
}

fun AudioDeviceInfo.toOutputDevice() = OutputDevice(
    id = id,
    type = type,
    productName = productName?.toString().orEmpty(),
    address = address.orEmpty(),
)
